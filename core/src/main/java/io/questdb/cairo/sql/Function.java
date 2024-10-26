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

package io.questdb.cairo.sql;

import io.questdb.cairo.ColumnType;
import io.questdb.cairo.TableUtils;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.Plannable;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.std.BinarySequence;
import io.questdb.std.DeepCloneable;
import io.questdb.std.Interval;
import io.questdb.std.Long256;
import io.questdb.std.ObjList;
import io.questdb.std.str.CharSink;
import io.questdb.std.str.Utf8Sequence;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;


public interface Function extends Closeable, StatefulAtom, Plannable, DeepCloneable<Function> {

    Map<Class<?>, Object> DEFAULT_PRIMITIVE_VALUES = Map.of(
            boolean.class, false, char.class,
            (char) 0, double.class, (double) 0, float.class, (float) 0, byte.class, (byte) 0,
            short.class, (short) 0, int.class, 0, long.class, (long) 0
    );

    static void init(
            ObjList<? extends Function> args,
            SymbolTableSource symbolTableSource,
            SqlExecutionContext executionContext
    ) throws SqlException {
        for (int i = 0, n = args.size(); i < n; i++) {
            args.getQuick(i).init(symbolTableSource, executionContext);
        }
    }

    static void initCursor(ObjList<? extends Function> args) {
        for (int i = 0, n = args.size(); i < n; i++) {
            args.getQuick(i).initCursor();
        }
    }

    static void initNc(
            ObjList<? extends Function> args,
            SymbolTableSource symbolTableSource,
            SqlExecutionContext executionContext
    ) throws SqlException {
        if (args != null) {
            init(args, symbolTableSource, executionContext);
        }
    }

    static void initNcFunctions(
            ObjList<? extends Function> args,
            SymbolTableSource symbolTableSource,
            SqlExecutionContext executionContext
    ) throws SqlException {
        if (args != null) {
            for (int i = 0, n = args.size(); i < n; i++) {
                final Function arg = args.getQuiet(i);
                if (arg != null) {
                    arg.init(symbolTableSource, executionContext);
                }
            }
        }
    }

    default void assignType(int type, BindVariableService bindVariableService) throws SqlException {
        throw new UnsupportedOperationException();
    }

    @Override
    default void close() {
    }

    default void cursorClosed() {
    }

    int getArrayLength();

    BinarySequence getBin(Record rec);

    long getBinLen(Record rec);

    boolean getBool(Record rec);

    byte getByte(Record rec);

    char getChar(Record rec);

    long getDate(Record rec);

    double getDouble(Record rec);

    float getFloat(Record rec);

    byte getGeoByte(Record rec);

    int getGeoInt(Record rec);

    long getGeoLong(Record rec);

    short getGeoShort(Record rec);

    int getIPv4(Record rec);

    int getInt(Record rec);

    @NotNull
    Interval getInterval(Record rec);

    long getLong(Record rec);

    long getLong128Hi(Record rec);

    long getLong128Lo(Record rec);

    void getLong256(Record rec, CharSink<?> sink);

    Long256 getLong256A(Record rec);

    Long256 getLong256B(Record rec);

    default RecordMetadata getMetadata() {
        return null;
    }

    /**
     * @return function name or symbol, e.g. concat or + .
     * r=
     */
    default String getName() {
        return getClass().getName();
    }

    // function returns a record of values
    Record getRecord(Record rec);

    // when function returns factory it becomes factory
    // on other words this is not a tear-away instance
    RecordCursorFactory getRecordCursorFactory();

    short getShort(Record rec);

    CharSequence getStrA(Record rec);

    CharSequence getStrA(Record rec, int arrayIndex);

    CharSequence getStrB(Record rec);

    CharSequence getStrB(Record rec, int arrayIndex);

    int getStrLen(Record rec);

    int getStrLen(Record rec, int arrayIndex);

    CharSequence getSymbol(Record rec);

    CharSequence getSymbolB(Record rec);

    long getTimestamp(Record rec);

    int getType();

    @Nullable
    Utf8Sequence getVarcharA(Record rec);

    @Nullable
    Utf8Sequence getVarcharB(Record rec);

    /**
     * @return size of the varchar value or {@link TableUtils#NULL_LEN} in case of NULL
     */
    int getVarcharSize(Record rec);

    /**
     * Returns true if function is constant, i.e. its value does not require
     * any input from the record.
     *
     * @return true if function is constant
     * @see #isRuntimeConstant()
     */
    default boolean isConstant() {
        return false;
    }

    default boolean isNullConstant() {
        return false;
    }

    // used in generic toSink implementations
    default boolean isOperator() {
        return false;
    }

    /**
     * Declares that the function will maintain its value for all the rows during
     * {@link RecordCursor} traversal. However, between cursor traversals the function
     * value is liable to change.
     * <p>
     * In practice this means that function arguments that are runtime constants can be
     * evaluated in the functions {@link #init(ObjList, SymbolTableSource, SqlExecutionContext)} call.
     * <p>
     * It has be noted that the function cannot be both {@link #isConstant()} and runtime constant.
     *
     * @return true when function is runtime constant.
     */
    default boolean isRuntimeConstant() {
        return false;
    }

    /**
     * Returns true if the function and all of its children functions are thread-safe
     * and, thus, can be called concurrently, false - otherwise. Used as a hint for
     * parallel SQL execution, thus this method makes sense only for functions
     * that are allowed in WHERE or GROUP BY clause.
     * <p>
     * In case of non-aggregate functions this flag means read thread-safety.
     * For decomposable (think, parallel) aggregate functions
     * ({@link io.questdb.griffin.engine.functions.GroupByFunction})
     * it means write thread-safety, i.e. whether it's safe to use single function
     * instance concurrently to aggregate across multiple threads.
     * <p>
     * If the function is not read thread-safe, it gets cloned for each worker thread.
     *
     * @return true if the function and all of its children functions are thread-safe
     */
    default boolean isThreadSafe() {
        return false;
    }

    default boolean isUndefined() {
        return getType() == ColumnType.UNDEFINED;
    }

    /**
     * DeepClone does not clone the running-states, only the init states of the function,
     * must be called before Function::init.
     * Used for parallel filter/groupBy execution, so that avoid Function::parser for every worker.
     * Note: CursorFunction and WindowFunction are not supported now.
     *
     * @return deep clones of the function
     */
    default Function deepClone() {
        try {
            Class<?> cls = this.getClass();
            Constructor<?> funcCons = cls.getDeclaredConstructors()[0];
            funcCons.setAccessible(true);
            int parameterCount = funcCons.getParameterCount();
            Object[] pArgs = new Object[parameterCount];
            for (int i = 0; i < parameterCount; i++) {
                pArgs[i] = DEFAULT_PRIMITIVE_VALUES.get(funcCons.getParameterTypes()[i]);
            }
            Object cloneFunc = funcCons.newInstance(pArgs);

            while (cls != null) {
                for (Field field : cls.getDeclaredFields()) {
                    Class<?> fType = field.getType();
                    field.setAccessible(true);
                    if (field.get(this) == null || Modifier.isStatic(field.getModifiers()) || (field.get(cloneFunc) != null && !fType.isPrimitive())) {
                        continue;
                    }
                    if (fType.isAssignableFrom(DeepCloneable.class)) {
                        field.set(cloneFunc, ((DeepCloneable<?>) field.get(this)).deepClone());
                    } else {
                        field.set(cloneFunc, field.get(this));
                    }
                }
                cls = cls.getSuperclass();
            }
            return (Function) cloneFunc;
        } catch (Throwable e) {
            throw new UnsupportedOperationException(e);
        }
    }

    /**
     * Returns true if the function supports parallel execution, e.g. parallel filter
     * or GROUP BY. If the method returns false, single-threaded execution plan
     * must be chosen for the query.
     * <p>
     * Examples of parallelizable, but thread-unsafe function are regexp_replace() or min(str).
     * These functions need to maintain a char sink, so they can't be accessed concurrently.
     * On the other hand, regexp_replace() can be used in parallel filter and min(str)
     * supports parallel aggregation and subsequent merge.
     *
     * @return true if the function and all of its children functions support parallel execution
     */
    default boolean supportsParallelism() {
        return true;
    }

    default boolean supportsRandomAccess() {
        return true;
    }

    @Override
    default void toPlan(PlanSink sink) {
        sink.val(getName()).val("()");
    }

    default void toTop() {
        // no-op
    }
}
