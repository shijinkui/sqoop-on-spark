package org.apache.sqoop.submission.spark;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.sqoop.common.Direction;
import org.apache.sqoop.common.SqoopException;
import org.apache.sqoop.connector.idf.IntermediateDataFormat;
import org.apache.sqoop.core.JobConstants;
import org.apache.sqoop.driver.JobRequest;
import org.apache.sqoop.error.code.SparkExecutionError;
import org.apache.sqoop.job.SparkPrefixContext;
import org.apache.sqoop.job.etl.Partition;
import org.apache.sqoop.job.etl.Partitioner;
import org.apache.sqoop.job.etl.PartitionerContext;
import org.apache.sqoop.schema.Schema;
import org.apache.sqoop.utils.ClassUtils;

import java.util.List;

public class SqoopSparkDriver {

  public static final String DEFAULT_EXTRACTORS = "defaultExtractors";
  public static final String NUM_LOADERS = "numLoaders";

  private static final Log LOG = LogFactory.getLog(SqoopSparkDriver.class.getName());

  public static void execute(JobRequest request, SparkConf conf, JavaSparkContext sc) throws Exception {

    LOG.info("Executing sqoop spark job");

    long totalTime = System.currentTimeMillis();
    SparkPrefixContext driverContext = new SparkPrefixContext(request.getConf(),
        JobConstants.PREFIX_CONNECTOR_DRIVER_CONTEXT);

    int defaultExtractors = conf.getInt(DEFAULT_EXTRACTORS, 10);
    long numExtractors = (driverContext.getLong(JobConstants.JOB_ETL_EXTRACTOR_NUM,
        defaultExtractors));
    int numLoaders = conf.getInt(NUM_LOADERS, 1);

    List<Partition> sp = getPartitions(request, numExtractors);
    System.out.println(">>> Partition size:" + sp.size());

    JavaRDD<Partition> rdd = sc.parallelize(sp, sp.size());
    JavaRDD<List<IntermediateDataFormat<?>>> mapRDD = rdd.map(new SqoopExtractFunction(
        request));
    // if max loaders or num loaders is given reparition to adjust the max
    // loader parallelism
    if (numLoaders != numExtractors) {
      JavaRDD<List<IntermediateDataFormat<?>>> reParitionedRDD = mapRDD.repartition(numLoaders);
      System.out.println(">>> RePartition RDD size:" + reParitionedRDD.partitions().size());
      reParitionedRDD.mapPartitions(new SqoopLoadFunction(request)).collect();
    } else {
      System.out.println(">>> Mapped RDD size:" + mapRDD.partitions().size());
      mapRDD.mapPartitions(new SqoopLoadFunction(request)).collect();
    }

    System.out.println(">>> TOTAL time ms:" + (System.currentTimeMillis() - totalTime));

    LOG.info("Done EL in sqoop spark job, next call destroy apis");

  }

  @SuppressWarnings("unchecked")
  private static List<Partition> getPartitions(JobRequest request, long maxPartitions) {
    String partitionerName = request.getDriverContext().getString(JobConstants.JOB_ETL_PARTITIONER);
    @SuppressWarnings("rawtypes")
    Partitioner partitioner = (Partitioner) ClassUtils.instantiate(partitionerName);
    SparkPrefixContext context = new SparkPrefixContext(request.getConf(),
        JobConstants.PREFIX_CONNECTOR_FROM_CONTEXT);

    Object fromLinkConfig = request.getConnectorLinkConfig(Direction.FROM);
    Object fromJobConfig = request.getJobConfig(Direction.FROM);
    Schema fromSchema = request.getJobSubmission().getFromSchema();

    System.out.println(">>> Configured Partition size:" + maxPartitions);

    PartitionerContext partitionerContext = new PartitionerContext(context, maxPartitions,
        fromSchema);

    List<Partition> partitions = partitioner.getPartitions(partitionerContext, fromLinkConfig,
        fromJobConfig);

    if (partitions.size() > maxPartitions) {
      throw new SqoopException(SparkExecutionError.SPARK_EXEC_0000, String.format(
          "Got %d, max was %d", partitions.size(), maxPartitions));
    }
    return partitions;
  }
}
