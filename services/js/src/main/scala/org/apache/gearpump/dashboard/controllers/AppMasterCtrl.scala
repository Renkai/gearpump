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
package org.apache.gearpump.dashboard.controllers

import com.greencatsoft.angularjs.core.{Compile, Route, RouteProvider, Scope}
import com.greencatsoft.angularjs.{AbstractController, Config, injectable}
import org.apache.gearpump.dashboard.services.{ColorSet, DagOptions, RestApiService}
import org.apache.gearpump.shared.Messages._
import org.scalajs.dom.raw.HTMLElement
import upickle.Js

import scala.collection.mutable
import scala.scalajs.concurrent.JSExecutionContext.Implicits.runNow
import scala.scalajs.js
import scala.scalajs.js.UndefOr
import scala.scalajs.js.annotation.{JSExport, JSExportAll, JSName}
import scala.util.{Failure, Success, Try}

@JSExportAll
case class Tab(var heading: String, templateUrl: String, controller: String, var selected: Boolean = false)

@JSExportAll
case class TabIndex(tabIndex: Int, reload: Boolean)

@JSExportAll
case class GraphEdge(source: Int, target: Int, `type`: String)

@JSExportAll
case class SummaryEntry(name: String, value: Any)

@JSExportAll
case class Options(height: String)

@JSExportAll
case class Chart(title: String, options: Options, var data: js.Array[Double])

@JSExportAll
case class AggregatedProcessedMessages(total: Int, rate: Double)

@JSExportAll
case class ProcessorConnections(inputs: Int, outputs: Int)

@JSExportAll
case class ProcessedMessages(total: Array[Int], rate: Array[Double])

@JSExportAll
case class ProcessorsData(processors: Map[ProcessorId, ProcessorDescription], hierarchyLevels: Map[Int, Int], var weights: Map[Int, Double])

@JSExportAll
case class EdgesData(edges: Map[String, GraphEdge], var bandwidths: Map[String, Double])

@JSName("vis.DataSet")
class DataSet extends js.Object {
  val length: Int = js.native
  def add(data: js.Array[js.Object], senderId: String): js.Array[String] = js.native
  def get(id:Int): js.Any = js.native
  def get(id:String): js.Any  = js.native
  def update(nodes: js.Array[_<:Any]): js.Array[Int] = js.native
}

/*
data
Object {nodes: o, edges: o}edges: o_data: Object
  0_1: Object
    arrows: Object
      to: Object
        scaleFactor: 0.2__proto__: Object__proto__: Object
    color: Object
      opacity: 0.8__proto__: Object
    from: 0
    hoverWidth: 0
    id: "0_1"
    selectionWidth: 0
    to: 1
    width: 3.6__proto__: Object
  0_2: Object
    arrows: Object
      to: Object
        scaleFactor: 0.2__proto__: Object__proto__: Object
    color: Object
      opacity: 0.8__proto__: Object
    from: 0
    hoverWidth: 0
    id: "0_2"
    selectionWidth: 0
    to: 2
    width: 3.6__proto__: Object
  0_3: Object
  0_4: Object
  0_5: Object
  0_6: Object
  3_4: Object4_7: Object5_4: Object5_7: Object5_8: Object8_7: Object9_10: Object9_11: Object11_7: Object__proto__: Object_fieldId: "id"_options: Object__proto__: Object_subscribers: Object_type: Object__proto__: Objectlength: 15__proto__: onodes: o__proto__: Object
 */
@JSExportAll
case class DagData(nodes: DataSet, edges: DataSet)

@JSExportAll
case class DoubleClickEvent(doubleClick: js.Function1[DagData, Unit])

@JSExportAll
case class VisGraph(options: DagOptions, data: DagData, events: DoubleClickEvent)

trait VisNode extends js.Object {
  val id: Int = js.native
  val label: String = js.native
  val level: Int = js.native
  val size: Double = js.native
}

trait EdgeScale extends js.Object {
  val scaleFactor: Double = js.native
}

trait EdgeArrows extends js.Object {
  val to: EdgeScale = js.native
}

trait EdgeColor extends js.Object {
  val opacity: Double = js.native
  val color: String = js.native
}

trait VisEdge extends js.Object {
  val id: String = js.native
  val from: Int = js.native
  val to: Int = js.native
  val width: Double = js.native
  val hoverWidth: Int = js.native
  val selectionWidth: Int = js.native
  val arrows: EdgeArrows = js.native
  val color: EdgeColor = js.native
}

@JSExport
@injectable("AppMasterConfig")
class AppMasterConfig(routeProvider: RouteProvider) extends Config {
  println("AppMasterConfig")
  routeProvider.when ("/apps/app/:id", Route("views/apps/app/appmaster.html", "Application", "AppMasterCtrl") )
}

@JSExport
class StreamingDag(data: StreamingAppMasterDataDetail) {

  import StreamingDag._

  val appId = data.appId
  val processors = data.processors
  val processorHierarchyLevels = data.processorLevels
  val edges = data.dag.edges.map(tuple => {
    val (node1, edge, node2) = tuple
    (node1 + "_" + node2) -> GraphEdge(node1, node2, edge)
  }).toMap
  val executors = data.executors
  var meter = Map.empty[String, Map[String,MetricInfo[Meter]]]
  var histogram = Map.empty[String, Map[String,MetricInfo[Histogram]]]
  val d3 = js.Dynamic.global.d3

  type MetricMap =  Map[String, Map[String, MetricInfo[_ <: MetricType]]]

  @JSExport
  def hasMetrics: Boolean = {
    meter.nonEmpty && histogram.nonEmpty
  }

  @JSExport
  def updateMetrics(data: HistoryMetrics): Boolean = {
    Try({
      data.metrics.foreach(data => {
        data.value.typeName match {
          case MeterType =>
            val metric = upickle.read[Meter](data.value.json)
            val (appId, processorId, taskId, name) = decodeName(metric.name)
            val key = s"${processorId}_$taskId"
            val metricInfo = MetricInfo[Meter](appId, processorId, taskId, metric, data.value)
            meter.contains(name) match {
              case true =>
                var map = meter(name)
                map += key -> metricInfo
                meter += name -> map
              case false =>
                meter += name -> Map(key -> metricInfo)
            }
          case HistogramType =>
            val metric = upickle.read[Histogram](data.value.json)
            val (appId, processorId, taskId, name) = decodeName(metric.name)
            val key = processorId + "_" + taskId
            val metricInfo = MetricInfo[Histogram](appId, processorId, taskId, metric, data.value)
            histogram.contains(name) match {
              case true =>
                var map = histogram(name)
                map += key -> metricInfo
                histogram += name -> map
              case false =>
                histogram += name -> Map(key -> metricInfo)
            }
          case _ =>
            println(s"unknown metric type ${data.value.typeName}")
        }
      })
      true
    }) match {
      case Success(value) =>
        value
      case Failure(throwable) =>
        println(s"cound not update metrics ${throwable.getMessage}")
        false
    }
  }

  @JSExport
  def getAggregatedMetrics(metricMap: MetricMap, metricCategory: String, metricType: String, processorId: Option[Int]): Array[Double] = {
      val ids = processorId match {
        case Some(id) =>
          Array(id)
        case None =>
          processors.keys.toArray
      }
      ids.flatMap(pid => {
        getProcessorMetrics(pid, metricMap, metricCategory, metricType)
      })
  }

  @JSExport
  def getNumOfTasks: Int = {
    processors.valuesIterator.map(processorDescription => {
      processorDescription.parallelism
    }).sum
  }

  @JSExport
  def getReceivedMessages(processorId: UndefOr[Int]): AggregatedProcessedMessages = {
    processorId.isDefined match {
      case true =>
        val id = processorId.asInstanceOf[Int]
        getProcessedMessagesByProcessor(meter, "receiveThroughput", id, aggregated=true).left.getOrElse(AggregatedProcessedMessages(total=0,rate=0))
      case false =>
        getProcessedMessages(meter, "receiveThroughput", getProcessorIdsByType("sink"))
    }
  }

  @JSExport
  def getProcessorIdsByType(typeValue: String): Array[Int] = {
    val ids = mutable.MutableList.empty[Int]
    processors.keys.foreach(processorId => {
      val conn = calculateProcessorConnections(processorId)
      typeValue match {
        case "source" =>
          conn.inputs == 0 && conn.outputs > 0 match {
            case true =>
              ids += processorId
            case false =>
          }
        case "sink" =>
          conn.inputs > 0 && conn.outputs == 0 match {
            case true =>
              ids += processorId
            case false =>
          }
      }
    })
    ids.toArray
  }

  @JSExport
  def calculateProcessorConnections(processorId: Int): ProcessorConnections = {
    val sum = edges.values.map(edge => {
      (edge.source == processorId match {
        case true =>
          1
        case false =>
          0
      },
      edge.target == processorId match {
        case true =>
          1
        case false =>
          0
      })
    }).reduce((a1, a2) => {
      (a1._1 + a2._1, a1._2 + a2._2)
    })
    ProcessorConnections(inputs=sum._2,outputs=sum._1)
  }

  @JSExport
  def getProcessedMessages(metricMap: MetricMap, metricCategory: String, processorIds: Array[Int]): AggregatedProcessedMessages = {
    val sum = processorIds.map(processorId => {
      val aggregated = getProcessedMessagesByProcessor(metricMap, metricCategory, processorId, aggregated=true).left.get
      (aggregated.total, aggregated.rate)
    }).reduce((a1,a2) => {
      (a1._1 + a2._1, a1._2 + a2._2)
    })
    AggregatedProcessedMessages(total=sum._1,rate=sum._2)
  }

  @JSExport
  def getProcessedMessagesByProcessor(metricMap: MetricMap, metricCategory: String, processorId: Int, aggregated: Boolean):
  Either[AggregatedProcessedMessages,ProcessedMessages] = {
    val taskCountArray = getAggregatedMetrics(metricMap, metricCategory, "count", Some(processorId)).map(_.toInt)
    val taskRateArray = getAggregatedMetrics(metricMap, metricCategory, "meanRate", Some(processorId))
    aggregated match {
      case true =>
        Left(AggregatedProcessedMessages(total=taskCountArray.sum, rate=taskRateArray.sum))
      case false =>
        Right(ProcessedMessages(total=taskCountArray, rate=taskRateArray))
    }
  }

  @JSExport
  def getSentMessages(processorId: UndefOr[Int]): AggregatedProcessedMessages = {
    processorId.isDefined match {
      case true =>
        val id = processorId.get
        getProcessedMessagesByProcessor(meter, "sendThroughput", id, aggregated = true).left.get
      case false =>
        getProcessedMessages(meter, "sendThroughput", getProcessorIdsByType("source"))
    }
  }

  @JSExport
  def getProcessingTime(processorId: UndefOr[Int]): Array[Double] = {
    processorId.isDefined match {
      case true =>
        val id = processorId.get
        getAggregatedMetrics(histogram, "processTime", "mean", Some(id))
      case false =>
        val array = getAggregatedMetrics(histogram, "processTime", "mean", None)
        Array(array.sum/array.length)
    }
  }

  @JSExport
  def getReceiveLatency(processorId: UndefOr[Int]): Array[Double] = {
    processorId.isDefined match {
      case true =>
        val id = processorId.get
        getAggregatedMetrics(histogram, "receiveLatency", "mean", Some(id))
      case false =>
        val array = getAggregatedMetrics(histogram, "receiveLatency", "mean", None)
        Array(array.sum/array.length)
    }
  }

  @JSExport
  def getProcessorsData(): ProcessorsData = {
    val weights = processors.keys.map(processorId => {
      processorId -> calculateProcessorWeight(processorId)
    }).toMap[Int, Double]
    ProcessorsData(processors, processorHierarchyLevels, weights)
  }

  @JSExport
  def calculateProcessorWeight(processorId: Int): Double = {
    Array(getProcessorMetrics(processorId, meter, "sendThroughput", "meanRate").sum,
    getProcessorMetrics(processorId, meter, "receiveThroughput", "meanRate").sum).max
  }

  def toMap(metricInfo: MetricInfo[_ <: MetricType]): Map[String, Js.Value] = {
    Try({
      metricInfo.typeInfo.typeName match {
        case MeterType =>
          upickle.writeJs[Meter](metricInfo.metric.asInstanceOf[Meter]).asInstanceOf[Js.Arr].value(1).asInstanceOf[Js.Obj].value.toMap
        case HistogramType =>
          upickle.writeJs[Histogram](metricInfo.metric.asInstanceOf[Histogram]).asInstanceOf[Js.Arr].value(1).asInstanceOf[Js.Obj].value.toMap
        case _ =>
          println("unknown type")
          Map.empty[String,Js.Value]
      }
    }) match {
      case Success(obj) =>
        obj
      case Failure(throwable) =>
        println("failed to convert to dictionary")
        Map.empty[String,Js.Value]
    }
  }

  @JSExport
  def getProcessorMetrics(processorId: Int, metricMap: Map[String,Map[String,MetricInfo[_ <: MetricType]]], metricCategory: String, metricType: String): Array[Double] = {
    val tasks = processors(processorId).parallelism
    var values = mutable.MutableList.empty[Double]
    (0 until tasks).foreach(task => {
      val name = s"${processorId}_$task"
      metricMap(metricCategory).contains(name) match {
        case true =>
          val metricInfo = metricMap(metricCategory)(name)
          val fMap = toMap(metricInfo)
          fMap.contains(metricType) match {
            case true =>
              val v = fMap(metricType).value
              val obj = v.toString
              val value = obj.toDouble
              //println(s"found $metricCategory[$name][$metricType] = $value")
              values += value
            case false =>
              println(s"could not find $metricCategory[$name][$metricType]")
          }
        case false =>
          println(s"could not find $metricCategory[$name]")
      }
    })
    values.toArray
  }

  @JSExport
  def getEdgesData(): EdgesData = {
    val bandwidths = edges.keys.map(edgeId => {
      edgeId -> calculateEdgeBandwidth(edgeId)
    }).toMap
    EdgesData(edges,bandwidths)
  }

  @JSExport
  def calculateEdgeBandwidth(edgeId: String): Double = {
    val digits = edgeId.split("_")
    val sourceId = digits(0).toInt
    val targetId = digits(1).toInt
    val sourceOutputs = calculateProcessorConnections(sourceId).outputs
    val targetInputs = calculateProcessorConnections(targetId).inputs
    val sourceSendThroughput = getProcessorMetrics(sourceId, meter, "sendThroughput", "meanRate").sum
    val targetReceiveThoughput = getProcessorMetrics(targetId, meter, "receiveThroughput", "meanRate").sum
    Array(sourceOutputs match {
      case 0 =>
        0.0
      case n =>
        sourceSendThroughput / sourceOutputs
    },
    targetInputs match {
      case 0 =>
        0.0
      case n =>
        targetReceiveThoughput / targetInputs
    }).min
  }

  @JSExport
  def hierarchyDepth(): Int = {
    processorHierarchyLevels.values.max
  }
  
  import js.JSConverters._

  def decodeName(name: String): (Int, Int, Int, String) = {
    val parts: js.Array[String] = name.split("\\.").toJSArray
    val appId = parts(0).substring("app".length).toInt
    val processorId = parts(1).substring("processor".length).toInt
    val taskId = parts(2).substring("task".length).toInt
    val metric = parts(3)
    (appId, processorId, taskId, metric)
  }
}

@JSExport
object StreamingDag {
  val MeterType = "org.apache.gearpump.shared.Messages.Meter" //"org.apache.gearpump.metrics.Metrics.Meter"
  val HistogramType = "org.apache.gearpump.shared.Messages.Histogram" //"org.apache.gearpump.metrics.Metrics.Histogram"

  def apply(data: StreamingAppMasterDataDetail) = new StreamingDag(data)
}


trait AppMasterScope extends Scope {
  var activeProcessorId: Int = js.native
  var app: StreamingAppMasterDataDetail = js.native
  var charts: js.Array[Chart] = js.native
  var streamingDag: StreamingDag = js.native
  var summary: js.Array[SummaryEntry] = js.native
  var switchToTabIndex: TabIndex = js.native
  var tabs: js.Array[Tab] = js.native
  var visgraph: VisGraph = js.native

  var receivedMessages: AggregatedProcessedMessages = js.native
  var sentMessages: AggregatedProcessedMessages = js.native
  var processingTime: Array[Double] = js.native
  var receiveLatency: Array[Double] = js.native

  var load: js.Function2[HTMLElement, Tab,Unit] = js.native
  var lastPart: js.Function1[String, String] = js.native
  var selectTab: js.Function1[Tab,Unit] = js.native
  var switchToTaskTab: js.Function1[Int,Unit] = js.native
  var updateMetricsCounter: js.Function0[Unit] = js.native
  var updateVisGraphEdges: js.Function0[Unit] = js.native
  var updateVisGraphNodes: js.Function0[Unit] = js.native
}


@JSExport
@injectable("AppMasterCtrl")
class AppMasterCtrl(scope: AppMasterScope, restApi: RestApiService, compile: Compile)
  extends AbstractController[AppMasterScope](scope) {

  println("AppMasterCtrl")

  def load(elem: HTMLElement, tab: Tab): Unit = {
    restApi.getUrl(tab.templateUrl) onComplete {
      case Success(html) =>
        val templateScope = scope.$new(true).asInstanceOf[AppMasterScope]
        elem.innerHTML = html
        //tabSetCtrl(templateScope)
        elem.setAttribute("ngController", tab.controller)
        compile(elem.innerHTML, null, 0)(templateScope, null)
      case Failure(t) =>
        println(s"Failed to get workers ${t.getMessage}")
    }
  }

  def lastPart(name: String): String = {
    name.split("\\.").last
  }

  def selectTab(tab: Tab): Unit = {
    tab.selected = true
  }

  def switchToTaskTab(processorId: Int): Unit = {
    scope.activeProcessorId = processorId
    scope.switchToTabIndex = TabIndex(tabIndex=2, reload=true)
  }

  scope.app = StreamingAppMasterDataDetail(appId=1)
  scope.tabs = js.Array(
   Tab(heading="Status", templateUrl="views/apps/app/appstatus.html", controller="AppStatusCtrl"),
   Tab(heading="DAG", templateUrl="views/apps/app/appdag.html", controller="AppDagCtrl"),
   Tab(heading="Processor", templateUrl="views/apps/app/appprocessor.html", controller="AppProcessorCtrl"),
   Tab(heading="Metrics", templateUrl="views/apps/app/appmetrics.html", controller="AppMetricsCtrl")
  )

  scope.switchToTaskTab = switchToTaskTab _
  scope.selectTab = selectTab _
  scope.load = load _
  scope.lastPart = lastPart _

  restApi.subscribe("/appmaster/" + scope.app.appId + "?detail=true") onComplete {
    case Success(value) =>
      val data = upickle.read[StreamingAppMasterDataDetail](value)
      scope.app = data
      scope.streamingDag = StreamingDag(scope.app)
      val hasMetrics = scope.streamingDag.hasMetrics
      hasMetrics match {
        case false =>
          val url = s"/metrics/app/${scope.app.appId}/app${scope.app.appId}?readLatest=true"
          restApi.subscribe(url) onComplete {
            case Success(rdata) =>
              val value = upickle.read[HistoryMetrics](rdata)
              Option(value).foreach(scope.streamingDag.updateMetrics(_))
            case Failure(t) =>
              println(s"failed ${t.getMessage}")
          }
        case true =>
      }
    case Failure(t) =>
      println(s"Failed to get workers ${t.getMessage}")
  }

}

