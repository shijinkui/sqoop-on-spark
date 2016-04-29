package org.apache.sqoop.spark;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.sqoop.common.Direction;
import org.apache.sqoop.driver.Driver;
import org.apache.sqoop.model.*;
import org.apache.sqoop.repository.RepositoryManager;

public class SqoopJDBCHDFSJob {

  @SuppressWarnings("static-access")
  static Options createOptions() {

    Options options = new Options();

    options.addOption(OptionBuilder.withLongOpt("jdbcString")
        .withDescription("jdbc connection string").hasArg().isRequired()
        .withArgName("jdbcConnectionString").create());

    options.addOption(OptionBuilder.withLongOpt("u").withDescription("jdbc username").hasArg()
        .isRequired().withArgName("username").create());

    options.addOption(OptionBuilder.withLongOpt("p").withDescription("jdbc password").hasArg()
        .withArgName("password").create());

    options.addOption(OptionBuilder.withLongOpt("table").withDescription("jdbc table").hasArg()
        .isRequired().withArgName("table").create());

    options.addOption(OptionBuilder.withLongOpt("partitionCol")
        .withDescription("jdbc table parition column").hasArg().withArgName("pc").create());

    options.addOption(OptionBuilder.withLongOpt("outputDir").withDescription("hdfs output dir")
        .hasArg().isRequired().withArgName("outputDir").create());

    SqoopSparkJob.addCommonOptions(options);

    return options;
  }

  public static void main(String[] args) throws Exception {

    final SqoopSparkJob sparkJob = new SqoopSparkJob();
    CommandLine cArgs = SqoopSparkJob.parseArgs(createOptions(), args);
    SparkConf conf = sparkJob.init(cArgs);
    JavaSparkContext context = new JavaSparkContext(conf);

    MConnector fromConnector = RepositoryManager.getInstance().getRepository()
        .findConnector("generic-jdbc-connector");
    MConnector toConnector = RepositoryManager.getInstance().getRepository()
        .findConnector("hdfs-connector");

    MLinkConfig fromLinkConfig = fromConnector.getLinkConfig();
    MLinkConfig toLinkConfig = toConnector.getLinkConfig();

    MLink fromLink = new MLink(fromConnector.getPersistenceId(), fromLinkConfig);
    fromLink.setName("jdbcLink-" + System.currentTimeMillis());
    fromLink.getConnectorLinkConfig().getStringInput("linkConfig.jdbcDriver")
        .setValue("com.mysql.jdbc.Driver");

    fromLink.getConnectorLinkConfig().getStringInput("linkConfig.connectionString")
        .setValue(cArgs.getOptionValue("jdbcString"));
    fromLink.getConnectorLinkConfig().getStringInput("linkConfig.username")
        .setValue(cArgs.getOptionValue("u"));
    fromLink.getConnectorLinkConfig().getStringInput("linkConfig.password")
        .setValue(cArgs.getOptionValue("p"));
    RepositoryManager.getInstance().getRepository().createLink(fromLink);

    MLink toLink = new MLink(toConnector.getPersistenceId(), toLinkConfig);
    toLink.setName("hdfsLink-" + System.currentTimeMillis());
    toLink.getConnectorLinkConfig().getStringInput("linkConfig.confDir")
        .setValue(cArgs.getOptionValue("outputDir"));
    RepositoryManager.getInstance().getRepository().createLink(toLink);

    MFromConfig fromJobConfig = fromConnector.getFromConfig();
    MToConfig toJobConfig = toConnector.getToConfig();

    MJob sqoopJob = new MJob(fromConnector.getPersistenceId(), toConnector.getPersistenceId(),
        fromLink.getPersistenceId(), toLink.getPersistenceId(), fromJobConfig, toJobConfig, Driver
            .getInstance().getDriver().getDriverConfig());

    MConfigList fromConfig = sqoopJob.getJobConfig(Direction.FROM);
    fromConfig.getStringInput("fromJobConfig.tableName").setValue(cArgs.getOptionValue("table"));
    fromConfig.getStringInput("fromJobConfig.partitionColumn").setValue(
        cArgs.getOptionValue("paritionCol"));

    MToConfig toConfig = sqoopJob.getToJobConfig();
    toConfig.getStringInput("toJobConfig.outputDirectory").setValue(
        cArgs.getOptionValue("outputDir") + System.currentTimeMillis());
    MDriverConfig driverConfig = sqoopJob.getDriverConfig();
    if (cArgs.getOptionValue("numE") != null) {
      driverConfig.getIntegerInput("throttlingConfig.numExtractors").setValue(
          Integer.valueOf(cArgs.getOptionValue("numE")));
    }
    if (cArgs.getOptionValue("numL") != null) {

      driverConfig.getIntegerInput("throttlingConfig.numLoaders").setValue(
          Integer.valueOf(cArgs.getOptionValue("numL")));
    }
    RepositoryManager.getInstance().getRepository().createJob(sqoopJob);
    sparkJob.setJob(sqoopJob);
    sparkJob.execute(conf, context);
  }
}
