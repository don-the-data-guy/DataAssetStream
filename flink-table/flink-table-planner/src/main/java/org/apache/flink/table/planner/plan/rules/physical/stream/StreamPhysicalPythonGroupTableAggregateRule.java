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

package org.apache.flink.table.planner.plan.rules.physical.stream;

import org.apache.flink.table.api.TableException;
import org.apache.flink.table.functions.python.PythonFunctionKind;
import org.apache.flink.table.planner.plan.nodes.FlinkConventions;
import org.apache.flink.table.planner.plan.nodes.logical.FlinkLogicalTableAggregate;
import org.apache.flink.table.planner.plan.nodes.physical.stream.StreamPhysicalPythonGroupTableAggregate;
import org.apache.flink.table.planner.plan.trait.FlinkRelDistribution;
import org.apache.flink.table.planner.plan.utils.PythonUtil;
import org.apache.flink.table.planner.utils.JavaScalaConversionUtil;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.AggregateCall;

import java.util.List;

/**
 * Rule to convert a {@link FlinkLogicalTableAggregate} into a {@link
 * StreamPhysicalPythonGroupTableAggregateRule}.
 */
public class StreamPhysicalPythonGroupTableAggregateRule extends ConverterRule {

    public static final RelOptRule INSTANCE =
            new StreamPhysicalPythonGroupTableAggregateRule(
                    Config.INSTANCE
                            .withConversion(
                                    FlinkLogicalTableAggregate.class,
                                    FlinkConventions.LOGICAL(),
                                    FlinkConventions.STREAM_PHYSICAL(),
                                    "StreamPhysicalPythonGroupTableAggregateRule")
                            .withRuleFactory(StreamPhysicalPythonGroupTableAggregateRule::new));

    public StreamPhysicalPythonGroupTableAggregateRule(Config config) {
        super(config);
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
        FlinkLogicalTableAggregate agg = call.rel(0);

        List<AggregateCall> aggCalls = agg.getAggCallList();
        boolean existGeneralPythonFunction =
                aggCalls.stream()
                        .anyMatch(x -> PythonUtil.isPythonAggregate(x, PythonFunctionKind.GENERAL));
        boolean existPandasFunction =
                aggCalls.stream()
                        .anyMatch(x -> PythonUtil.isPythonAggregate(x, PythonFunctionKind.PANDAS));
        boolean existJavaUserDefinedFunction =
                aggCalls.stream()
                        .anyMatch(
                                x ->
                                        !PythonUtil.isPythonAggregate(x, null)
                                                && !PythonUtil.isBuiltInAggregate(x));
        if (existPandasFunction || existGeneralPythonFunction) {
            if (existPandasFunction) {
                throw new TableException(
                        "Pandas UDAFs are not supported in streaming mode currently.");
            }
            if (existJavaUserDefinedFunction) {
                throw new TableException(
                        "Python UDAF and Java/Scala UDAF cannot be used together.");
            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    public RelNode convert(RelNode rel) {
        FlinkLogicalTableAggregate agg = (FlinkLogicalTableAggregate) rel;
        FlinkRelDistribution requiredDistribution;
        if (agg.getGroupSet().cardinality() != 0) {
            requiredDistribution = FlinkRelDistribution.hash(agg.getGroupSet().asList(), true);
        } else {
            requiredDistribution = FlinkRelDistribution.SINGLETON();
        }

        RelTraitSet requiredTraitSet =
                rel.getCluster()
                        .getPlanner()
                        .emptyTraitSet()
                        .replace(requiredDistribution)
                        .replace(FlinkConventions.STREAM_PHYSICAL());

        RelTraitSet providedTraitSet =
                rel.getTraitSet().replace(FlinkConventions.STREAM_PHYSICAL());
        RelNode newInput = RelOptRule.convert(agg.getInput(), requiredTraitSet);

        return new StreamPhysicalPythonGroupTableAggregate(
                rel.getCluster(),
                providedTraitSet,
                newInput,
                rel.getRowType(),
                agg.getGroupSet().toArray(),
                JavaScalaConversionUtil.toScala(agg.getAggCallList()));
    }
}
