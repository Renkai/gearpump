/*
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
package io.gearpump.experiments.yarn.client

import java.io.{File,FileNotFoundException}

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.typesafe.config.ConfigFactory
import io.gearpump.cluster.AppMasterToMaster.MasterData
import io.gearpump.cluster.main.{ArgumentsParser, CLIOption, ParseResult}
import io.gearpump.experiments.yarn
import io.gearpump.experiments.yarn.{AppConfig, ContainerLaunchContext}
import io.gearpump.services._
import io.gearpump.util.{Constants, LogUtil}
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.yarn.api.ApplicationConstants
import org.apache.hadoop.yarn.api.ApplicationConstants.Environment
import org.apache.hadoop.yarn.api.records._
import org.apache.hadoop.yarn.client.api.YarnClient
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.hadoop.yarn.util.{Apps, Records}
import org.slf4j.Logger

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}


/**
Features for YARNClient
- [ ] Configuration file needs to indicate how many workers to allocate with possible locations
- [ ] Configuration file needs to specify minimum master ram, vmcore requirements
- [ ] Configuration file needs to specify minimum worker ram, vmcore requirements
- [ ] Configuration file should specify where in HDFS to place jars for appmaster and workers
- [ ] Client needs to use YARN cluster API to find best nodes to run Master(s)
- [ ] Client needs to use YARN cluster API to find best nodes to run Workers
 */

trait ClientAPI {
  def getConfiguration: AppConfig
  def getCommand: String
  def getYarnConf: YarnConfiguration
  def getAppEnv: Map[String, String]
  def getAMCapability: Resource
  def waitForTerminalState(appId: ApplicationId): ApplicationReport
  def start(): Boolean
  def submit(): Try[ApplicationId]
}

class Client(configuration: AppConfig, yarnConf: YarnConfiguration, yarnClient: YarnClient,
             containerLaunchContext: (String) => ContainerLaunchContext, fileSystem: FileSystem) extends ClientAPI {
  import Client._
  import yarn.Constants._

  private val LOG: Logger = LogUtil.getLogger(getClass)
  def getConfiguration = configuration
  def getYarnConf = yarnConf
  def getFs = FileSystem.get(getYarnConf)
  private def getEnv = getConfiguration.getEnv _

  private val version = configuration.getEnv("version")
  private val confOnYarn = getEnv(HDFS_ROOT) + "/conf/"
  private val restURI = s"api/$REST_VERSION"


  private[client] def getMemory(envVar: String): Int = {
    try {
      getEnv(envVar).trim.toInt
    } catch {
      case throwable: Throwable =>
        MEMORY_DEFAULT
    }
  }

  def getCommand = {
    val exe = getEnv(YARNAPPMASTER_COMMAND)
    val classPath = Array(
      s"pack/$version/conf",
      s"pack/$version/dashboard",
      s"pack/$version/lib/*",
      s"pack/$version/lib/daemon/*",
      s"pack/$version/lib/services/*",
      s"pack/$version/lib/yarn/*",
      "yarnConf"
    )
    val mainClass = getEnv(YARNAPPMASTER_MAIN)
    val logdir = ApplicationConstants.LOG_DIR_EXPANSION_VAR
    val command = s"$exe  -cp ${classPath.mkString(File.pathSeparator)}${File.pathSeparator}" +
      "$CLASSPATH" +
      s" -D${Constants.GEARPUMP_HOME}=${Environment.LOCAL_DIRS.$$()}/${Environment.CONTAINER_ID.$$()}/pack" +
      s" $mainClass" +
      s" -version $version" +
      " 1>" + logdir +"/" + ApplicationConstants.STDOUT +
      " 2>" + logdir +"/" + ApplicationConstants.STDERR

    LOG.info(s"command=$command")
    command
  }

  def getAppEnv: Map[String, String] = {
    val appMasterEnv = new java.util.HashMap[String,String]
    for (
      c <- getYarnConf.getStrings(
        YarnConfiguration.YARN_APPLICATION_CLASSPATH,
        YarnConfiguration.DEFAULT_YARN_APPLICATION_CLASSPATH.mkString(File.pathSeparator))
    ) {
      Apps.addToEnvironment(appMasterEnv, Environment.CLASSPATH.name(),
        c.trim(), File.pathSeparator)
    }
    Apps.addToEnvironment(appMasterEnv, Environment.CLASSPATH.name(),
      Environment.PWD.$()+File.separator+"*", File.pathSeparator)
    appMasterEnv.toMap
  }

  def uploadConfigToHDFS: Boolean = {
    val localConfigPath = getEnv("config")
    val configDir = new Path(confOnYarn)
    val hdfsConfigPath = new Path(confOnYarn + YARN_CONFIG)
    if(!getFs.exists(configDir.getParent)){
      getFs.mkdirs(configDir.getParent)
    }
    Try(getFs.copyFromLocalFile(false, true, new Path(localConfigPath), hdfsConfigPath)) match {
      case Success(a) =>
        LOG.info(s"$localConfigPath uploaded to $hdfsConfigPath")
        true
      case Failure(error) =>
        LOG.error(s"$localConfigPath could not be uploaded to $hdfsConfigPath ${error.getMessage}")
        false
    }
  }

  def getAMCapability: Resource = {
    val capability = Records.newRecord(classOf[Resource])
    capability.setMemory(getMemory(YARNAPPMASTER_MEMORY))
    capability.setVirtualCores(getEnv(YARNAPPMASTER_VCORES).toInt)
    capability
  }

  private def clusterResources: ClusterResources = {
    val nodes:Seq[NodeReport] = yarnClient.getNodeReports(NodeState.RUNNING)
    nodes.foldLeft(ClusterResources(0L, 0, Map.empty[String, Long]))((clusterResources, nodeReport) => {
      val resource = nodeReport.getCapability
      ClusterResources(clusterResources.totalFreeMemory+resource.getMemory,
        clusterResources.totalContainers+nodeReport.getNumContainers,
        clusterResources.nodeManagersFreeMemory+(nodeReport.getNodeId.getHost->resource.getMemory))
    })
  }

  private def getApplicationReport(appId: ApplicationId): (ApplicationReport, YarnApplicationState) = {
    val appReport = yarnClient.getApplicationReport(appId)
    val appState = appReport.getYarnApplicationState
    (appReport, appState)
  }

  def waitForTerminalState(appId: ApplicationId): ApplicationReport = {
    var appReport = yarnClient.getApplicationReport(appId)
    var appState = appReport.getYarnApplicationState
    var terminalState = false

    while (!terminalState) {
      appState match {
        case YarnApplicationState.FINISHED =>
          LOG.info(s"Application $appId finished with state $appState at ${appReport.getFinishTime}")
          terminalState = true
        case YarnApplicationState.KILLED =>
          LOG.info(s"Application $appId finished with state $appState at ${appReport.getFinishTime}")
          terminalState = true
        case YarnApplicationState.FAILED =>
          LOG.info(s"Application $appId finished with state $appState at ${appReport.getFinishTime}")
          terminalState = true
        case YarnApplicationState.SUBMITTED =>
          val (ar, as) = getApplicationReport(appId)
          appReport = ar
          appState = as
        case YarnApplicationState.ACCEPTED =>
          val (ar, as) = getApplicationReport(appId)
          appReport = ar
          appState = as
        case YarnApplicationState.RUNNING =>
          LOG.info(s"Application $appId is $appState trackingURL=${appReport.getTrackingUrl}")
          terminalState = true
        case unknown: YarnApplicationState =>
          LOG.info(s"Application $appId is $appState")
          val (ar, as) = getApplicationReport(appId)
          appReport = ar
          appState = as
      }
      Thread.sleep(1000)
    }
    appReport
  }

  def logMasters(report: ApplicationReport): Unit = {
    import upickle.default.read
    implicit val system = ActorSystem("httpclient")
    implicit val materializer = ActorMaterializer()
    import concurrent.ExecutionContext.Implicits.global
    LOG.info(s"trackingURL=${report.getTrackingUrl}")
    val trackingURL = new java.net.URL(report.getTrackingUrl)
    system.scheduler.scheduleOnce(Duration(5, TimeUnit.SECONDS)) {
      LOG.info(s"host=${trackingURL.getHost} port=${trackingURL.getPort} uri=/proxy/${report.getApplicationId}/$restURI/master")
      lazy val trackingURLConnectionFlow: Flow[HttpRequest, HttpResponse, Any] =
        Http().outgoingConnection(trackingURL.getHost, trackingURL.getPort)
      val response = Source.single(RequestBuilding.Get(s"/proxy/${report.getApplicationId}/$restURI/master")).via(trackingURLConnectionFlow).runWith(Sink.head)
      response.foreach(response => {
        response.status match {
          case StatusCodes.OK =>
            LOG.info(s"status code=${StatusCodes.OK.intValue}")
            Unmarshal(response.entity).to[String].onComplete(result => {
              result match {
                case Success(data) =>
                  val masterData = read[MasterData](data)
                  LOG.info(s"leader=${masterData.masterDescription.leader._1}:${masterData.masterDescription.leader._2}")
                  val cluster=masterData.masterDescription.cluster.map(p=>{p._1+":"+p._2}).mkString(",")
                  LOG.info("masters=" + cluster)
                case Failure(throwable) =>
                  LOG.error("Failed to fetch masters", throwable)
              }
              system.shutdown()
            })
          case value =>
            LOG.error(s"Bad status code=${value.intValue}")
            system.shutdown()
        }
      })
    }
  }

  def start(): Boolean = {
    Try({
      yarnClient.init(yarnConf)
      yarnClient.start()
      true
    }) match {
      case Success(success) =>
        success
      case Failure(throwable) =>
        LOG.error("Failed to start", throwable)
        false
    }
  }

  def submit(): Try[ApplicationId] = {
    Try({
      val appContext = yarnClient.createApplication.getApplicationSubmissionContext
      appContext.setApplicationName(getEnv(YARNAPPMASTER_NAME))

      val containerContext = containerLaunchContext(getCommand)
      appContext.setAMContainerSpec(containerContext)
      appContext.setResource(getAMCapability)
      appContext.setQueue(getEnv(YARNAPPMASTER_QUEUE))

      yarnClient.submitApplication(appContext)
      appContext.getApplicationId
    })
  }

  private def deploy(): Unit = {
    LOG.info("Starting AM")
    Try({
      uploadConfigToHDFS && start() match {
        case true =>
          submit() match {
            case Success(appId) =>
              val report = waitForTerminalState(appId)
              logMasters(report)
            case Failure(throwable) =>
              LOG.error("Failed to submit", throwable)
          }
        case false =>
      }
    }).failed.map(throwable => {
      LOG.error("Failed to deploy", throwable)
    })
  }

}

object Client extends App with ArgumentsParser {

  case class ClusterResources(totalFreeMemory: Long, totalContainers: Int, nodeManagersFreeMemory: Map[String, Long])

  override val options: Array[(String, CLIOption[Any])] = Array(
    "jars" -> CLIOption[String]("<AppMaster jar directory>", required = false),
    "version" -> CLIOption[String]("<gearpump version, we allow multiple gearpump version to co-exist on yarn>", required = true),
    "main" -> CLIOption[String]("<AppMaster main class>", required = false),
    "config" ->CLIOption[String]("<Config file path>", required = true),
    "verbose" -> CLIOption("<print verbose log on console>", required = false, defaultValue = Some(false))
  )

  val parseResult: ParseResult = parse(args)
  val config = ConfigFactory.parseFile(new File(parseResult.getString("config")))

  val verbose = parseResult.getBoolean("verbose")
  if (verbose) {
    LogUtil.verboseLogToConsole
  }

  def apply(appConfig: AppConfig  = new AppConfig(parseResult, config), conf: YarnConfiguration  = new YarnConfiguration,
            client: YarnClient  = YarnClient.createYarnClient) = {
    new Client(appConfig, conf, client, ContainerLaunchContext(conf, appConfig), FileSystem.get(conf)).deploy()
  }

  def apply(appConfig: AppConfig, conf: YarnConfiguration, client: YarnClient,
            containerLaunchContext: (String) => ContainerLaunchContext, fileSystem: FileSystem) = {
    new Client(appConfig, conf, client, containerLaunchContext, fileSystem).deploy()
  }

  Try(apply()).recover{
    case ex:FileNotFoundException =>
      Console.err.println(s"${ex.getMessage}\n" +
        s"try to check if your gearpump version is right " +
        s"or HDFS was correctly configured.")
    case ex => help; throw ex
  }
}
