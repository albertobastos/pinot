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
package org.apache.pinot.query.runtime.operator.operands;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.pinot.common.function.FunctionInfo;
import org.apache.pinot.common.function.FunctionRegistry;
import org.apache.pinot.common.function.FunctionUtils;
import org.apache.pinot.common.function.QueryFunctionInvoker;
import org.apache.pinot.common.utils.DataSchema;
import org.apache.pinot.common.utils.DataSchema.ColumnDataType;
import org.apache.pinot.common.utils.PinotDataType;
import org.apache.pinot.query.planner.logical.RexExpression;
import org.apache.pinot.query.runtime.operator.utils.TypeUtils;


/*
 * FunctionOperands are generated from {@link RexExpression}s.
 */
public class FunctionOperand implements TransformOperand {
  private final ColumnDataType _resultType;
  private final QueryFunctionInvoker _functionInvoker;
  private final ColumnDataType _functionInvokerResultType;
  private final List<TransformOperand> _operands;
  private final Object[] _reusableOperandHolder;

  public FunctionOperand(RexExpression.FunctionCall functionCall, DataSchema dataSchema) {
    _resultType = functionCall.getDataType();
    List<RexExpression> operands = functionCall.getFunctionOperands();
    int numOperands = operands.size();
    ColumnDataType[] argumentTypes = new ColumnDataType[numOperands];
    for (int i = 0; i < numOperands; i++) {
      RexExpression operand = operands.get(i);
      ColumnDataType argumentType;
      if (operand instanceof RexExpression.InputRef) {
        argumentType = dataSchema.getColumnDataType(((RexExpression.InputRef) operand).getIndex());
      } else if (operand instanceof RexExpression.Literal) {
        argumentType = ((RexExpression.Literal) operand).getDataType();
      } else {
        assert operand instanceof RexExpression.FunctionCall;
        argumentType = ((RexExpression.FunctionCall) operand).getDataType();
      }
      argumentTypes[i] = argumentType;
    }
    String functionName = functionCall.getFunctionName();
    String canonicalName = FunctionRegistry.canonicalize(functionName);
    FunctionInfo functionInfo = FunctionRegistry.lookupFunctionInfo(canonicalName, argumentTypes);
    if (functionInfo == null) {
      if (FunctionRegistry.contains(canonicalName)) {
        throw new IllegalArgumentException(
            String.format("Unsupported function: %s with argument types: %s", functionName,
                Arrays.toString(argumentTypes)));
      } else {
        throw new IllegalArgumentException(String.format("Unsupported function: %s", functionName));
      }
    }
    _functionInvoker = new QueryFunctionInvoker(functionInfo);
    if (!_functionInvoker.getMethod().isVarArgs()) {
      Class<?>[] parameterClasses = _functionInvoker.getParameterClasses();
      PinotDataType[] parameterTypes = _functionInvoker.getParameterTypes();
      for (int i = 0; i < numOperands; i++) {
        Preconditions.checkState(parameterTypes[i] != null, "Unsupported parameter class: %s for method: %s",
            parameterClasses[i], functionInfo.getMethod());
      }
    }
    ColumnDataType functionInvokerResultType = FunctionUtils.getColumnDataType(_functionInvoker.getResultClass());
    // Handle unrecognized result class with STRING
    _functionInvokerResultType = functionInvokerResultType != null ? functionInvokerResultType : ColumnDataType.STRING;
    _operands = new ArrayList<>(numOperands);
    for (RexExpression operand : operands) {
      _operands.add(TransformOperandFactory.getTransformOperand(operand, dataSchema));
    }
    _reusableOperandHolder = new Object[numOperands];
  }

  @Override
  public ColumnDataType getResultType() {
    return _resultType;
  }

  @Nullable
  @Override
  public Object apply(List<Object> row) {
    for (int i = 0; i < _operands.size(); i++) {
      TransformOperand operand = _operands.get(i);
      Object value = operand.apply(row);
      _reusableOperandHolder[i] = value != null ? operand.getResultType().toExternal(value) : null;
    }
    // TODO: Optimize per record conversion
    Object result;
    if (_functionInvoker.getMethod().isVarArgs()) {
      result = _functionInvoker.invoke(new Object[]{_reusableOperandHolder});
    } else {
      _functionInvoker.convertTypes(_reusableOperandHolder);
      result = _functionInvoker.invoke(_reusableOperandHolder);
    }
    return result != null ? TypeUtils.convert(_functionInvokerResultType.toInternal(result),
        _resultType.getStoredType()) : null;
  }
}
