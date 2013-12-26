/**
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

package org.apache.tajo.engine.planner.global;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.tajo.ExecutionBlockId;
import org.apache.tajo.algebra.JoinType;
import org.apache.tajo.catalog.*;
import org.apache.tajo.catalog.proto.CatalogProtos;
import org.apache.tajo.catalog.proto.CatalogProtos.StoreType;
import org.apache.tajo.conf.TajoConf;
import org.apache.tajo.engine.eval.AggregationFunctionCallEval;
import org.apache.tajo.engine.eval.EvalTreeUtil;
import org.apache.tajo.engine.planner.*;
import org.apache.tajo.engine.planner.global.ExecutionPlanEdge.Tag;
import org.apache.tajo.engine.planner.logical.*;
import org.apache.tajo.storage.AbstractStorageManager;

import java.io.IOException;
import java.util.*;

import static org.apache.tajo.ipc.TajoWorkerProtocol.PartitionType.*;

public class GlobalPlanner {
  private static Log LOG = LogFactory.getLog(GlobalPlanner.class);

  private TajoConf conf;
  private CatalogProtos.StoreType storeType;

  public GlobalPlanner(final TajoConf conf, final AbstractStorageManager sm)
      throws IOException {
    this.conf = conf;
    this.storeType = CatalogProtos.StoreType.valueOf(conf.getVar(TajoConf.ConfVars.SHUFFLE_FILE_FORMAT).toUpperCase());
    Preconditions.checkArgument(storeType != null);
  }

  public class GlobalPlanContext {
    MasterPlan plan;
    Map<Integer, ExecutionBlock> execBlockMap = Maps.newHashMap();
  }

  /**
   * Builds a master plan from the given logical plan.
   */
  public void build(MasterPlan masterPlan) throws IOException, PlanningException {

    DistributedPlannerVisitor planner = new DistributedPlannerVisitor();
    GlobalPlanContext globalPlanContext = new GlobalPlanContext();
    globalPlanContext.plan = masterPlan;
    LOG.info(masterPlan.getLogicalPlan());

    LogicalNode inputPlan = PlannerUtil.clone(masterPlan.getLogicalPlan().getPidFactory(),
        masterPlan.getLogicalPlan().getRootBlock().getRoot());
    LogicalNode lastNode = planner.visitChild(globalPlanContext, masterPlan.getLogicalPlan(), inputPlan,
        new Stack<LogicalNode>());

    ExecutionBlock childExecBlock = globalPlanContext.execBlockMap.get(lastNode.getPID());

    if (childExecBlock.getPlan() != null) {
      ExecutionBlock terminalBlock = masterPlan.createTerminalBlock();
      DataChannel dataChannel = new DataChannel(childExecBlock, terminalBlock, lastNode.getPID(), -1, NONE_PARTITION, 1,
          lastNode.getOutSchema());
      dataChannel.setStoreType(CatalogProtos.StoreType.CSV);
      masterPlan.addConnect(dataChannel);
      masterPlan.setTerminal(terminalBlock);
    } else {
      masterPlan.setTerminal(childExecBlock);
    }

    LOG.info(masterPlan);
  }

  public static ScanNode buildInputExecutor(LogicalPlan plan, Schema schema, ExecutionBlockId srcId, ExecutionBlockId targetId, StoreType storeType) {
    Preconditions.checkArgument(schema != null,
        "Channel schema (" + srcId +" -> "+ targetId + ") is not initialized");
    TableMeta meta = new TableMeta(storeType, new Options());
    TableDesc desc = new TableDesc(srcId.toString(), schema, meta, new Path("/"));
    return new ScanNode(plan.newPID(), desc);
  }

  public static ScanNode buildInputExecutor(LogicalPlan plan, DataChannel channel) {
    Preconditions.checkArgument(channel.getSchema() != null,
        "Channel schema (" + channel.getSrcId().getId() +" -> "+ channel.getTargetId().getId()+") is not initialized");
    TableMeta meta = new TableMeta(channel.getStoreType(), new Options());
    TableDesc desc = new TableDesc(channel.getSrcId().toString(), channel.getSchema(), meta, new Path("/"));
    return new ScanNode(plan.newPID(), desc);
  }

  private DataChannel createDataChannelFromJoin(ExecutionBlock leftBlock, ExecutionBlock rightBlock,
                                                ExecutionBlock parent, JoinNode join, boolean leftTable) {
    ExecutionBlock childBlock;
    int targetPid;
    if (leftTable) {
      childBlock = leftBlock;
      targetPid = parent.getInputContext().getScanNodes()[0].getPID();
    } else {
      childBlock = rightBlock;
      targetPid = parent.getInputContext().getScanNodes()[1].getPID();
    }
    LogicalNode srcTopNode = childBlock.getPlan().getChild(childBlock.getPlan().getTerminalNode(), 0);
    int srcPid = srcTopNode.getPID();

    DataChannel channel = new DataChannel(childBlock, parent, srcPid, targetPid, HASH_PARTITION, 32,
        srcTopNode.getOutSchema());
    channel.setStoreType(storeType);
    if (join.getJoinType() != JoinType.CROSS) {
      Column [][] joinColumns = PlannerUtil.joinJoinKeyForEachTable(join.getJoinQual(),
          leftBlock.getPlan().getOutSchema(0), rightBlock.getPlan().getOutSchema(0));
      if (leftTable) {
        channel.setPartitionKey(joinColumns[0]);
      } else {
        channel.setPartitionKey(joinColumns[1]);
      }
    }
    return channel;
  }

  private ExecutionBlock buildJoinPlan(GlobalPlanContext context, JoinNode joinNode,
                                       ExecutionBlock leftBlock, ExecutionBlock rightBlock)
      throws PlanningException {
    MasterPlan masterPlan = context.plan;
    ExecutionBlock currentBlock;

    LogicalNode leftNode = joinNode.getLeftChild();
    LogicalNode rightNode = joinNode.getRightChild();

    boolean leftBroadcasted = false;
    boolean rightBroadcasted = false;

    if (leftNode.getType() == NodeType.SCAN && rightNode.getType() == NodeType.SCAN ) {
      ScanNode leftScan = (ScanNode) leftNode;
      ScanNode rightScan = (ScanNode) rightNode;

      TableDesc leftDesc = leftScan.getTableDesc();
      TableDesc rightDesc = rightScan.getTableDesc();
      long broadcastThreshold = conf.getLongVar(TajoConf.ConfVars.DIST_QUERY_BROADCAST_JOIN_THRESHOLD);

      if (leftDesc.getStats().getNumBytes() < broadcastThreshold) {
        leftBroadcasted = true;
      }
      if (rightDesc.getStats().getNumBytes() < broadcastThreshold) {
        rightBroadcasted = true;
      }

      if (leftBroadcasted || rightBroadcasted) {
        currentBlock = masterPlan.newExecutionBlock();
        currentBlock.setPlan(joinNode);
        if (leftBroadcasted) {
          currentBlock.addBroadcastTable(leftScan.getCanonicalName());
        }
        if (rightBroadcasted) {
          currentBlock.addBroadcastTable(rightScan.getCanonicalName());
        }

        context.execBlockMap.remove(leftScan.getPID());
        context.execBlockMap.remove(rightScan.getPID());
        return currentBlock;
      }
    }

    // symmetric repartition join
    currentBlock = masterPlan.newExecutionBlock();

    DataChannel leftChannel = createDataChannelFromJoin(leftBlock, rightBlock, currentBlock, joinNode, true);
    DataChannel rightChannel = createDataChannelFromJoin(leftBlock, rightBlock, currentBlock, joinNode, false);

    ScanNode leftScan = buildInputExecutor(masterPlan.getLogicalPlan(), leftChannel);
    ScanNode rightScan = buildInputExecutor(masterPlan.getLogicalPlan(), rightChannel);

    joinNode.setLeftChild(leftScan);
    joinNode.setRightChild(rightScan);
    currentBlock.setPlan(joinNode);

    masterPlan.addConnect(leftChannel);
    masterPlan.addConnect(rightChannel);

    return currentBlock;
  }

  /**
   * If a query contains a distinct aggregation function, the query does not
   * perform pre-aggregation in the first phase. Instead, in the fist phase,
   * the query performs only hash shuffle. Then, the query performs the
   * sort aggregation in the second phase. At that time, the aggregation
   * function should be executed as the first phase.
   */
  private ExecutionBlock buildDistinctGroupBy(GlobalPlanContext context, ExecutionBlock childBlock,
                                              GroupbyNode groupbyNode) {
    // setup child block
    LogicalNode topMostOfFirstPhase = groupbyNode.getChild();
    childBlock.setPlan(topMostOfFirstPhase);

    // setup current block
    ExecutionBlock currentBlock = context.plan.newExecutionBlock();
    LinkedHashSet<Column> columnsForDistinct = new LinkedHashSet<Column>();
    for (Target target : groupbyNode.getTargets()) {
      List<AggregationFunctionCallEval> functions = EvalTreeUtil.findDistinctAggFunction(target.getEvalTree());
      for (AggregationFunctionCallEval function : functions) {
        if (function.isDistinct()) {
          columnsForDistinct.addAll(EvalTreeUtil.findDistinctRefColumns(function));
        } else {
          // See the comment of this method. the aggregation function should be executed as the first phase.
          function.setFirstPhase();
        }
      }
    }

    // Set sort aggregation enforcer to the second groupby node
    Set<Column> existingColumns = Sets.newHashSet(groupbyNode.getGroupingColumns());
    columnsForDistinct.removeAll(existingColumns); // remove existing grouping columns
    SortSpec [] sortSpecs = PlannerUtil.columnsToSortSpec(columnsForDistinct);
    currentBlock.getEnforcer().enforceSortAggregation(groupbyNode.getPID(), sortSpecs);

    // setup current block with channel
    ScanNode scanNode = buildInputExecutor(context.plan.getLogicalPlan(), topMostOfFirstPhase.getOutSchema(),
        childBlock.getId(), currentBlock.getId(), storeType);
    groupbyNode.setChild(scanNode);
    currentBlock.setPlan(groupbyNode);

    // setup channel
    DataChannel channel;
    channel = new DataChannel(childBlock, currentBlock, topMostOfFirstPhase.getPID(), scanNode.getPID(), HASH_PARTITION,
        32, topMostOfFirstPhase.getOutSchema());
    channel.setPartitionKey(groupbyNode.getGroupingColumns());
    channel.setStoreType(storeType);

    context.plan.addConnect(channel);

    return currentBlock;
  }

  private ExecutionBlock buildGroupBy(GlobalPlanContext context, ExecutionBlock childBlock,
                                      GroupbyNode groupbyNode)
      throws PlanningException {

    MasterPlan masterPlan = context.plan;
    ExecutionBlock currentBlock;

    if (groupbyNode.isDistinct()) {
      return buildDistinctGroupBy(context, childBlock, groupbyNode);
    } else {
      GroupbyNode firstPhaseGroupBy = PlannerUtil.transformGroupbyTo2P(groupbyNode);
      firstPhaseGroupBy.setHavingCondition(null);

      if (firstPhaseGroupBy.getChild().getType() == NodeType.TABLE_SUBQUERY &&
          ((TableSubQueryNode)firstPhaseGroupBy.getChild()).getSubQuery().getType() == NodeType.UNION) {

        currentBlock = childBlock;
        for (DataChannel dataChannel : masterPlan.getIncomingChannels(currentBlock.getId())) {
          if (firstPhaseGroupBy.isEmptyGrouping()) {
            dataChannel.setPartition(HASH_PARTITION, firstPhaseGroupBy.getGroupingColumns(), 1);
          } else {
            dataChannel.setPartition(HASH_PARTITION, firstPhaseGroupBy.getGroupingColumns(), 32);
          }
          dataChannel.setSchema(firstPhaseGroupBy.getOutSchema());

          ExecutionBlock subBlock = masterPlan.getExecBlock(dataChannel.getSrcId());
          ExecutionPlan subBlockPlan = subBlock.getPlan();
          LogicalNode topNode = subBlockPlan.getChild(subBlockPlan.getTerminalNode(), 0);
          GroupbyNode g1 = PlannerUtil.clone(context.plan.getLogicalPlan().getPidFactory(), firstPhaseGroupBy);
          subBlockPlan.add(topNode, g1, Tag.SINGLE);
//          g1.setChild(subBlock.getPlan());
          subBlock.setPlan(g1);

          GroupbyNode g2 = PlannerUtil.clone(context.plan.getLogicalPlan().getPidFactory(), groupbyNode);
          ScanNode scanNode = buildInputExecutor(masterPlan.getLogicalPlan(), dataChannel);
          g2.setChild(scanNode);
          currentBlock.setPlan(g2);
        }
      } else { // general hash-shuffled aggregation
        childBlock.setPlan(firstPhaseGroupBy);
        currentBlock = masterPlan.newExecutionBlock();

        DataChannel channel;
        if (firstPhaseGroupBy.isEmptyGrouping()) {
          channel = new DataChannel(childBlock, currentBlock, HASH_PARTITION, 1);
          channel.setPartitionKey(firstPhaseGroupBy.getGroupingColumns());
        } else {
          channel = new DataChannel(childBlock, currentBlock, HASH_PARTITION, 32);
          channel.setPartitionKey(firstPhaseGroupBy.getGroupingColumns());
        }
        channel.setSchema(firstPhaseGroupBy.getOutSchema());
        channel.setStoreType(storeType);

        ScanNode scanNode = buildInputExecutor(masterPlan.getLogicalPlan(), channel);
        groupbyNode.setChild(scanNode);
        groupbyNode.setInSchema(scanNode.getOutSchema());
        currentBlock.setPlan(groupbyNode);
        masterPlan.addConnect(channel);
      }
    }

    return currentBlock;
  }

  private ExecutionBlock buildSortPlan(GlobalPlanContext context, ExecutionBlock childBlock, SortNode currentNode) {
    MasterPlan masterPlan = context.plan;
    ExecutionBlock currentBlock;

    SortNode firstSortNode = PlannerUtil.clone(context.plan.getLogicalPlan(), currentNode);
    LogicalNode childBlockPlan = childBlock.getPlan();
    firstSortNode.setChild(childBlockPlan);
    // sort is a non-projectable operator. So, in/out schemas are the same to its child operator.
    firstSortNode.setInSchema(childBlockPlan.getOutSchema());
    firstSortNode.setOutSchema(childBlockPlan.getOutSchema());
    childBlock.setPlan(firstSortNode);

    currentBlock = masterPlan.newExecutionBlock();
    DataChannel channel = new DataChannel(childBlock, currentBlock, RANGE_PARTITION, 32);
    channel.setPartitionKey(PlannerUtil.sortSpecsToSchema(currentNode.getSortKeys()).toArray());
    channel.setSchema(firstSortNode.getOutSchema());
    channel.setStoreType(storeType);

    ScanNode secondScan = buildInputExecutor(masterPlan.getLogicalPlan(), channel);
    currentNode.setChild(secondScan);
    currentNode.setInSchema(secondScan.getOutSchema());
    currentBlock.setPlan(currentNode);
    masterPlan.addConnect(channel);

    return currentBlock;
  }

  public class DistributedPlannerVisitor extends BasicLogicalPlanVisitor<GlobalPlanContext, LogicalNode> {

    @Override
    public LogicalNode visitRoot(GlobalPlanContext context, LogicalPlan plan, LogicalRootNode node,
                                 Stack<LogicalNode> stack) throws PlanningException {
      LogicalNode child = super.visitRoot(context, plan, node, stack);
      return child;
    }

    @Override
    public LogicalNode visitProjection(GlobalPlanContext context, LogicalPlan plan, ProjectionNode node,
                                       Stack<LogicalNode> stack) throws PlanningException {
      LogicalNode child = super.visitProjection(context, plan, node, stack);

      ExecutionBlock execBlock = context.execBlockMap.remove(child.getPID());

      node.setChild(execBlock.getPlan());
      node.setInSchema(execBlock.getPlan().getOutSchema());
      execBlock.setPlan(node);
      context.execBlockMap.put(node.getPID(), execBlock);
      return node;
    }

    @Override
    public LogicalNode visitLimit(GlobalPlanContext context, LogicalPlan plan, LimitNode node, Stack<LogicalNode> stack)
        throws PlanningException {
      LogicalNode child = super.visitLimit(context, plan, node, stack);

      ExecutionBlock block;
      block = context.execBlockMap.remove(child.getPID());
      if (child.getType() == NodeType.SORT) {
        node.setChild(block.getPlan());
        block.setPlan(node);

        ExecutionBlock childBlock = context.plan.getChild(block, 0);
        LimitNode childLimit = PlannerUtil.clone(context.plan.getLogicalPlan(), node);
        childLimit.setChild(childBlock.getPlan());
        childBlock.setPlan(childLimit);

        DataChannel channel = context.plan.getChannel(childBlock, block);
        channel.setPartitionNum(1);
        context.execBlockMap.put(node.getPID(), block);
      } else {
        node.setChild(block.getPlan());
        block.setPlan(node);

        ExecutionBlock newExecBlock = context.plan.newExecutionBlock();
        DataChannel newChannel = new DataChannel(block, newExecBlock, HASH_PARTITION, 1);
        newChannel.setPartitionKey(new Column[]{});
        newChannel.setSchema(node.getOutSchema());
        newChannel.setStoreType(storeType);

        ScanNode scanNode = buildInputExecutor(plan, newChannel);
        LimitNode parentLimit = PlannerUtil.clone(context.plan.getLogicalPlan(), node);
        parentLimit.setChild(scanNode);
        newExecBlock.setPlan(parentLimit);
        context.plan.addConnect(newChannel);
        context.execBlockMap.put(parentLimit.getPID(), newExecBlock);
        node = parentLimit;
      }

      return node;
    }

    @Override
    public LogicalNode visitSort(GlobalPlanContext context, LogicalPlan plan, SortNode node, Stack<LogicalNode> stack)
        throws PlanningException {

      LogicalNode child = super.visitSort(context, plan, node, stack);

      ExecutionBlock childBlock = context.execBlockMap.remove(child.getPID());
      ExecutionBlock newExecBlock = buildSortPlan(context, childBlock, node);
      context.execBlockMap.put(node.getPID(), newExecBlock);

      return node;
    }

    @Override
    public LogicalNode visitGroupBy(GlobalPlanContext context, LogicalPlan plan, GroupbyNode node,
                                    Stack<LogicalNode> stack) throws PlanningException {
      LogicalNode child = super.visitGroupBy(context, plan, node, stack);

      ExecutionBlock childBlock = context.execBlockMap.remove(child.getPID());
      ExecutionBlock newExecBlock = buildGroupBy(context, childBlock, node);
      context.execBlockMap.put(node.getPID(), newExecBlock);

      return node;
    }

    @Override
    public LogicalNode visitFilter(GlobalPlanContext context, LogicalPlan plan, SelectionNode node,
                                   Stack<LogicalNode> stack) throws PlanningException {
      LogicalNode child = super.visitFilter(context, plan, node, stack);

      ExecutionBlock execBlock = context.execBlockMap.remove(child.getPID());
      node.setChild(execBlock.getPlan());
      node.setInSchema(execBlock.getPlan().getOutSchema());
      execBlock.setPlan(node);
      context.execBlockMap.put(node.getPID(), execBlock);

      return node;
    }

    @Override
    public LogicalNode visitJoin(GlobalPlanContext context, LogicalPlan plan, JoinNode node, Stack<LogicalNode> stack)
        throws PlanningException {
      LogicalNode leftChild = visitChild(context, plan, node.getLeftChild(), stack);
      LogicalNode rightChild = visitChild(context, plan, node.getRightChild(), stack);

      ExecutionBlock leftChildBlock = context.execBlockMap.get(leftChild.getPID());
      ExecutionBlock rightChildBlock = context.execBlockMap.get(rightChild.getPID());

      ExecutionBlock newExecBlock = buildJoinPlan(context, node, leftChildBlock, rightChildBlock);
      context.execBlockMap.put(node.getPID(), newExecBlock);

      return node;
    }

    @Override
    public LogicalNode visitUnion(GlobalPlanContext context, LogicalPlan plan, UnionNode node,
                                  Stack<LogicalNode> stack) throws PlanningException {
      stack.push(node);
      LogicalNode leftChild = visitChild(context, plan, node.getLeftChild(), stack);
      LogicalNode rightChild = visitChild(context, plan, node.getRightChild(), stack);
      stack.pop();

      List<ExecutionBlock> unionBlocks = Lists.newArrayList();
      List<ExecutionBlock> queryBlockBlocks = Lists.newArrayList();

      ExecutionBlock leftBlock = context.execBlockMap.remove(leftChild.getPID());
      ExecutionBlock rightBlock = context.execBlockMap.remove(rightChild.getPID());
      if (leftChild.getType() == NodeType.UNION) {
        unionBlocks.add(leftBlock);
      } else {
        queryBlockBlocks.add(leftBlock);
      }
      if (rightChild.getType() == NodeType.UNION) {
        unionBlocks.add(rightBlock);
      } else {
        queryBlockBlocks.add(rightBlock);
      }

      ExecutionBlock execBlock;
      if (unionBlocks.size() == 0) {
        execBlock = context.plan.newExecutionBlock();
      } else {
        execBlock = unionBlocks.get(0);
      }

      for (ExecutionBlock childBlocks : unionBlocks) {
        UnionNode union = (UnionNode) childBlocks.getPlan();
        queryBlockBlocks.add(context.execBlockMap.get(union.getLeftChild().getPID()));
        queryBlockBlocks.add(context.execBlockMap.get(union.getRightChild().getPID()));
      }

      for (ExecutionBlock childBlocks : queryBlockBlocks) {
        DataChannel channel = new DataChannel(childBlocks, execBlock, NONE_PARTITION, 1);
        channel.setStoreType(storeType);
        context.plan.addConnect(channel);
      }

      context.execBlockMap.put(node.getPID(), execBlock);

      return node;
    }

    private LogicalNode handleUnaryNode(GlobalPlanContext context, LogicalNode child, LogicalNode node) {
      ExecutionBlock execBlock = context.execBlockMap.remove(child.getPID());
      execBlock.setPlan(node);
      context.execBlockMap.put(node.getPID(), execBlock);

      return node;
    }

    @Override
    public LogicalNode visitExcept(GlobalPlanContext context, LogicalPlan plan, ExceptNode node,
                                   Stack<LogicalNode> stack) throws PlanningException {
      LogicalNode child = super.visitExcept(context, plan, node, stack);
      return handleUnaryNode(context, child, node);
    }

    @Override
    public LogicalNode visitIntersect(GlobalPlanContext context, LogicalPlan plan, IntersectNode node,
                                      Stack<LogicalNode> stack) throws PlanningException {
      LogicalNode child = super.visitIntersect(context, plan, node, stack);
      return handleUnaryNode(context, child, node);
    }

    @Override
    public LogicalNode visitTableSubQuery(GlobalPlanContext context, LogicalPlan plan, TableSubQueryNode node,
                                          Stack<LogicalNode> stack) throws PlanningException {
      LogicalNode child = super.visitTableSubQuery(context, plan, node, stack);
      return handleUnaryNode(context, child, node);
    }

    @Override
    public LogicalNode visitScan(GlobalPlanContext context, LogicalPlan plan, ScanNode node, Stack<LogicalNode> stack)
        throws PlanningException {
      ExecutionBlock newExecBlock = context.plan.newExecutionBlock();
      newExecBlock.setPlan(node);
      context.execBlockMap.put(node.getPID(), newExecBlock);
      return node;
    }

    @Override
    public LogicalNode visitStoreTable(GlobalPlanContext context, LogicalPlan plan, StoreTableNode node,
                                       Stack<LogicalNode> stack) throws PlanningException {
      LogicalNode child = super.visitStoreTable(context, plan, node, stack);

      ExecutionBlock execBlock = context.execBlockMap.remove(child.getPID());
      node.setChild(execBlock.getPlan());
      node.setInSchema(execBlock.getPlan().getOutSchema());
      execBlock.setPlan(node);
      context.execBlockMap.put(node.getPID(), execBlock);

      return node;
    }

    @Override
    public LogicalNode visitInsert(GlobalPlanContext context, LogicalPlan plan, InsertNode node,
                                   Stack<LogicalNode> stack)
        throws PlanningException {
      LogicalNode child = super.visitInsert(context, plan, node, stack);
      ExecutionBlock execBlock = context.execBlockMap.remove(child.getPID());
      execBlock.setPlan(node);
      context.execBlockMap.put(node.getPID(), execBlock);

      return node;
    }
  }

  private class UnionsFinderContext {
    List<UnionNode> unionList = new ArrayList<UnionNode>();
  }

  @SuppressWarnings("unused")
  private class ConsecutiveUnionFinder extends BasicLogicalPlanVisitor<UnionsFinderContext, LogicalNode> {
    @Override
    public LogicalNode visitUnion(UnionsFinderContext context, LogicalPlan plan, UnionNode node,
                                  Stack<LogicalNode> stack)
        throws PlanningException {
      if (node.getType() == NodeType.UNION) {
        context.unionList.add(node);
      }

      stack.push(node);
      TableSubQueryNode leftSubQuery = node.getLeftChild();
      TableSubQueryNode rightSubQuery = node.getRightChild();
      if (leftSubQuery.getSubQuery().getType() == NodeType.UNION) {
        visitChild(context, plan, leftSubQuery, stack);
      }
      if (rightSubQuery.getSubQuery().getType() == NodeType.UNION) {
        visitChild(context, plan, rightSubQuery, stack);
      }
      stack.pop();

      return node;
    }
  }
}
