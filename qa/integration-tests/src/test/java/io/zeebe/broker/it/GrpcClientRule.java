/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.broker.it;

import static io.zeebe.test.util.TestUtil.waitUntil;

import io.zeebe.broker.it.clustering.ClusteringRule;
import io.zeebe.broker.test.EmbeddedBrokerRule;
import io.zeebe.client.ZeebeClient;
import io.zeebe.client.ZeebeClientBuilder;
import io.zeebe.client.api.clients.JobClient;
import io.zeebe.client.api.clients.WorkflowClient;
import io.zeebe.client.api.commands.PartitionInfo;
import io.zeebe.client.api.commands.Topology;
import io.zeebe.client.impl.ZeebeClientBuilderImpl;
import io.zeebe.protocol.intent.DeploymentIntent;
import io.zeebe.test.util.record.RecordingExporter;
import io.zeebe.util.sched.clock.ControlledActorClock;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.rules.ExternalResource;

public class GrpcClientRule extends ExternalResource {

  private final Consumer<ZeebeClientBuilder> configurator;

  protected ZeebeClient client;
  private final ControlledActorClock actorClock = new ControlledActorClock();

  public GrpcClientRule(final EmbeddedBrokerRule brokerRule) {
    this(brokerRule, config -> {});
  }

  public GrpcClientRule(
      final EmbeddedBrokerRule brokerRule, final Consumer<ZeebeClientBuilder> configurator) {
    this(
        config -> {
          config.brokerContactPoint(brokerRule.getGatewayAddress().toString());
          configurator.accept(config);
        });
  }

  public GrpcClientRule(final ClusteringRule clusteringRule) {
    this(config -> config.brokerContactPoint(clusteringRule.getClientAddress().toString()));
  }

  private GrpcClientRule(final Consumer<ZeebeClientBuilder> configurator) {
    this.configurator = configurator;
  }

  @Override
  protected void before() {
    final ZeebeClientBuilderImpl builder = (ZeebeClientBuilderImpl) ZeebeClient.newClientBuilder();
    configurator.accept(builder);
    client = builder.setActorClock(actorClock).build();
  }

  @Override
  protected void after() {
    client.close();
    client = null;
  }

  public ZeebeClient getClient() {
    return client;
  }

  public void waitUntilDeploymentIsDone(final long key) {
    waitUntil(
        () ->
            RecordingExporter.deploymentRecordStream(DeploymentIntent.DISTRIBUTED)
                .withKey(key)
                .exists());
  }

  public List<Integer> getPartitions() {
    final Topology topology = client.newTopologyRequest().send().join();

    return topology
        .getBrokers()
        .stream()
        .flatMap(i -> i.getPartitions().stream())
        .filter(PartitionInfo::isLeader)
        .map(PartitionInfo::getPartitionId)
        .collect(Collectors.toList());
  }

  public ControlledActorClock getActorClock() {
    return actorClock;
  }

  public WorkflowClient getWorkflowClient() {
    return getClient().workflowClient();
  }

  public JobClient getJobClient() {
    return getClient().jobClient();
  }
}
