/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.query.planner.explain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.pinot.query.planner.physical.DispatchablePlanFragment;
import org.apache.pinot.query.planner.physical.DispatchableSubPlan;
import org.apache.pinot.query.planner.plannode.AggregateNode;
import org.apache.pinot.query.planner.plannode.ExchangeNode;
import org.apache.pinot.query.planner.plannode.ExplainedNode;
import org.apache.pinot.query.planner.plannode.FilterNode;
import org.apache.pinot.query.planner.plannode.JoinNode;
import org.apache.pinot.query.planner.plannode.MailboxReceiveNode;
import org.apache.pinot.query.planner.plannode.MailboxSendNode;
import org.apache.pinot.query.planner.plannode.PlanNode;
import org.apache.pinot.query.planner.plannode.PlanNodeVisitor;
import org.apache.pinot.query.planner.plannode.ProjectNode;
import org.apache.pinot.query.planner.plannode.SetOpNode;
import org.apache.pinot.query.planner.plannode.SortNode;
import org.apache.pinot.query.planner.plannode.TableScanNode;
import org.apache.pinot.query.planner.plannode.ValueNode;
import org.apache.pinot.query.planner.plannode.WindowNode;
import org.apache.pinot.query.routing.MailboxInfo;
import org.apache.pinot.query.routing.QueryServerInstance;


/**
 * A visitor that converts a {@code QueryPlan} into a human-readable string representation.
 *
 * <p>It is getting used for getting the physical plan of the query.</p>
 */
public class PhysicalExplainPlanVisitor implements PlanNodeVisitor<StringBuilder, PhysicalExplainPlanVisitor.Context> {

  private final DispatchableSubPlan _dispatchableSubPlan;

  public PhysicalExplainPlanVisitor(DispatchableSubPlan dispatchableSubPlan) {
    _dispatchableSubPlan = dispatchableSubPlan;
  }

  /**
   * Explains the query plan.
   *
   * @param dispatchableSubPlan the queryPlan to explain
   * @return a String representation of the query plan tree
   */
  public static String explain(DispatchableSubPlan dispatchableSubPlan) {
    if (dispatchableSubPlan.getQueryStageMap().isEmpty()) {
      return "EMPTY";
    }

    // the root of a query plan always only has a single node
    QueryServerInstance rootServer =
        dispatchableSubPlan.getQueryStageMap().get(0).getServerInstanceToWorkerIdMap()
            .keySet().iterator().next();
    return explainFrom(dispatchableSubPlan,
        dispatchableSubPlan.getQueryStageMap().get(0).getPlanFragment().getFragmentRoot(), rootServer);
  }

  /**
   * Explains the query plan from a specific point in the subtree, taking {@code rootServer}
   * as the node that is executing this sub-tree. This is helpful for debugging what is happening
   * at a given point in time (for example, printing the tree that will be executed on a
   * local node right before it is executed).
   *
   * @param dispatchableSubPlan the entire query plan, including non-executed portions
   * @param node the node to begin traversal
   * @param rootServer the server instance that is executing this plan (should execute {@code node})
   *
   * @return a query plan associated with
   */
  public static String explainFrom(DispatchableSubPlan dispatchableSubPlan, PlanNode node,
      QueryServerInstance rootServer) {
    final PhysicalExplainPlanVisitor visitor = new PhysicalExplainPlanVisitor(dispatchableSubPlan);
    return node
        .visit(visitor, new Context(rootServer, 0, "", "", new StringBuilder()))
        .toString();
  }

  /**
   * This wrapper prints out contextual info from {@link Context} before invoking {@link PlanNode#explain()}.
   * The format of the contextual info is always:
   *   "`PREFIX`[`FRAGMENT_ID`]@`HOSTNAME`:`PORT`|[`WORKER_ID`(s)] `EXPLAIN`"
   *
   * @param node the {@link PlanNode} to be explained
   * @param context the {@link Context} to be wrapped in front ot plan node explain.
   * @return stringify format of the explained result wrapped with contextual info.
   */
  private StringBuilder appendInfo(PlanNode node, Context context) {
    int stageId = node.getStageId();
    context._builder
        .append(context._prefix)
        .append('[')
        .append(stageId)
        .append("]@")
        .append(context._host.getHostname())
        .append(':')
        .append(context._host.getQueryServicePort())
        .append("|[")
        .append(context._workerId)
        .append("] ")
        .append(node.explain());
    return context._builder;
  }

  private StringBuilder visitSimpleNode(PlanNode node, Context context) {
    appendInfo(node, context).append('\n');
    return node.getInputs().get(0).visit(this, context.next(false, context._host, context._workerId));
  }

  @Override
  public StringBuilder visitAggregate(AggregateNode node, Context context) {
    return visitSimpleNode(node, context);
  }

  @Override
  public StringBuilder visitWindow(WindowNode node, Context context) {
    return visitSimpleNode(node, context);
  }

  @Override
  public StringBuilder visitSetOp(SetOpNode setOpNode, Context context) {
    appendInfo(setOpNode, context).append('\n');
    for (PlanNode input : setOpNode.getInputs()) {
      input.visit(this, context.next(false, context._host, context._workerId));
    }
    return context._builder;
  }

  @Override
  public StringBuilder visitExchange(ExchangeNode exchangeNode, Context context) {
    throw new UnsupportedOperationException("ExchangeNode should not be visited");
  }

  @Override
  public StringBuilder visitFilter(FilterNode node, Context context) {
    return visitSimpleNode(node, context);
  }

  @Override
  public StringBuilder visitJoin(JoinNode node, Context context) {
    appendInfo(node, context).append('\n');
    node.getInputs().get(0).visit(this, context.next(true, context._host, context._workerId));
    node.getInputs().get(1).visit(this, context.next(false, context._host, context._workerId));
    return context._builder;
  }

  @Override
  public StringBuilder visitMailboxReceive(MailboxReceiveNode node, Context context) {
    appendInfo(node, context).append('\n');

    MailboxSendNode sender = node.getSender();
    int senderStageId = node.getSenderStageId();
    DispatchablePlanFragment dispatchablePlanFragment = _dispatchableSubPlan.getQueryStageMap().get(senderStageId);

    Map<QueryServerInstance, List<Integer>> serverInstanceToWorkerIdMap =
        dispatchablePlanFragment.getServerInstanceToWorkerIdMap();
    Iterator<QueryServerInstance> iterator = serverInstanceToWorkerIdMap.keySet().iterator();
    while (iterator.hasNext()) {
      QueryServerInstance queryServerInstance = iterator.next();
      List<Integer> workerIdList = serverInstanceToWorkerIdMap.get(queryServerInstance);
      for (int idx = 0; idx < workerIdList.size(); idx++) {
        int workerId = workerIdList.get(idx);
        if (!iterator.hasNext() && idx == workerIdList.size() - 1) {
          // always print out the last one
          sender.visit(this, context.next(false, queryServerInstance, workerId));
        } else {
          // only print short version of the sender node
          appendMailboxSend(sender, context.next(true, queryServerInstance, workerId))
              .append(" (Subtree Omitted)")
              .append('\n');
        }
      }
    }
    return context._builder;
  }

  @Override
  public StringBuilder visitMailboxSend(MailboxSendNode node, Context context) {
    appendMailboxSend(node, context).append('\n');
    return node.getInputs().get(0).visit(this, context.next(false, context._host, context._workerId));
  }

  /**
   * Print out mailbox sending info.
   *
   * Noted that when print out mailbox sending info. the receiving side follows the contextual info format defined in
   * {@link PhysicalExplainPlanVisitor#appendInfo(PlanNode, Context)}.
   *
   * e.g. the RECEIVERs are printed as:
   *   "{[`FRAGMENT_ID`]@`HOSTNAME`:`PORT`|[`WORKER_ID`(s)]}" and are comma-separated.
   */
  private StringBuilder appendMailboxSend(MailboxSendNode node, Context context) {
    appendInfo(node, context);

    List<Stream<String>> perStageDescriptions = new ArrayList<>();
    // This iterator is guaranteed to be sorted by stageId
    for (Integer receiverStageId : node.getReceiverStageIds()) {
      List<MailboxInfo> receiverMailboxInfos =
          _dispatchableSubPlan.getQueryStageMap().get(node.getStageId()).getWorkerMetadataList().get(context._workerId)
              .getMailboxInfosMap().get(receiverStageId).getMailboxInfos();
      // Sort to ensure print order
      Stream<String> stageDescriptions = receiverMailboxInfos.stream()
          .sorted(Comparator.comparingInt(MailboxInfo::getPort))
          .map(v -> "[" + receiverStageId + "]@" + v);
      perStageDescriptions.add(stageDescriptions);
    }
    context._builder.append("->");
    String receivers = perStageDescriptions.stream()
        .flatMap(Function.identity())
        .collect(Collectors.joining(",", "{", "}"));
    return context._builder.append(receivers);
  }

  @Override
  public StringBuilder visitProject(ProjectNode node, Context context) {
    return visitSimpleNode(node, context);
  }

  @Override
  public StringBuilder visitSort(SortNode node, Context context) {
    return visitSimpleNode(node, context);
  }

  @Override
  public StringBuilder visitTableScan(TableScanNode node, Context context) {
    return appendInfo(node, context)
        .append(' ')
        .append(_dispatchableSubPlan.getQueryStageMap()
            .get(node.getStageId())
            .getWorkerIdToSegmentsMap()
            .get(context._host))
        .append('\n');
  }

  @Override
  public StringBuilder visitValue(ValueNode node, Context context) {
    return appendInfo(node, context);
  }

  @Override
  public StringBuilder visitExplained(ExplainedNode node, Context context) {
    return appendInfo(node, context);
  }

  static class Context {
    final QueryServerInstance _host;
    final int _workerId;
    final String _prefix;
    final String _childPrefix;
    final StringBuilder _builder;

    Context(QueryServerInstance host, int workerId, String prefix, String childPrefix, StringBuilder builder) {
      _host = host;
      _workerId = workerId;
      _prefix = prefix;
      _childPrefix = childPrefix;
      _builder = builder;
    }

    Context next(boolean hasMoreChildren, QueryServerInstance host, int workerId) {
      return new Context(
          host,
          workerId,
          hasMoreChildren ? _childPrefix + "├── " : _childPrefix + "└── ",
          hasMoreChildren ? _childPrefix + "│   " : _childPrefix + "    ",
          _builder
      );
    }
  }
}
