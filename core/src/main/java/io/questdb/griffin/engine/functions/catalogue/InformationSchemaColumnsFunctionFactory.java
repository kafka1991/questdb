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

package io.questdb.griffin.engine.functions.catalogue;

import io.questdb.cairo.AbstractRecordCursorFactory;
import io.questdb.cairo.CairoColumn;
import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.CairoTable;
import io.questdb.cairo.ColumnType;
import io.questdb.cairo.GenericRecordMetadata;
import io.questdb.cairo.MetadataCacheReader;
import io.questdb.cairo.TableColumnMetadata;
import io.questdb.cairo.sql.Function;
import io.questdb.cairo.sql.NoRandomAccessRecordCursor;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.griffin.FunctionFactory;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.engine.functions.CursorFunction;
import io.questdb.std.CharSequenceObjHashMap;
import io.questdb.std.IntList;
import io.questdb.std.ObjList;

public class InformationSchemaColumnsFunctionFactory implements FunctionFactory {
    public static final RecordMetadata METADATA;
    public static final String SIGNATURE = "information_schema.columns()";


    @Override
    public String getSignature() {
        return SIGNATURE;
    }

    @Override
    public boolean isRuntimeConstant() {
        return true;
    }

    @Override
    public Function newInstance(
            int position,
            ObjList<Function> args,
            IntList argPositions,
            CairoConfiguration configuration,
            SqlExecutionContext sqlExecutionContext
    ) {
        return new CursorFunction(new ColumnsCursorFactory());
    }


    private static class ColumnsCursorFactory extends AbstractRecordCursorFactory {
        private final ColumnRecordCursor cursor;
        private final CharSequenceObjHashMap<CairoTable> tableCache = new CharSequenceObjHashMap<>();
        private long tableCacheVersion = -1;

        private ColumnsCursorFactory() {
            super(METADATA);
            this.cursor = new ColumnRecordCursor(tableCache);
        }

        @Override
        public RecordCursor getCursor(SqlExecutionContext executionContext) {
            final CairoEngine engine = executionContext.getCairoEngine();
            try (MetadataCacheReader metadataRO = engine.getMetadataCache().readLock()) {
                tableCacheVersion = metadataRO.snapshot(tableCache, tableCacheVersion);
            }
            cursor.toTop();
            return cursor;
        }

        @Override
        public boolean recordCursorSupportsRandomAccess() {
            return false;
        }

        @Override
        public void toPlan(PlanSink sink) {
            sink.type(SIGNATURE);
        }

        private static class ColumnRecordCursor implements NoRandomAccessRecordCursor {
            private final ColumnsRecord record = new ColumnsRecord();
            private final CharSequenceObjHashMap<CairoTable> tableCache;
            private int columnIdx;
            private int iteratorIdx;
            private CairoTable table;

            private ColumnRecordCursor(CharSequenceObjHashMap<CairoTable> tableCache) {
                this.tableCache = tableCache;
            }

            @Override
            public void close() {
                columnIdx = -1;
                iteratorIdx = -1;
            }

            @Override
            public Record getRecord() {
                return record;
            }

            @Override
            public boolean hasNext() {

                if (table == null) {
                    if (!nextTable()) {
                        return false;
                    }
                }

                assert table != null;
                // we have a table

                if (table == null) {
                    throw new RuntimeException();
                }

                if (columnIdx < table.getColumnCount() - 1) {
                    columnIdx++;
                } else {
                    columnIdx = -1;
                    table = null;
                    return hasNext();
                }

                CairoColumn column = table.getColumnQuiet(columnIdx);
                assert column != null;

                record.of(table.getTableName(), columnIdx, column.getName(), ColumnType.nameOf(column.getType()));

                return true;
            }

            public boolean nextTable() {
                assert table == null;

                if (iteratorIdx < tableCache.size() - 1) {
                    table = tableCache.getAt(++iteratorIdx);
                } else return false;

                return true;
            }

            @Override
            public long size() {
                return -1;
            }

            @Override
            public void toTop() {
                columnIdx = -1;
                table = null;
                iteratorIdx = -1;
            }

            private static class ColumnsRecord implements Record {
                private CharSequence columnName;
                private CharSequence dataType;
                private int ordinalPosition;
                private CharSequence tableName;

                @Override
                public int getInt(int col) {
                    return ordinalPosition;
                }

                @Override
                public CharSequence getStrA(int col) {
                    switch (col) {
                        case 0:
                            return tableName;
                        case 2:
                            return columnName;
                        case 3:
                            return dataType;
                    }
                    return null;
                }

                @Override
                public CharSequence getStrB(int col) {
                    return getStrA(col);
                }

                @Override
                public int getStrLen(int col) {
                    CharSequence str = getStrA(col);
                    return str != null ? str.length() : -1;
                }

                private void of(CharSequence tableName, int ordinalPosition, CharSequence columnName, CharSequence dataType) {
                    this.tableName = tableName;
                    this.ordinalPosition = ordinalPosition;
                    this.columnName = columnName;
                    this.dataType = dataType;
                }
            }
        }
    }

    static {
        final GenericRecordMetadata metadata = new GenericRecordMetadata();
        metadata.add(new TableColumnMetadata("table_name", ColumnType.STRING));
        metadata.add(new TableColumnMetadata("ordinal_position", ColumnType.INT));
        metadata.add(new TableColumnMetadata("column_name", ColumnType.STRING));
        metadata.add(new TableColumnMetadata("data_type", ColumnType.STRING));
        METADATA = metadata;
    }
}
