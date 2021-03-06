/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.mapreduce;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding;
import org.apache.hadoop.hbase.io.hfile.AbstractHFileWriter;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.HFileContext;
import org.apache.hadoop.hbase.io.hfile.HFileContextBuilder;
import org.apache.hadoop.hbase.regionserver.BloomType;
import org.apache.hadoop.hbase.regionserver.HStore;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.partition.TotalOrderPartitioner;

/**
 * Writes HFiles. Passed KeyValues must arrive in order.
 * Writes current time as the sequence id for the file. Sets the major compacted
 * attribute on created hfiles. Calling write(null,null) will forceably roll
 * all HFiles being written.
 * <p>
 * Using this class as part of a MapReduce job is best done
 * using {@link #configureIncrementalLoad(Job, HTable)}.
 * @see KeyValueSortReducer
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public class HFileOutputFormat extends FileOutputFormat<ImmutableBytesWritable, KeyValue> {
  static Log LOG = LogFactory.getLog(HFileOutputFormat.class);
  static final String COMPRESSION_CONF_KEY = "hbase.hfileoutputformat.families.compression";
  private static final String BLOOM_TYPE_CONF_KEY = "hbase.hfileoutputformat.families.bloomtype";
  private static final String DATABLOCK_ENCODING_CONF_KEY =
     "hbase.mapreduce.hfileoutputformat.datablock.encoding";
  private static final String BLOCK_SIZE_CONF_KEY = "hbase.mapreduce.hfileoutputformat.blocksize";

  public RecordWriter<ImmutableBytesWritable, KeyValue> getRecordWriter(final TaskAttemptContext context)
  throws IOException, InterruptedException {
    // Get the path of the temporary output file
    final Path outputPath = FileOutputFormat.getOutputPath(context);
    final Path outputdir = new FileOutputCommitter(outputPath, context).getWorkPath();
    final Configuration conf = context.getConfiguration();
    final FileSystem fs = outputdir.getFileSystem(conf);
    // These configs. are from hbase-*.xml
    final long maxsize = conf.getLong(HConstants.HREGION_MAX_FILESIZE,
        HConstants.DEFAULT_MAX_FILE_SIZE);
    // Invented config.  Add to hbase-*.xml if other than default compression.
    final String defaultCompression = conf.get("hfile.compression",
        Compression.Algorithm.NONE.getName());
    final boolean compactionExclude = conf.getBoolean(
        "hbase.mapreduce.hfileoutputformat.compaction.exclude", false);

    // create a map from column family to the compression algorithm
    final Map<byte[], String> compressionMap = createFamilyCompressionMap(conf);
    final Map<byte[], String> bloomTypeMap = createFamilyBloomMap(conf);
    final Map<byte[], String> blockSizeMap = createFamilyBlockSizeMap(conf);

    final String dataBlockEncodingStr = conf.get(DATABLOCK_ENCODING_CONF_KEY);

    return new RecordWriter<ImmutableBytesWritable, KeyValue>() {
      // Map of families to writers and how much has been output on the writer.
      private final Map<byte [], WriterLength> writers =
        new TreeMap<byte [], WriterLength>(Bytes.BYTES_COMPARATOR);
      private byte [] previousRow = HConstants.EMPTY_BYTE_ARRAY;
      private final byte [] now = Bytes.toBytes(System.currentTimeMillis());
      private boolean rollRequested = false;

      public void write(ImmutableBytesWritable row, KeyValue kv)
      throws IOException {
        // null input == user explicitly wants to flush
        if (row == null && kv == null) {
          rollWriters();
          return;
        }

        byte [] rowKey = kv.getRow();
        long length = kv.getLength();
        byte [] family = kv.getFamily();
        WriterLength wl = this.writers.get(family);

        // If this is a new column family, verify that the directory exists
        if (wl == null) {
          fs.mkdirs(new Path(outputdir, Bytes.toString(family)));
        }

        // If any of the HFiles for the column families has reached
        // maxsize, we need to roll all the writers
        if (wl != null && wl.written + length >= maxsize) {
          this.rollRequested = true;
        }

        // This can only happen once a row is finished though
        if (rollRequested && Bytes.compareTo(this.previousRow, rowKey) != 0) {
          rollWriters();
        }

        // create a new HLog writer, if necessary
        if (wl == null || wl.writer == null) {
          wl = getNewWriter(family, conf);
        }

        // we now have the proper HLog writer. full steam ahead
        kv.updateLatestStamp(this.now);
        wl.writer.append(kv);
        wl.written += length;

        // Copy the row so we know when a row transition.
        this.previousRow = rowKey;
      }

      private void rollWriters() throws IOException {
        for (WriterLength wl : this.writers.values()) {
          if (wl.writer != null) {
            LOG.info("Writer=" + wl.writer.getPath() +
                ((wl.written == 0)? "": ", wrote=" + wl.written));
            close(wl.writer);
          }
          wl.writer = null;
          wl.written = 0;
        }
        this.rollRequested = false;
      }

      /* Create a new StoreFile.Writer.
       * @param family
       * @return A WriterLength, containing a new StoreFile.Writer.
       * @throws IOException
       */
      private WriterLength getNewWriter(byte[] family, Configuration conf)
          throws IOException {
        WriterLength wl = new WriterLength();
        Path familydir = new Path(outputdir, Bytes.toString(family));
        String compression = compressionMap.get(family);
        compression = compression == null ? defaultCompression : compression;
        String bloomTypeStr = bloomTypeMap.get(family);
        BloomType bloomType = BloomType.NONE;
        if (bloomTypeStr != null) {
          bloomType = BloomType.valueOf(bloomTypeStr);
        }
        String blockSizeString = blockSizeMap.get(family);
        int blockSize = blockSizeString == null ? HConstants.DEFAULT_BLOCKSIZE
            : Integer.parseInt(blockSizeString);
        Configuration tempConf = new Configuration(conf);
        tempConf.setFloat(HConstants.HFILE_BLOCK_CACHE_SIZE_KEY, 0.0f);
        HFileContextBuilder contextBuilder = new HFileContextBuilder()
                                    .withCompression(AbstractHFileWriter.compressionByName(compression))
                                    .withChecksumType(HStore.getChecksumType(conf))
                                    .withBytesPerCheckSum(HStore.getBytesPerChecksum(conf))
                                    .withBlockSize(blockSize);
        if(dataBlockEncodingStr !=  null) {
          contextBuilder.withDataBlockEncoding(DataBlockEncoding.valueOf(dataBlockEncodingStr));
        }
        HFileContext hFileContext = contextBuilder.build();
                                    
        wl.writer = new StoreFile.WriterBuilder(conf, new CacheConfig(tempConf), fs)
            .withOutputDir(familydir).withBloomType(bloomType).withComparator(KeyValue.COMPARATOR)
            .withFileContext(hFileContext)
            .build();

        this.writers.put(family, wl);
        return wl;
      }

      private void close(final StoreFile.Writer w) throws IOException {
        if (w != null) {
          w.appendFileInfo(StoreFile.BULKLOAD_TIME_KEY,
              Bytes.toBytes(System.currentTimeMillis()));
          w.appendFileInfo(StoreFile.BULKLOAD_TASK_KEY,
              Bytes.toBytes(context.getTaskAttemptID().toString()));
          w.appendFileInfo(StoreFile.MAJOR_COMPACTION_KEY,
              Bytes.toBytes(true));
          w.appendFileInfo(StoreFile.EXCLUDE_FROM_MINOR_COMPACTION_KEY,
              Bytes.toBytes(compactionExclude));
          w.appendTrackedTimestampsToMetadata();
          w.close();
        }
      }

      public void close(TaskAttemptContext c)
      throws IOException, InterruptedException {
        for (WriterLength wl: this.writers.values()) {
          close(wl.writer);
        }
      }
    };
  }

  /*
   * Data structure to hold a Writer and amount of data written on it.
   */
  static class WriterLength {
    long written = 0;
    StoreFile.Writer writer = null;
  }

  /**
   * Return the start keys of all of the regions in this table,
   * as a list of ImmutableBytesWritable.
   */
  private static List<ImmutableBytesWritable> getRegionStartKeys(HTable table)
  throws IOException {
    byte[][] byteKeys = table.getStartKeys();
    ArrayList<ImmutableBytesWritable> ret =
      new ArrayList<ImmutableBytesWritable>(byteKeys.length);
    for (byte[] byteKey : byteKeys) {
      ret.add(new ImmutableBytesWritable(byteKey));
    }
    return ret;
  }

  /**
   * Write out a {@link SequenceFile} that can be read by
   * {@link TotalOrderPartitioner} that contains the split points in startKeys.
   */
  private static void writePartitions(Configuration conf, Path partitionsPath,
      List<ImmutableBytesWritable> startKeys) throws IOException {
    LOG.info("Writing partition information to " + partitionsPath);
    if (startKeys.isEmpty()) {
      throw new IllegalArgumentException("No regions passed");
    }

    // We're generating a list of split points, and we don't ever
    // have keys < the first region (which has an empty start key)
    // so we need to remove it. Otherwise we would end up with an
    // empty reducer with index 0
    TreeSet<ImmutableBytesWritable> sorted =
      new TreeSet<ImmutableBytesWritable>(startKeys);

    ImmutableBytesWritable first = sorted.first();
    if (!first.equals(HConstants.EMPTY_BYTE_ARRAY)) {
      throw new IllegalArgumentException(
          "First region of table should have empty start key. Instead has: "
          + Bytes.toStringBinary(first.get()));
    }
    sorted.remove(first);

    // Write the actual file
    FileSystem fs = partitionsPath.getFileSystem(conf);
    SequenceFile.Writer writer = SequenceFile.createWriter(fs,
        conf, partitionsPath, ImmutableBytesWritable.class, NullWritable.class);

    try {
      for (ImmutableBytesWritable startKey : sorted) {
        writer.append(startKey, NullWritable.get());
      }
    } finally {
      writer.close();
    }
  }

  /**
   * Configure a MapReduce Job to perform an incremental load into the given
   * table. This
   * <ul>
   *   <li>Inspects the table to configure a total order partitioner</li>
   *   <li>Uploads the partitions file to the cluster and adds it to the DistributedCache</li>
   *   <li>Sets the number of reduce tasks to match the current number of regions</li>
   *   <li>Sets the output key/value class to match HFileOutputFormat's requirements</li>
   *   <li>Sets the reducer up to perform the appropriate sorting (either KeyValueSortReducer or
   *     PutSortReducer)</li>
   * </ul>
   * The user should be sure to set the map output value class to either KeyValue or Put before
   * running this function.
   */
  public static void configureIncrementalLoad(Job job, HTable table)
  throws IOException {
    Configuration conf = job.getConfiguration();

    job.setOutputKeyClass(ImmutableBytesWritable.class);
    job.setOutputValueClass(KeyValue.class);
    job.setOutputFormatClass(HFileOutputFormat.class);

    // Based on the configured map output class, set the correct reducer to properly
    // sort the incoming values.
    // TODO it would be nice to pick one or the other of these formats.
    if (KeyValue.class.equals(job.getMapOutputValueClass())) {
      job.setReducerClass(KeyValueSortReducer.class);
    } else if (Put.class.equals(job.getMapOutputValueClass())) {
      job.setReducerClass(PutSortReducer.class);
    } else if (Text.class.equals(job.getMapOutputValueClass())) {
      job.setReducerClass(TextSortReducer.class);
    } else {
      LOG.warn("Unknown map output value type:" + job.getMapOutputValueClass());
    }

    conf.setStrings("io.serializations", conf.get("io.serializations"),
        MutationSerialization.class.getName(), ResultSerialization.class.getName(),
        KeyValueSerialization.class.getName());

    // Use table's region boundaries for TOP split points.
    LOG.info("Looking up current regions for table " + Bytes.toString(table.getTableName()));
    List<ImmutableBytesWritable> startKeys = getRegionStartKeys(table);
    LOG.info("Configuring " + startKeys.size() + " reduce partitions " +
        "to match current region count");
    job.setNumReduceTasks(startKeys.size());

    configurePartitioner(job, startKeys);
    // Set compression algorithms based on column families
    configureCompression(table, conf);
    configureBloomType(table, conf);
    configureBlockSize(table, conf);

    TableMapReduceUtil.addDependencyJars(job);
    TableMapReduceUtil.initCredentials(job);
    LOG.info("Incremental table " + Bytes.toString(table.getTableName()) + " output configured.");
  }

  private static void configureBlockSize(HTable table, Configuration conf) throws IOException {
    StringBuilder blockSizeConfigValue = new StringBuilder();
    HTableDescriptor tableDescriptor = table.getTableDescriptor();
    if(tableDescriptor == null){
      // could happen with mock table instance
      return;
    }
    Collection<HColumnDescriptor> families = tableDescriptor.getFamilies();
    int i = 0;
    for (HColumnDescriptor familyDescriptor : families) {
      if (i++ > 0) {
        blockSizeConfigValue.append('&');
      }
      blockSizeConfigValue.append(URLEncoder.encode(
          familyDescriptor.getNameAsString(), "UTF-8"));
      blockSizeConfigValue.append('=');
      blockSizeConfigValue.append(URLEncoder.encode(
          String.valueOf(familyDescriptor.getBlocksize()), "UTF-8"));
    }
    // Get rid of the last ampersand
    conf.set(BLOCK_SIZE_CONF_KEY, blockSizeConfigValue.toString());
  }

  /**
   * Run inside the task to deserialize column family to compression algorithm
   * map from the
   * configuration.
   *
   * Package-private for unit tests only.
   *
   * @return a map from column family to the name of the configured compression
   *         algorithm
   */
  static Map<byte[], String> createFamilyCompressionMap(Configuration conf) {
    return createFamilyConfValueMap(conf, COMPRESSION_CONF_KEY);
  }

  private static Map<byte[], String> createFamilyBloomMap(Configuration conf) {
    return createFamilyConfValueMap(conf, BLOOM_TYPE_CONF_KEY);
  }

  private static Map<byte[], String> createFamilyBlockSizeMap(Configuration conf) {
    return createFamilyConfValueMap(conf, BLOCK_SIZE_CONF_KEY);
  }

  /**
   * Run inside the task to deserialize column family to given conf value map.
   *
   * @param conf
   * @param confName
   * @return a map of column family to the given configuration value
   */
  private static Map<byte[], String> createFamilyConfValueMap(Configuration conf, String confName) {
    Map<byte[], String> confValMap = new TreeMap<byte[], String>(Bytes.BYTES_COMPARATOR);
    String confVal = conf.get(confName, "");
    for (String familyConf : confVal.split("&")) {
      String[] familySplit = familyConf.split("=");
      if (familySplit.length != 2) {
        continue;
      }
      try {
        confValMap.put(URLDecoder.decode(familySplit[0], "UTF-8").getBytes(),
            URLDecoder.decode(familySplit[1], "UTF-8"));
      } catch (UnsupportedEncodingException e) {
        // will not happen with UTF-8 encoding
        throw new AssertionError(e);
      }
    }
    return confValMap;
  }

  /**
   * Configure <code>job</code> with a TotalOrderPartitioner, partitioning against
   * <code>splitPoints</code>. Cleans up the partitions file after job exists.
   */
  static void configurePartitioner(Job job, List<ImmutableBytesWritable> splitPoints)
      throws IOException {

    // create the partitions file
    FileSystem fs = FileSystem.get(job.getConfiguration());
    Path partitionsPath = new Path("/tmp", "partitions_" + UUID.randomUUID());
    fs.makeQualified(partitionsPath);
    fs.deleteOnExit(partitionsPath);
    writePartitions(job.getConfiguration(), partitionsPath, splitPoints);

    // configure job to use it
    job.setPartitionerClass(TotalOrderPartitioner.class);
    TotalOrderPartitioner.setPartitionFile(job.getConfiguration(), partitionsPath);
  }

  /**
   * Serialize column family to compression algorithm map to configuration.
   * Invoked while configuring the MR job for incremental load.
   *
   * Package-private for unit tests only.
   *
   * @throws IOException
   *           on failure to read column family descriptors
   */
  @edu.umd.cs.findbugs.annotations.SuppressWarnings(
      value="RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE")
  static void configureCompression(HTable table, Configuration conf) throws IOException {
    StringBuilder compressionConfigValue = new StringBuilder();
    HTableDescriptor tableDescriptor = table.getTableDescriptor();
    if(tableDescriptor == null){
      // could happen with mock table instance
      return;
    }
    Collection<HColumnDescriptor> families = tableDescriptor.getFamilies();
    int i = 0;
    for (HColumnDescriptor familyDescriptor : families) {
      if (i++ > 0) {
        compressionConfigValue.append('&');
      }
      compressionConfigValue.append(URLEncoder.encode(familyDescriptor.getNameAsString(), "UTF-8"));
      compressionConfigValue.append('=');
      compressionConfigValue.append(URLEncoder.encode(familyDescriptor.getCompression().getName(), "UTF-8"));
    }
    // Get rid of the last ampersand
    conf.set(COMPRESSION_CONF_KEY, compressionConfigValue.toString());
  }

  /**
   * Serialize column family to bloom type map to configuration.
   * Invoked while configuring the MR job for incremental load.
   *
   * @throws IOException
   *           on failure to read column family descriptors
   */
  static void configureBloomType(HTable table, Configuration conf) throws IOException {
    HTableDescriptor tableDescriptor = table.getTableDescriptor();
    if (tableDescriptor == null) {
      // could happen with mock table instance
      return;
    }
    StringBuilder bloomTypeConfigValue = new StringBuilder();
    Collection<HColumnDescriptor> families = tableDescriptor.getFamilies();
    int i = 0;
    for (HColumnDescriptor familyDescriptor : families) {
      if (i++ > 0) {
        bloomTypeConfigValue.append('&');
      }
      bloomTypeConfigValue.append(URLEncoder.encode(familyDescriptor.getNameAsString(), "UTF-8"));
      bloomTypeConfigValue.append('=');
      String bloomType = familyDescriptor.getBloomFilterType().toString();
      if (bloomType == null) {
        bloomType = HColumnDescriptor.DEFAULT_BLOOMFILTER;
      }
      bloomTypeConfigValue.append(URLEncoder.encode(bloomType, "UTF-8"));
    }
    conf.set(BLOOM_TYPE_CONF_KEY, bloomTypeConfigValue.toString());
  }
}
