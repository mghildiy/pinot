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
package org.apache.pinot.core.query.aggregation.function;

import org.apache.pinot.common.CustomObject;
import org.apache.pinot.common.request.context.ExpressionContext;
import org.apache.pinot.common.utils.DataSchema.ColumnDataType;
import org.apache.pinot.core.common.BlockValSet;
import org.apache.pinot.core.common.ObjectSerDeUtils;
import org.apache.pinot.core.query.aggregation.groupby.GroupByResultHolder;
import org.apache.pinot.segment.local.customobject.StringLongPair;
import org.apache.pinot.segment.local.customobject.ValueLongPair;
import org.roaringbitmap.IntIterator;


/**
 * This function is used for FirstWithTime calculations for data column with string type.
 * <p>The function can be used as FirstWithTime(dataExpression, timeExpression, 'string')
 * <p>Following arguments are supported:
 * <ul>
 *   <li>dataExpression: expression that contains the string data column to be calculated first on</li>
 *   <li>timeExpression: expression that contains the column to be used to decide which data is first, can be any
 *   Numeric column</li>
 * </ul>
 */
public class FirstStringValueWithTimeAggregationFunction extends FirstWithTimeAggregationFunction<String> {
  private final static ValueLongPair<String> DEFAULT_VALUE_TIME_PAIR = new StringLongPair("", Long.MAX_VALUE);

  public FirstStringValueWithTimeAggregationFunction(ExpressionContext dataCol, ExpressionContext timeCol,
      boolean nullHandlingEnabled) {
    super(dataCol, timeCol, ObjectSerDeUtils.STRING_LONG_PAIR_SER_DE, nullHandlingEnabled);
  }

  @Override
  public ValueLongPair<String> constructValueLongPair(String value, long time) {
    return new StringLongPair(value, time);
  }

  @Override
  public ValueLongPair<String> getDefaultValueTimePair() {
    return DEFAULT_VALUE_TIME_PAIR;
  }

  @Override
  public String readCell(BlockValSet block, int docId) {
    return block.getStringValuesSV()[docId];
  }

  @Override
  public void aggregateGroupResultWithRawDataSv(int length, int[] groupKeyArray,
      GroupByResultHolder groupByResultHolder, BlockValSet blockValSet, BlockValSet timeValSet) {
    String[] stringValues = blockValSet.getStringValuesSV();
    long[] timeValues = timeValSet.getLongValuesSV();

    IntIterator nullIdxIterator = orNullIterator(blockValSet, timeValSet);
    forEachNotNull(length, nullIdxIterator, (from, to) -> {
      for (int i = from; i < to; i++) {
        String data = stringValues[i];
        long time = timeValues[i];
        setGroupByResult(groupKeyArray[i], groupByResultHolder, data, time);
      }
    });
  }

  @Override
  public void aggregateGroupResultWithRawDataMv(int length, int[][] groupKeysArray,
      GroupByResultHolder groupByResultHolder, BlockValSet blockValSet, BlockValSet timeValSet) {
    String[] stringValues = blockValSet.getStringValuesSV();
    long[] timeValues = timeValSet.getLongValuesSV();

    IntIterator nullIdxIterator = orNullIterator(blockValSet, timeValSet);
    forEachNotNull(length, nullIdxIterator, (from, to) -> {
      for (int i = from; i < to; i++) {
        String value = stringValues[i];
        long time = timeValues[i];
        for (int groupKey : groupKeysArray[i]) {
          setGroupByResult(groupKey, groupByResultHolder, value, time);
        }
      }
    });
  }

  @Override
  public String getResultColumnName() {
    return getType().getName().toLowerCase() + "(" + _expression + "," + _timeCol + ",'STRING')";
  }

  @Override
  public SerializedIntermediateResult serializeIntermediateResult(ValueLongPair<String> stringLongPair) {
    return new SerializedIntermediateResult(ObjectSerDeUtils.ObjectType.StringLongPair.getValue(),
        ObjectSerDeUtils.STRING_LONG_PAIR_SER_DE.serialize((StringLongPair) stringLongPair));
  }

  @Override
  public ValueLongPair<String> deserializeIntermediateResult(CustomObject customObject) {
    return ObjectSerDeUtils.STRING_LONG_PAIR_SER_DE.deserialize(customObject.getBuffer());
  }

  @Override
  public ColumnDataType getFinalResultColumnType() {
    return ColumnDataType.STRING;
  }
}
