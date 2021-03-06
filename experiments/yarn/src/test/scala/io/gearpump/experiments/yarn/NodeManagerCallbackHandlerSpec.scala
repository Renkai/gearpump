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

package io.gearpump.experiments.yarn

import java.nio.ByteBuffer

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestKit, TestProbe}
import io.gearpump.experiments.yarn.master.{StopSystemAfterAll, AmActorProtocol}
import AmActorProtocol.ContainerStarted
import org.apache.hadoop.yarn.api.records.{ApplicationAttemptId, ApplicationId, ContainerId}
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers._

import scala.collection.JavaConverters._

class NodeManagerCallbackHandlerSpec extends TestKit(ActorSystem("testsystem"))
with FlatSpecLike
with StopSystemAfterAll
{
  val createNodeManagerCallbackHandler: (ActorRef) => NodeManagerCallbackHandler = (am) => {
    new NodeManagerCallbackHandler(am)
  }

  "A NodeManagerCallbackHandler" should "send ContainerStarted to AmMaster when onContainersStarted is called on it" in {
    val probe = TestProbe()
    val handler = createNodeManagerCallbackHandler(probe.ref)
    val containerId = ContainerId.newInstance(ApplicationAttemptId.newInstance(ApplicationId.newInstance(1, 1), 1), 1)
    handler.onContainerStarted(containerId, Map.empty[String, ByteBuffer].asJava)
    probe.expectMsg(ContainerStarted(containerId))
  }

  "A NodeManagerCallbackHandlerFactory" should "create new NodeManagerCallbackHandler object when newInstance is called on it" in {
    createNodeManagerCallbackHandler(testActor) should not be theSameInstanceAs(createNodeManagerCallbackHandler(testActor))
  }
}
