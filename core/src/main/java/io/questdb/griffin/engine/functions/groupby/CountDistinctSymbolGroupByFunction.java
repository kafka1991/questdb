/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.functions.groupby;

import io.questdb.cairo.ArrayColumnTypes;
import io.questdb.cairo.ColumnType;
import io.questdb.cairo.map.MapValue;
import io.questdb.cairo.sql.Function;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.StaticSymbolTable;
import io.questdb.cairo.sql.SymbolTableSource;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.engine.functions.GroupByFunction;
import io.questdb.griffin.engine.functions.LongFunction;
import io.questdb.griffin.engine.functions.UnaryFunction;
import io.questdb.griffin.engine.functions.columns.SymbolColumn;
import io.questdb.griffin.engine.groupby.GroupByAllocator;
import io.questdb.griffin.engine.groupby.GroupByIntHashSet;
import io.questdb.std.Numbers;

import static io.questdb.cairo.sql.SymbolTable.VALUE_IS_NULL;

public class CountDistinctSymbolGroupByFunction extends LongFunction implements UnaryFunction, GroupByFunction {
    private final Function arg;
    private final GroupByIntHashSet setA;
    private final GroupByIntHashSet setB;
    private int knownSymbolCount = -1;
    private int valueIndex;

    public CountDistinctSymbolGroupByFunction(Function arg, int setInitialCapacity, double setLoadFactor) {
        this.arg = arg;
        this.setA = new GroupByIntHashSet(setInitialCapacity, setLoadFactor, VALUE_IS_NULL);
        this.setB = new GroupByIntHashSet(setInitialCapacity, setLoadFactor, VALUE_IS_NULL);
    }

    @Override
    public void clear() {
        setA.resetPtr();
        setB.resetPtr();
        knownSymbolCount = -1;
    }

    @Override
    public void computeFirst(MapValue mapValue, Record record, long rowId) {
        final int key = arg.getInt(record);
        if (key != VALUE_IS_NULL) {
            mapValue.putLong(valueIndex, 1);
            setA.of(0).add(key);
            mapValue.putLong(valueIndex + 1, setA.ptr());
        } else {
            mapValue.putLong(valueIndex, 0);
            mapValue.putLong(valueIndex + 1, 0);
        }
    }

    @Override
    public void computeNext(MapValue mapValue, Record record, long rowId) {
        final int key = arg.getInt(record);
        if (key != VALUE_IS_NULL) {
            long ptr = mapValue.getLong(valueIndex + 1);
            final long index = setA.of(ptr).keyIndex(key);
            if (index >= 0) {
                setA.addAt(index, key);
                mapValue.addLong(valueIndex, 1);
                mapValue.putLong(valueIndex + 1, setA.ptr());
            }
        }
    }

    @Override
    public boolean earlyExit(MapValue mapValue) {
        // Fast path for the case when we've reached total number of symbols.
        return knownSymbolCount != -1 && mapValue.getLong(valueIndex) == knownSymbolCount;
    }

    @Override
    public Function getArg() {
        return arg;
    }

    @Override
    public long getLong(Record rec) {
        return rec.getLong(valueIndex);
    }

    @Override
    public String getName() {
        return "count_distinct";
    }

    @Override
    public int getValueIndex() {
        return valueIndex;
    }

    @Override
    public void init(SymbolTableSource symbolTableSource, SqlExecutionContext executionContext) throws SqlException {
        arg.init(symbolTableSource, executionContext);
        knownSymbolCount = -1;
        if (arg instanceof SymbolColumn) {
            final SymbolColumn argCol = (SymbolColumn) arg;
            final StaticSymbolTable symbolTable = argCol.getStaticSymbolTable();
            if (symbolTable != null) {
                knownSymbolCount = symbolTable.getSymbolCount();
            }
        }
    }

    @Override
    public void initValueIndex(int valueIndex) {
        this.valueIndex = valueIndex;
    }

    @Override
    public void initValueTypes(ArrayColumnTypes columnTypes) {
        this.valueIndex = columnTypes.getColumnCount();
        columnTypes.add(ColumnType.LONG); // count
        columnTypes.add(ColumnType.LONG); // GroupByIntHashSet pointer
    }

    @Override
    public boolean isConstant() {
        return false;
    }

    @Override
    public boolean isEarlyExitSupported() {
        return true;
    }

    @Override
    public boolean isThreadSafe() {
        return false;
    }

    @Override
    public void merge(MapValue destValue, MapValue srcValue) {
        long srcCount = srcValue.getLong(valueIndex);
        if (srcCount == 0 || srcCount == Numbers.LONG_NULL) {
            return;
        }
        long srcPtr = srcValue.getLong(valueIndex + 1);

        long destCount = destValue.getLong(valueIndex);
        if (destCount == 0 || destCount == Numbers.LONG_NULL) {
            destValue.putLong(valueIndex, srcCount);
            destValue.putLong(valueIndex + 1, srcPtr);
            return;
        }
        long destPtr = destValue.getLong(valueIndex + 1);

        setA.of(destPtr);
        setB.of(srcPtr);

        if (setA.size() > (setB.size() >> 1)) {
            setA.merge(setB);
            destValue.putLong(valueIndex, setA.size());
            destValue.putLong(valueIndex + 1, setA.ptr());
        } else {
            // Set A is significantly smaller than set B, so we merge it into set B.
            setB.merge(setA);
            destValue.putLong(valueIndex, setB.size());
            destValue.putLong(valueIndex + 1, setB.ptr());
        }
    }

    @Override
    public void setAllocator(GroupByAllocator allocator) {
        setA.setAllocator(allocator);
        setB.setAllocator(allocator);
    }

    @Override
    public void setEmpty(MapValue mapValue) {
        mapValue.putLong(valueIndex, 0);
        mapValue.putLong(valueIndex + 1, 0);
    }

    @Override
    public void setLong(MapValue mapValue, long value) {
        mapValue.putLong(valueIndex, value);
        mapValue.putLong(valueIndex + 1, 0);
    }

    @Override
    public void setNull(MapValue mapValue) {
        mapValue.putLong(valueIndex, Numbers.LONG_NULL);
        mapValue.putLong(valueIndex + 1, 0);
    }

    @Override
    public boolean supportsParallelism() {
        return UnaryFunction.super.supportsParallelism();
    }

    @Override
    public void toTop() {
        UnaryFunction.super.toTop();
    }

    @Override
    public Function deepClone() {
        return new CountDistinctSymbolGroupByFunction(arg.deepClone(), new GroupByIntHashSet(setA), new GroupByIntHashSet(setB));
    }

    private CountDistinctSymbolGroupByFunction(Function arg, GroupByIntHashSet setA, GroupByIntHashSet setB) {
        this.arg = arg;
        this.setA = setA;
        this.setB = setB;
    }
}
