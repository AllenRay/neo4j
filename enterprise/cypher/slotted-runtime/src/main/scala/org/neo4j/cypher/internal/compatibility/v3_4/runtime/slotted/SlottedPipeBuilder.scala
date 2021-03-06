/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compatibility.v3_4.runtime.slotted

import org.neo4j.cypher.internal.compatibility.v3_4.runtime.SlotAllocation.PhysicalPlan
import org.neo4j.cypher.internal.compatibility.v3_4.runtime._
import org.neo4j.cypher.internal.compatibility.v3_4.runtime.executionplan.builders.prepare.KeyTokenResolver
import org.neo4j.cypher.internal.compatibility.v3_4.runtime.pipes.DropResultPipe
import org.neo4j.cypher.internal.compatibility.v3_4.runtime.slotted.pipes._
import org.neo4j.cypher.internal.compatibility.v3_4.runtime.slotted.{expressions => slottedExpressions}
import org.neo4j.cypher.internal.compiler.v3_4.planner.CantCompileQueryException
import org.neo4j.cypher.internal.frontend.v3_4.phases.Monitors
import org.neo4j.cypher.internal.frontend.v3_4.semantics.SemanticTable
import org.neo4j.cypher.internal.ir.v3_4.{IdName, VarPatternLength}
import org.neo4j.cypher.internal.planner.v3_4.spi.PlanContext
import org.neo4j.cypher.internal.runtime.interpreted.ExecutionContext
import org.neo4j.cypher.internal.runtime.interpreted.commands.convert.ExpressionConverters
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.{AggregationExpression, Expression}
import org.neo4j.cypher.internal.runtime.interpreted.commands.predicates.{Predicate, True}
import org.neo4j.cypher.internal.runtime.interpreted.commands.{expressions => commandExpressions}
import org.neo4j.cypher.internal.runtime.interpreted.pipes.{ColumnOrder => _, _}
import org.neo4j.cypher.internal.util.v3_4.AssertionRunner.Thunk
import org.neo4j.cypher.internal.util.v3_4.symbols._
import org.neo4j.cypher.internal.util.v3_4.{AssertionRunner, InternalException}
import org.neo4j.cypher.internal.v3_4.expressions.{Equals, SignedDecimalIntegerLiteral}
import org.neo4j.cypher.internal.v3_4.logical.plans
import org.neo4j.cypher.internal.v3_4.logical.plans._
import org.neo4j.cypher.internal.v3_4.{expressions => frontEndAst}

class SlottedPipeBuilder(fallback: PipeBuilder,
                         expressionConverters: ExpressionConverters,
                         monitors: Monitors,
                         physicalPlan: PhysicalPlan,
                         readOnly: Boolean,
                         rewriteAstExpression: (frontEndAst.Expression) => frontEndAst.Expression)
                        (implicit context: PipeExecutionBuilderContext, planContext: PlanContext)
  extends PipeBuilder {

  private val convertExpressions: (frontEndAst.Expression) => commandExpressions.Expression =
    rewriteAstExpression andThen expressionConverters.toCommandExpression

  override def build(plan: LogicalPlan): Pipe = {
    implicit val table: SemanticTable = context.semanticTable

    val id = plan.assignedId
    val slots = physicalPlan.slotConfigurations(plan.assignedId)
    val argumentSize = physicalPlan.argumentSizes(plan.assignedId)

    plan match {
      case AllNodesScan(IdName(column), _) =>
        AllNodesScanSlottedPipe(column, slots, argumentSize)(id)

      case NodeIndexScan(IdName(column), label, propertyKeys, _) =>
        NodeIndexScanSlottedPipe(column, label, propertyKeys, slots, argumentSize)(id)

      case NodeIndexSeek(IdName(column), label, propertyKeys, valueExpr, _) =>
        val indexSeekMode = IndexSeekModeFactory(unique = false, readOnly = readOnly).fromQueryExpression(valueExpr)
        NodeIndexSeekSlottedPipe(column, label, propertyKeys,
                                  valueExpr.map(convertExpressions), indexSeekMode, slots, argumentSize)(id)

      case NodeUniqueIndexSeek(IdName(column), label, propertyKeys, valueExpr, _) =>
        val indexSeekMode = IndexSeekModeFactory(unique = true, readOnly = readOnly).fromQueryExpression(valueExpr)
        NodeIndexSeekSlottedPipe(column, label, propertyKeys,
                                  valueExpr.map(convertExpressions), indexSeekMode, slots, argumentSize)(id = id)

      case NodeByLabelScan(IdName(column), label, _) =>
        NodesByLabelScanSlottedPipe(column, LazyLabel(label), slots, argumentSize)(id)

      case _:Argument =>
        ArgumentSlottedPipe(slots, argumentSize)(id)

      case _ =>
        throw new CantCompileQueryException(s"Unsupported logical plan operator: $plan")

    }
  }

  override def build(plan: LogicalPlan, source: Pipe): Pipe = {
    implicit val table: SemanticTable = context.semanticTable

    val id = plan.assignedId
    val slots = physicalPlan.slotConfigurations(plan.assignedId)

    plan match {
      case ProduceResult(_, columns) =>
        val runtimeColumns = createProjectionsForResult(columns, slots)
        ProduceResultSlottedPipe(source, runtimeColumns)(id)

      case Expand(_, IdName(from), dir, types, IdName(to), IdName(relName), ExpandAll) =>
        val fromSlot = slots.getLongOffsetFor(from)
        val relSlot = slots.getLongOffsetFor(relName)
        val toSlot = slots.getLongOffsetFor(to)
        ExpandAllSlottedPipe(source, fromSlot, relSlot, toSlot, dir, LazyTypes(types.toArray), slots)(id)

      case Expand(_, IdName(from), dir, types, IdName(to), IdName(relName), ExpandInto) =>
        val fromOffset = slots.getLongOffsetFor(from)
        val relOffset = slots.getLongOffsetFor(relName)
        val toOffset = slots.getLongOffsetFor(to)
        ExpandIntoSlottedPipe(source, fromOffset, relOffset, toOffset, dir, LazyTypes(types.toArray), slots)(id)

      case OptionalExpand(_, IdName(fromName), dir, types, IdName(toName), IdName(relName), ExpandAll, predicates) =>
        val fromOffset = slots.getLongOffsetFor(fromName)
        val relOffset = slots.getLongOffsetFor(relName)
        val toOffset = slots.getLongOffsetFor(toName)
        val predicate: Predicate = predicates.map(buildPredicate).reduceOption(_ andWith _).getOrElse(True())
        OptionalExpandAllSlottedPipe(source, fromOffset, relOffset, toOffset, dir, LazyTypes(types.toArray), predicate,
                                      slots)(id)

      case OptionalExpand(_, IdName(fromName), dir, types, IdName(toName), IdName(relName), ExpandInto, predicates) =>
        val fromOffset = slots.getLongOffsetFor(fromName)
        val relOffset = slots.getLongOffsetFor(relName)
        val toOffset = slots.getLongOffsetFor(toName)
        val predicate = predicates.map(buildPredicate).reduceOption(_ andWith _).getOrElse(True())
        OptionalExpandIntoSlottedPipe(source, fromOffset, relOffset, toOffset, dir, LazyTypes(types.toArray), predicate,
                                       slots)(id)

      case VarExpand(sourcePlan, IdName(fromName), dir, projectedDir, types, IdName(toName), IdName(relName),
                     VarPatternLength(min, max), expansionMode, IdName(tempNode), IdName(tempEdge), nodePredicate,
                     edgePredicate, _) =>

        val shouldExpandAll = expansionMode match {
          case ExpandAll => true
          case ExpandInto => false
        }
        val fromOffset = slots.getLongOffsetFor(fromName)
        val toOffset = slots.getLongOffsetFor(toName)
        val relOffset = slots.getReferenceOffsetFor(relName)

        // The node/edge predicates are evaluated on the source pipeline, not the produced one
        val sourceSlots = physicalPlan.slotConfigurations(sourcePlan.assignedId)
        val tempNodeOffset = sourceSlots.getLongOffsetFor(tempNode)
        val tempEdgeOffset = sourceSlots.getLongOffsetFor(tempEdge)
        val argumentSize = SlotConfiguration.Size(sourceSlots.numberOfLongs - 2, sourceSlots.numberOfReferences)
        VarLengthExpandSlottedPipe(source, fromOffset, relOffset, toOffset, dir, projectedDir, LazyTypes(types.toArray), min,
                                    max, shouldExpandAll, slots,
                                    tempNodeOffset = tempNodeOffset,
                                    tempEdgeOffset = tempEdgeOffset,
                                    nodePredicate = buildPredicate(nodePredicate),
                                    edgePredicate = buildPredicate(edgePredicate),
                                    argumentSize = argumentSize)(id)

      case Optional(inner, symbols) =>
        val nullableKeys = inner.availableSymbols -- symbols
        val nullableOffsets = nullableKeys.map(k => slots.getLongOffsetFor(k.name))
        val argumentSize = physicalPlan.argumentSizes(plan.assignedId)
        OptionalSlottedPipe(source, nullableOffsets.toSeq, slots, argumentSize)(id)

      case Projection(_, expressions) =>
        val expressionsWithSlots: Map[Int, Expression] = expressions collect {
          case (k, e) if refSlotAndNotAlias(slots, k) =>
            val slot = slots.get(k).get
            slot.offset -> convertExpressions(e)
        }
        ProjectionSlottedPipe(source, expressionsWithSlots)(id)

      case CreateNode(_, idName, labels, props) =>
        CreateNodeSlottedPipe(source, idName.name, slots, labels.map(LazyLabel.apply),
                               props.map(convertExpressions))(id)

      case MergeCreateNode(_, idName, labels, props) =>
        MergeCreateNodeSlottedPipe(source, idName.name, slots, labels.map(LazyLabel.apply), props.map(convertExpressions))(id)

      case EmptyResult(_) =>
        EmptyResultPipe(source)(id)

      case DropResult(_) =>
        DropResultPipe(source)(id)

      case UnwindCollection(_, IdName(name), expression) =>
        val offset = slots.getReferenceOffsetFor(name)
        UnwindSlottedPipe(source, convertExpressions(expression), offset, slots)(id)

      // Aggregation without grouping, such as RETURN count(*)
      case Aggregation(_, groupingExpressions, aggregationExpression) if groupingExpressions.isEmpty =>
        val aggregation = aggregationExpression.map {
          case (key, expression) =>
            slots.getReferenceOffsetFor(key) -> convertExpressions(expression)
              .asInstanceOf[AggregationExpression]
        }
        EagerAggregationWithoutGroupingSlottedPipe(source, slots, aggregation)(id)

      case Aggregation(_, groupingExpressions, aggregationExpression) =>
        val grouping = groupingExpressions.map {
          case (key, expression) =>
            slots.getReferenceOffsetFor(key) -> convertExpressions(expression)
        }
        val aggregation = aggregationExpression.map {
          case (key, expression) =>
            slots.getReferenceOffsetFor(key) -> convertExpressions(expression)
              .asInstanceOf[AggregationExpression]
        }
        EagerAggregationSlottedPipe(source, slots, grouping, aggregation)(id)

      case Distinct(_, groupingExpressions) =>
        val grouping = groupingExpressions.map {
          case (key, expression) =>
            slots.getReferenceOffsetFor(key) -> convertExpressions(expression)
        }

        DistinctSlottedPipe(source, slots, grouping)(id)

      case CreateRelationship(_, idName, IdName(startNode), typ, IdName(endNode), props) =>
        val fromOffset = slots.getLongOffsetFor(startNode)
        val endOffset = slots.getLongOffsetFor(endNode)
        CreateRelationshipSlottedPipe(source, idName.name, fromOffset, LazyType(typ)(context.semanticTable), endOffset,
                                       slots, props.map(convertExpressions))(id = id)

      case MergeCreateRelationship(_, idName, IdName(startNode), typ, IdName(endNode), props) =>
        val fromOffset = slots.getLongOffsetFor(startNode)
        val endOffset = slots.getLongOffsetFor(endNode)
        MergeCreateRelationshipSlottedPipe(source, idName.name, fromOffset, LazyType(typ)(context.semanticTable),
                                            endOffset, slots, props.map(convertExpressions))(id = id)

      case Top(_, sortItems, SignedDecimalIntegerLiteral("1")) =>
        Top1SlottedPipe(source, sortItems.map(translateColumnOrder(slots, _)).toList)(id = id)

      case Top(_, sortItems, limit) =>
        TopNSlottedPipe(source, sortItems.map(translateColumnOrder(slots, _)).toList, convertExpressions(limit))(id = id)

      case Limit(_, count, IncludeTies) =>
        (source, count) match {
          case (SortSlottedPipe(inner, sortDescription, _), SignedDecimalIntegerLiteral("1")) =>
            Top1WithTiesSlottedPipe(inner, sortDescription.toList)(id = id)

          case _ => throw new InternalException("Including ties is only supported for very specific plans")
        }

      // Pipes that do not themselves read/write slots should be fine to use the fallback (non-slot aware pipes)
      case _: Selection |
           _: Limit |
           _: ErrorPlan |
           _: Skip =>
        fallback.build(plan, source)

      case Sort(_, sortItems) =>
        SortSlottedPipe(source, sortItems.map(translateColumnOrder(slots, _)), slots)(id = id)

      case Eager(_) =>
        EagerSlottedPipe(source, slots)(id)

      case _ =>
        throw new CantCompileQueryException(s"Unsupported logical plan operator: $plan")
    }
  }

  private def refSlotAndNotAlias(slots: SlotConfiguration, k: String) = {
    !slots.isAlias(k) &&
      slots.get(k).forall(_.isInstanceOf[RefSlot])
  }

  private def translateColumnOrder(slots: SlotConfiguration, s: plans.ColumnOrder): pipes.ColumnOrder = s match {
    case plans.Ascending(IdName(name)) => {
      slots.get(name) match {
        case Some(slot) => pipes.Ascending(slot)
        case None => throw new InternalException(s"Did not find `$name` in the slot configuration")
      }
    }
    case plans.Descending(IdName(name)) => {
      slots.get(name) match {
        case Some(slot) => pipes.Descending(slot)
        case None => throw new InternalException(s"Did not find `$name` in the slot configuration")
      }
    }
  }

  private def createProjectionsForResult(columns: Seq[String], slots: SlotConfiguration) = {
    val runtimeColumns: Seq[(String, commandExpressions.Expression)] =
      columns.map(createProjectionForIdentifier(slots))
    runtimeColumns
  }

  private def createProjectionForIdentifier(slots: SlotConfiguration)(identifier: String) = {
    val slot = slots.get(identifier).getOrElse(
      throw new InternalException(s"Did not find `$identifier` in the slot configuration")
    )
    identifier -> SlottedPipeBuilder.projectSlotExpression(slot)
  }

  private def buildPredicate(expr: frontEndAst.Expression)
                            (implicit context: PipeExecutionBuilderContext, planContext: PlanContext): Predicate = {
    val rewrittenExpr: frontEndAst.Expression = rewriteAstExpression(expr)

    expressionConverters.toCommandPredicate(rewrittenExpr).rewrite(KeyTokenResolver.resolveExpressions(_, planContext))
      .asInstanceOf[Predicate]
  }

  override def build(plan: LogicalPlan, lhs: Pipe, rhs: Pipe): Pipe = {
    implicit val table: SemanticTable = context.semanticTable

    val slotConfigs = physicalPlan.slotConfigurations
    val id = plan.assignedId
    val slots = slotConfigs(plan.assignedId)

    plan match {
      case Apply(_, _) =>
        ApplySlottedPipe(lhs, rhs)(id)

      case _: SemiApply |
           _: AntiSemiApply =>
        fallback.build(plan, lhs, rhs)

      case RollUpApply(_, rhsPlan, collectionName, identifierToCollect, nullables) =>
        val rhsSlots = slotConfigs(rhsPlan.assignedId)
        val identifierToCollectExpression = createProjectionForIdentifier(rhsSlots)(identifierToCollect.name)
        val collectionRefSlotOffset = slots.getReferenceOffsetFor(collectionName.name)
        RollUpApplySlottedPipe(lhs, rhs, collectionRefSlotOffset, identifierToCollectExpression,
          nullables.map(_.name), slots)(id = id)

      case _: CartesianProduct =>
        val argumentSize = physicalPlan.argumentSizes(plan.assignedId)
        val lhsPlan = plan.lhs.get
        val lhsSlots = slotConfigs(lhsPlan.assignedId)

        // Verify the assumption that the only shared slots we have are arguments which are identical on both lhs and rhs.
        // This assumption enables us to use array copy within CartesianProductSlottedPipe.
        ifAssertionsEnabled(verifyOnlyArgumentsAreSharedSlots(plan, physicalPlan))

        CartesianProductSlottedPipe(lhs, rhs, lhsSlots.numberOfLongs, lhsSlots.numberOfReferences, slots, argumentSize)(id)

      case joinPlan: NodeHashJoin =>
        val argumentSize = physicalPlan.argumentSizes(plan.assignedId)
        val leftNodes: Array[Int] = joinPlan.nodes.map(k => slots.getLongOffsetFor(k.name)).toArray
        val rhsSlots = slotConfigs(joinPlan.right.assignedId)
        val rightNodes: Array[Int] = joinPlan.nodes.map(k => rhsSlots.getLongOffsetFor(k.name)).toArray
        val copyLongsFromRHS = collection.mutable.ArrayBuffer.newBuilder[(Int,Int)]
        val copyRefsFromRHS = collection.mutable.ArrayBuffer.newBuilder[(Int,Int)]

        // Verify the assumption that the argument slots are the same on both sides
        ifAssertionsEnabled(verifyArgumentsAreTheSameOnBothSides(plan, physicalPlan))

        // When executing the HashJoin, the LHS will be copied to the first slots in the produced row, and any additional RHS columns that are not
        // part of the join comparison
        rhsSlots.foreachSlotOrdered {
          case (key, LongSlot(offset, _, _)) if offset >= argumentSize.nLongs =>
            copyLongsFromRHS += ((offset, slots.getLongOffsetFor(key)))
          case (key, RefSlot(offset, _, _)) if offset >= argumentSize.nReferences =>
            copyRefsFromRHS += ((offset, slots.getReferenceOffsetFor(key)))
        }
        NodeHashJoinSlottedPipe(leftNodes, rightNodes, lhs, rhs, slots, copyLongsFromRHS.result().toArray, copyRefsFromRHS.result().toArray)(id)

      case ValueHashJoin(lhsPlan, _, Equals(lhsAstExp, rhsAstExp)) =>
        val argumentSize = physicalPlan.argumentSizes(plan.assignedId)
        val lhsCmdExp = convertExpressions(lhsAstExp)
        val rhsCmdExp = convertExpressions(rhsAstExp)
        val lhsSlots = slotConfigs(lhsPlan.assignedId)
        val longOffset = lhsSlots.numberOfLongs
        val refOffset = lhsSlots.numberOfReferences

        // Verify the assumption that the only shared slots we have are arguments which are identical on both lhs and rhs.
        // This assumption enables us to use array copy within CartesianProductSlottedPipe.
        ifAssertionsEnabled(verifyOnlyArgumentsAreSharedSlots(plan, physicalPlan))

        ValueHashJoinSlottedPipe(lhsCmdExp, rhsCmdExp, lhs, rhs, slots, longOffset, refOffset, argumentSize)(id)

      case ConditionalApply(_, _, items) =>
        val (longIds , refIds) = items.partition(idName => slots.get(idName.name) match {
          case Some(s: LongSlot) => true
          case Some(s: RefSlot) => false
          case _ => throw new InternalException("We expect only an existing LongSlot or RefSlot here")
        })
        val longOffsets = longIds.map(e => slots.getLongOffsetFor(e.name))
        val refOffsets = refIds.map(e => slots.getReferenceOffsetFor(e.name))
        ConditionalApplySlottedPipe(lhs, rhs, longOffsets, refOffsets, negated = false, slots)(id)

      case AntiConditionalApply(_, _, items) =>
        val (longIds , refIds) = items.partition(idName => slots.get(idName.name) match {
          case Some(s: LongSlot) => true
          case Some(s: RefSlot) => false
          case _ => throw new InternalException("We expect only an existing LongSlot or RefSlot here")
        })
        val longOffsets = longIds.map(e => slots.getLongOffsetFor(e.name))
        val refOffsets = refIds.map(e => slots.getReferenceOffsetFor(e.name))
        ConditionalApplySlottedPipe(lhs, rhs, longOffsets, refOffsets, negated = true, slots)(id)

      case Union(_, _) =>
        val lhsSlots = slotConfigs(lhs.id)
        val rhsSlots = slotConfigs(rhs.id)
        UnionSlottedPipe(lhs, rhs,
          SlottedPipeBuilder.computeUnionMapping(lhsSlots, slots),
          SlottedPipeBuilder.computeUnionMapping(rhsSlots, slots))(id = id)

      case _ => throw new CantCompileQueryException(s"Unsupported logical plan operator: $plan")
    }
  }

  private def ifAssertionsEnabled(f: => Unit): Unit = {
    AssertionRunner.runUnderAssertion(new Thunk {
      override def apply() = f
    })
  }

  // Verifies the assumption that all shared slots are arguments with slot offsets within the first argument size number of slots
  // and the number of shared slots are identical to the argument size.
  private def verifyOnlyArgumentsAreSharedSlots(plan: LogicalPlan, physicalPlan: PhysicalPlan) = {
    val argumentSize = physicalPlan.argumentSizes(plan.assignedId)
    val lhsPlan = plan.lhs.get
    val rhsPlan = plan.rhs.get
    val lhsSlots = physicalPlan.slotConfigurations(lhsPlan.assignedId)
    val rhsSlots = physicalPlan.slotConfigurations(rhsPlan.assignedId)
    val (sharedSlots, rhsUniqueSlots) = rhsSlots.partitionSlots {
      case (k, slot) =>
        lhsSlots.get(k).isDefined
    }
    val (sharedLongSlots, sharedRefSlots) = sharedSlots.partition(_._2.isLongSlot)

    val longSlotsOk = sharedLongSlots.forall {
      case (key, slot) => slot.offset < argumentSize.nLongs
    } && sharedLongSlots.size == argumentSize.nLongs

    val refSlotsOk = sharedRefSlots.forall {
      case (key, slot) => slot.offset < argumentSize.nReferences
    } && sharedRefSlots.size == argumentSize.nReferences

    if (!longSlotsOk || !refSlotsOk) {
      val longSlotsMessage = if (longSlotsOk) "" else s"#long arguments=${argumentSize.nLongs} shared long slots: $sharedLongSlots "
      val refSlotsMessage = if (refSlotsOk) "" else s"#ref arguments=${argumentSize.nReferences} shared ref slots: $sharedRefSlots "
      throw new InternalException(s"Unexpected slot configuration. Shared slots not only within argument size: ${longSlotsMessage}${refSlotsMessage}")
    }
  }

  private def verifyArgumentsAreTheSameOnBothSides(plan: LogicalPlan, physicalPlan: PhysicalPlan) = {
    val argumentSize = physicalPlan.argumentSizes(plan.assignedId)
    val lhsPlan = plan.lhs.get
    val rhsPlan = plan.rhs.get
    val lhsSlots = physicalPlan.slotConfigurations(lhsPlan.assignedId)
    val rhsSlots = physicalPlan.slotConfigurations(rhsPlan.assignedId)
    val (lhsLongSlots, lhsRefSlots) = lhsSlots.partitionSlots((_, slot) => slot.isLongSlot)
    val (rhsLongSlots, rhsRefSlots) = rhsSlots.partitionSlots((_, slot) => slot.isLongSlot)

    val lhsArgLongSlots = lhsLongSlots.filter { case (_, slot) => slot.offset < argumentSize.nLongs }
    val lhsArgRefSlots = lhsRefSlots.filter { case (_, slot) => slot.offset < argumentSize.nReferences }
    val rhsArgLongSlots = rhsLongSlots.filter { case (_, slot) => slot.offset < argumentSize.nLongs }
    val rhsArgRefSlots = rhsRefSlots.filter { case (_, slot) => slot.offset < argumentSize.nReferences }

    val sizesAreTheSame = lhsArgLongSlots.size == rhsArgLongSlots.size && lhsArgLongSlots.size == argumentSize.nLongs &&
      lhsArgRefSlots.size == rhsArgRefSlots.size && lhsArgRefSlots.size == argumentSize.nReferences
    val longSlotsOk = sizesAreTheSame && lhsArgLongSlots.forall {
      case (k, slot) => {
        val (k2, slot2) = rhsArgLongSlots(slot.offset)
        k == k2 && slot.isTypeCompatibleWith(slot2)
      }
    }
    val refSlotsOk = sizesAreTheSame && lhsArgRefSlots.forall {
      case (k, slot) => {
        val (k2, slot2) = rhsArgRefSlots(slot.offset)
        k == k2 && slot.isTypeCompatibleWith(slot2)
      }
    }
    if (!longSlotsOk || !refSlotsOk) {
      val longSlotsMessage = if (longSlotsOk) "" else s"#long arguments=${argumentSize.nLongs} lhs: $lhsLongSlots rhs: $rhsArgLongSlots "
      val refSlotsMessage = if (refSlotsOk) "" else s"#ref arguments=${argumentSize.nReferences} lhs: $lhsRefSlots rhs: $rhsArgRefSlots "
      throw new InternalException(s"Unexpected slot configuration. Arguments differ between lhs and rhs: ${longSlotsMessage}${refSlotsMessage}")
    }
  }
}

object SlottedPipeBuilder {
  private def projectSlotExpression(slot: Slot): Expression = slot match {
    case LongSlot(offset, false, CTNode) =>
      slottedExpressions.NodeFromSlot(offset)
    case LongSlot(offset, true, CTNode) =>
      slottedExpressions.NullCheck(offset, slottedExpressions.NodeFromSlot(offset))
    case LongSlot(offset, false, CTRelationship) =>
      slottedExpressions.RelationshipFromSlot(offset)
    case LongSlot(offset, true, CTRelationship) =>
      slottedExpressions.NullCheck(offset, slottedExpressions.RelationshipFromSlot(offset))

    case RefSlot(offset, _, _) =>
      slottedExpressions.ReferenceFromSlot(offset)

    case _ =>
      throw new InternalException(s"Do not know how to project $slot")
  }

  type RowMapping = (ExecutionContext, QueryState) => ExecutionContext

  //compute mapping from incoming to outgoing pipe line, the slot order may differ
  //between the output and the input (lhs and rhs) and it may be the case that
  //we have a reference slot in the output but a long slot on one of the inputs,
  //e.g. MATCH (n) RETURN n UNION RETURN 42 AS n
  def computeUnionMapping(in: SlotConfiguration, out: SlotConfiguration): RowMapping = {
    val overlaps: Boolean = out.mapSlot {
      //For long slots we need to make sure both offset and types match
      //e.g we cannot allow mixing a node long slot with a relationship
      //longslot
      case (k, s: LongSlot) =>
        s == in.get(k).get
      //If both incoming and outgoing slots are refslot is is ok that types etc differs,
      // just make sure they have the same offset
      case (k, s: RefSlot) =>
        val inSlot = in.get(k).get
        inSlot.isInstanceOf[RefSlot] && s.offset == inSlot.offset

    }.forall(_ == true)

    //If we overlap we can just pass the result right through
    if (overlaps) {
      (incoming: ExecutionContext, _: QueryState) =>
        incoming
    }
    else {
    //find columns where output is a reference slot but where the input is a long slot

      val mapSlots: Iterable[(ExecutionContext, ExecutionContext, QueryState) => Unit] = out.mapSlot {
        case (k, v: LongSlot) =>
          val sourceOffset = in.getLongOffsetFor(k)
          (in, out, _) =>
            out.setLongAt(v.offset, in.getLongAt(sourceOffset))
        case (k, v: RefSlot) =>
          in.get(k).get match {
            case l: LongSlot => //here we must map the long slot to a reference slot
              val projectionExpression = projectSlotExpression(l) // Pre-compute projection expression
              (in, out, state) =>
                out.setRefAt(v.offset, projectionExpression(in, state))
            case _ =>
              val sourceOffset = in.getReferenceOffsetFor(k)
              (in, out, _) =>
                out.setRefAt(v.offset, in.getRefAt(sourceOffset))
          }
      }
      //Create a new context and apply all transformations
      (incoming: ExecutionContext, state: QueryState) =>
        val outgoing = PrimitiveExecutionContext(out)
        mapSlots.foreach(f => f(incoming, outgoing, state))
        outgoing
    }

  }

  def translateColumnOrder(slots: SlotConfiguration, s: plans.ColumnOrder): pipes.ColumnOrder = s match {
    case plans.Ascending(IdName(name)) =>
      slots.get(name) match {
        case Some(slot) => pipes.Ascending(slot)
        case None => throw new InternalException(s"Did not find `$name` in the pipeline information")
      }
    case plans.Descending(IdName(name)) =>
      slots.get(name) match {
        case Some(slot) => pipes.Descending(slot)
        case None => throw new InternalException(s"Did not find `$name` in the pipeline information")
      }
  }
}
