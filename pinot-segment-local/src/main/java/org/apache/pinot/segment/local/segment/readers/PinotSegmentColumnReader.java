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
package org.apache.pinot.segment.local.segment.readers;

import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.IOException;
import javax.annotation.Nullable;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.pinot.segment.spi.IndexSegment;
import org.apache.pinot.segment.spi.datasource.DataSource;
import org.apache.pinot.segment.spi.index.reader.Dictionary;
import org.apache.pinot.segment.spi.index.reader.ForwardIndexReader;
import org.apache.pinot.segment.spi.index.reader.ForwardIndexReaderContext;
import org.apache.pinot.segment.spi.index.reader.NullValueVectorReader;
import org.apache.pinot.spi.data.FieldSpec.DataType;


@SuppressWarnings({"rawtypes", "unchecked"})
public class PinotSegmentColumnReader implements Closeable {
  private final ForwardIndexReader _forwardIndexReader;
  private final ForwardIndexReaderContext _forwardIndexReaderContext;
  private final Dictionary _dictionary;
  private final NullValueVectorReader _nullValueVectorReader;
  private final int[] _dictIdBuffer;

  public PinotSegmentColumnReader(IndexSegment indexSegment, String column) {
    DataSource dataSource = indexSegment.getDataSource(column);
    _forwardIndexReader = dataSource.getForwardIndex();
    Preconditions.checkArgument(_forwardIndexReader != null, "Forward index disabled for column: %s", column);
    _forwardIndexReaderContext = _forwardIndexReader.createContext();
    _dictionary = dataSource.getDictionary();
    _nullValueVectorReader = dataSource.getNullValueVector();
    if (_forwardIndexReader.isSingleValue()) {
      _dictIdBuffer = null;
    } else {
      int maxNumValuesPerMVEntry = dataSource.getDataSourceMetadata().getMaxNumValuesPerMVEntry();
      Preconditions.checkState(maxNumValuesPerMVEntry >= 0, "maxNumValuesPerMVEntry is negative for an MV column.");
      _dictIdBuffer = new int[maxNumValuesPerMVEntry];
    }
  }

  public PinotSegmentColumnReader(ForwardIndexReader forwardIndexReader, @Nullable Dictionary dictionary,
      @Nullable NullValueVectorReader nullValueVectorReader, int maxNumValuesPerMVEntry) {
    _forwardIndexReader = forwardIndexReader;
    _forwardIndexReaderContext = _forwardIndexReader.createContext();
    _dictionary = dictionary;
    _nullValueVectorReader = nullValueVectorReader;
    if (_forwardIndexReader.isSingleValue()) {
      _dictIdBuffer = null;
    } else {
      _dictIdBuffer = new int[maxNumValuesPerMVEntry];
    }
  }

  public boolean isSingleValue() {
    return _forwardIndexReader.isSingleValue();
  }

  public boolean hasDictionary() {
    return _dictionary != null;
  }

  public Dictionary getDictionary() {
    return _dictionary;
  }

  public int getDictId(int docId) {
    return _forwardIndexReader.getDictId(docId, _forwardIndexReaderContext);
  }

  public Object getValue(int docId) {
    if (_dictionary != null) {
      // Dictionary based
      if (_forwardIndexReader.isSingleValue()) {
        return _dictionary.get(_forwardIndexReader.getDictId(docId, _forwardIndexReaderContext));
      } else {
        int numValues = _forwardIndexReader.getDictIdMV(docId, _dictIdBuffer, _forwardIndexReaderContext);
        DataType storedType = _dictionary.getValueType();
        switch (storedType) {
          case INT: {
            Integer[] values = new Integer[numValues];
            _dictionary.readIntValues(_dictIdBuffer, numValues, values);
            return values;
          }
          case LONG: {
            Long[] values = new Long[numValues];
            _dictionary.readLongValues(_dictIdBuffer, numValues, values);
            return values;
          }
          case FLOAT: {
            Float[] values = new Float[numValues];
            _dictionary.readFloatValues(_dictIdBuffer, numValues, values);
            return values;
          }
          case DOUBLE: {
            Double[] values = new Double[numValues];
            _dictionary.readDoubleValues(_dictIdBuffer, numValues, values);
            return values;
          }
          case STRING: {
            String[] values = new String[numValues];
            _dictionary.readStringValues(_dictIdBuffer, numValues, values);
            return values;
          }
          case BYTES: {
            byte[][] values = new byte[numValues][];
            _dictionary.readBytesValues(_dictIdBuffer, numValues, values);
            return values;
          }
          default:
            throw new IllegalStateException("Unsupported MV type: " + storedType);
        }
      }
    } else {
      // Raw index based
      DataType storedType = _forwardIndexReader.getStoredType();
      if (_forwardIndexReader.isSingleValue()) {
        switch (storedType) {
          case INT:
            return _forwardIndexReader.getInt(docId, _forwardIndexReaderContext);
          case LONG:
            return _forwardIndexReader.getLong(docId, _forwardIndexReaderContext);
          case FLOAT:
            return _forwardIndexReader.getFloat(docId, _forwardIndexReaderContext);
          case DOUBLE:
            return _forwardIndexReader.getDouble(docId, _forwardIndexReaderContext);
          case BIG_DECIMAL:
            return _forwardIndexReader.getBigDecimal(docId, _forwardIndexReaderContext);
          case STRING:
            return _forwardIndexReader.getString(docId, _forwardIndexReaderContext);
          case BYTES:
            return _forwardIndexReader.getBytes(docId, _forwardIndexReaderContext);
          case MAP:
            return _forwardIndexReader.getMap(docId, _forwardIndexReaderContext);
          default:
            throw new IllegalStateException("Unsupported SV type: " + storedType);
        }
      } else {
        switch (storedType) {
          case INT:
            return ArrayUtils.toObject(_forwardIndexReader.getIntMV(docId, _forwardIndexReaderContext));
          case LONG:
            return ArrayUtils.toObject(_forwardIndexReader.getLongMV(docId, _forwardIndexReaderContext));
          case FLOAT:
            return ArrayUtils.toObject(_forwardIndexReader.getFloatMV(docId, _forwardIndexReaderContext));
          case DOUBLE:
            return ArrayUtils.toObject(_forwardIndexReader.getDoubleMV(docId, _forwardIndexReaderContext));
          case STRING:
            return _forwardIndexReader.getStringMV(docId, _forwardIndexReaderContext);
          case BYTES:
            return _forwardIndexReader.getBytesMV(docId, _forwardIndexReaderContext);
          default:
            throw new IllegalStateException("Unsupported MV type: " + storedType);
        }
      }
    }
  }

  public boolean isNull(int docId) {
    return _nullValueVectorReader != null && _nullValueVectorReader.isNull(docId);
  }

  @Override
  public void close()
      throws IOException {
    if (_forwardIndexReaderContext != null) {
      _forwardIndexReaderContext.close();
    }
  }
}
