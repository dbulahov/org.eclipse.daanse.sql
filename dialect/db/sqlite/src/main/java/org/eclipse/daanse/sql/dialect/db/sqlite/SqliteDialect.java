/*
 * Copyright (c) 2002-2017 Hitachi Vantara..  All rights reserved.
 *
 * For more information please visit the Project: Hitachi Vantara - Mondrian
 *
 * ---- All changes after Fork in 2023 ------------------------
 *
 * Project: Eclipse daanse
 *
 * Copyright (c) 2023 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors after Fork in 2023:
 *   SmartCity Jena - initial adapt parts of Syntax.class
 *   Stefan Bischof (bipolis.org) - initial
 */
package org.eclipse.daanse.sql.dialect.db.sqlite;

import org.eclipse.daanse.sql.model.schema.TableReference;
import org.eclipse.daanse.sql.model.schema.Trigger;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import org.eclipse.daanse.sql.model.type.BestFitColumnType;
import org.eclipse.daanse.sql.dialect.api.generator.PaginationGenerator.PagingParam;
import org.eclipse.daanse.sql.dialect.api.generator.PaginationGenerator.PreparedPaging;
import org.eclipse.daanse.sql.dialect.db.common.AbstractJdbcDialect;

public class SqliteDialect extends AbstractJdbcDialect {

    private static final String SUPPORTED_PRODUCT_NAME = "SQLITE";

    /**
     * No.
     *
     * <p>
     * SQLite takes one writer at a time. Loading tables concurrently does not
     * divide the work, it makes the connections starve each other on the shared
     * cache — which is why the testkit used to compare against this dialect's
     * name in two places.
     */
    @Override
    public boolean supportsParallelLoading() {
        return false;
    }

    /**
     * {@code SMALLINT}.
     *
     * <p>
     * SQLite has no boolean type; it stores 0 and 1 in an INTEGER affinity.
     */
    @Override
    public String booleanTypeName() {
        return "SMALLINT";
    }

    private volatile org.eclipse.daanse.sql.dialect.api.generator.PaginationGenerator cachedPaginationGenerator;
    private volatile org.eclipse.daanse.sql.dialect.api.generator.ReturningGenerator cachedReturningGenerator;
    private volatile org.eclipse.daanse.sql.dialect.api.generator.MergeGenerator cachedMergeGenerator;

    /** JDBC-free constructor for SQL generation. */
    public SqliteDialect() {
        super(org.eclipse.daanse.sql.dialect.api.DialectInitData.ansiDefaults());
    }

    /** Construct from a captured snapshot — the canonical entry point. */
    public SqliteDialect(org.eclipse.daanse.sql.dialect.api.DialectInitData init) {
        super(init);
    }

    /**
     * {@code ALTER TABLE … RENAME COLUMN} from 3.25.0, which renames the column in the table's indexes, triggers and
     * views too; 3.22 refuses it with a syntax error, and {@code renameColumn} then gives {@code null}. A dialect made
     * without a connection takes such a version.
     */
    @Override
    public boolean supportsRenameColumn() {
        return dialectVersion.isUnknownOrAtLeast(3, 25);
    }

    /**
     * Yes, on every version: {@code group_concat(x, sep)}, which leaves the NULLs out. An order inside it,
     * {@code group_concat(x, sep ORDER BY …)}, comes with 3.44.0; before, no expression of a grouped query lists the
     * values in an order (a sorted subquery over the group is a misuse of the aggregate), and an ordered list is
     * refused. A DISTINCT aggregate takes one argument only, on every version: a DISTINCT list with any separator is
     * the values of {@code json_group_array(DISTINCT x)} joined in a subquery. A DISTINCT list in the order of
     * another column SQLite puts in no order of the values' first places, and is refused, as PostgreSQL refuses it.
     */
    @Override
    public boolean supportsListAgg() {
        return true;
    }

    @Override
    public java.util.Optional<String> generateListAgg(CharSequence operand, boolean distinct, String separator,
            String coalesce, String onOverflowTruncate, List<org.eclipse.daanse.sql.model.sql.OrderedColumn> columns) {
        boolean ordered = columns != null && !columns.isEmpty();
        if (ordered && !dialectVersion.isUnknownOrAtLeast(3, 44)) {
            return java.util.Optional.empty();
        }
        if (ordered && distinct && !columns.stream().allMatch(c -> c.tableName() == null
                && (operand.toString().equals(c.columnName())
                        || operand.toString().equals(quoteIdentifier(c.columnName()).toString())))) {
            return java.util.Optional.empty();
        }
        StringBuilder sep = new StringBuilder();
        quoteStringLiteral(sep, separator == null ? "," : separator);
        String order = ordered ? " ORDER BY " + buildOrderedColumnsClause(columns) : "";
        if (distinct) {
            return java.util.Optional.of("(SELECT group_concat(value, " + sep
                    + ") FROM json_each(json_group_array(DISTINCT " + operand + order + ")))");
        }
        return java.util.Optional.of("group_concat(" + operand + ", " + sep + order + ")");
    }

    /**
     * From 3.44.0: the n-th element of {@code json_group_array(v ORDER BY …)}, the NULL values left out with
     * {@code FILTER (WHERE v IS NOT NULL)} where asked; an aggregate over the rows of the group, not the window
     * {@code nth_value … OVER}, which in a grouped query runs over the groups (V-82). Before 3.44 an aggregate takes
     * no ORDER BY, and the n-th value is refused. n counts from 1, below 1 is 1; past the end it is NULL. A dialect
     * made without a connection takes such a version.
     */
    @Override
    public boolean supportsNthValue() {
        return dialectVersion.isUnknownOrAtLeast(3, 44);
    }

    @Override
    public boolean supportsNthValueIgnoreNulls() {
        return supportsNthValue();
    }

    @Override
    public java.util.Optional<String> generateNthValueAgg(CharSequence operand, boolean ignoreNulls, Integer n,
            List<org.eclipse.daanse.sql.model.sql.OrderedColumn> columns) {
        if (!supportsNthValue()) {
            return java.util.Optional.empty();
        }
        StringBuilder sb = new StringBuilder("json_extract(json_group_array(").append(operand);
        if (columns != null && !columns.isEmpty()) {
            sb.append(" ORDER BY ").append(buildOrderedColumnsClause(columns));
        }
        sb.append(')');
        if (ignoreNulls) {
            sb.append(" FILTER (WHERE ").append(operand).append(" IS NOT NULL)");
        }
        sb.append(", ");
        int index = org.eclipse.daanse.sql.dialect.api.generator.AggregationGenerator.nthIndex(n) - 1;
        quoteStringLiteral(sb, "$[" + index + "]");
        return java.util.Optional.of(sb.append(')').toString());
    }

    /**
     * From 3.44.0, computed on the group's values sorted in {@code json_group_array(c ORDER BY c) FILTER (WHERE c IS
     * NOT NULL)}: SQLite has no {@code WITHIN GROUP}, and its own {@code percentile_cont(c, p)} and
     * {@code percentile_disc(c, p)} are in some builds only (3.47 and 3.50 of keinos/sqlite3, not 3.46 or 3.51), as
     * {@code ceil} and {@code floor} are; the expression takes neither, {@code CAST(x AS INTEGER)} rounds down. Before
     * 3.44 an aggregate takes no ORDER BY. A dialect made without a connection takes such a version.
     */
    @Override
    public boolean supportsPercentileDisc() {
        return dialectVersion.isUnknownOrAtLeast(3, 44);
    }

    @Override
    public boolean supportsPercentileCont() {
        return supportsPercentileDisc();
    }

    /** The {@code max(1, ceil(p n))}-th value: the first whose cumulative share reaches p; NULL for no value. */
    @Override
    public java.util.Optional<String> generatePercentileDisc(double percentile, boolean desc, String tableName,
            String columnName) {
        if (!supportsPercentileDisc()) {
            return java.util.Optional.empty();
        }
        String col = column(tableName, columnName);
        String pn = "(" + percentile + " * count(" + col + "))";
        String ceil = "(CAST(" + pn + " AS INTEGER) + (" + pn + " > CAST(" + pn + " AS INTEGER)))";
        return java.util.Optional.of("json_extract(" + sortedArray(col, desc) + ", '$[' || (max(1, " + ceil
                + ") - 1) || ']')");
    }

    /** The value at {@code p (n - 1)} of the sorted values counted from 0, interpolated; NULL for no value. */
    @Override
    public java.util.Optional<String> generatePercentileCont(double percentile, boolean desc, String tableName,
            String columnName) {
        if (!supportsPercentileCont()) {
            return java.util.Optional.empty();
        }
        String col = column(tableName, columnName);
        String array = sortedArray(col, desc);
        String r = "(" + percentile + " * (count(" + col + ") - 1))";
        String lo = "CAST(" + r + " AS INTEGER)";
        String frac = "(" + r + " - " + lo + ")";
        String atLo = "json_extract(" + array + ", '$[' || " + lo + " || ']')";
        String atHi = "json_extract(" + array + ", '$[' || (" + lo + " + (" + frac + " > 0)) || ']')";
        return java.util.Optional.of("(" + atLo + " + " + frac + " * (" + atHi + " - " + atLo + "))");
    }

    private String column(String tableName, String columnName) {
        StringBuilder col = new StringBuilder();
        if (tableName != null) {
            quoteIdentifier(col, tableName, columnName);
        } else {
            quoteIdentifier(col, columnName);
        }
        return col.toString();
    }

    private static String sortedArray(String col, boolean desc) {
        return "json_group_array(" + col + " ORDER BY " + col + (desc ? " DESC" : "") + ") FILTER (WHERE " + col
                + " IS NOT NULL)";
    }

    /**
     * SQLite has no {@code ALTER TABLE … ADD CONSTRAINT}, in any version (a syntax error): its unique constraints,
     * checks and foreign keys are declared in {@code CREATE TABLE}.
     */
    @Override
    public boolean supportsAddUniqueConstraint() {
        return false;
    }

    @Override
    public boolean supportsAddCheckConstraint() {
        return false;
    }

    @Override
    public boolean supportsAddForeignKeyConstraint() {
        return false;
    }

    /**
     * The referenced table by its name alone: {@code REFERENCES "main"."t"} is a syntax error, a foreign key refers
     * to a table of its own schema. SQLite checks foreign keys only on a connection that turned them on
     * ({@code PRAGMA foreign_keys = ON}); without, the key is declared and not kept.
     */
    @Override
    public String foreignKeyClause(String constraintName, List<String> fkColumns, TableReference referencedTable,
            List<String> referencedColumns, String onDelete, String onUpdate) {
        TableReference unqualified = referencedTable == null ? null
                : new TableReference(java.util.Optional.empty(), referencedTable.name(), referencedTable.type());
        return super.foreignKeyClause(constraintName, fkColumns, unqualified, referencedColumns, onDelete, onUpdate);
    }

    /**
     * No: SQLite keeps no schemas inside a database. Its schemas are {@code main}, {@code temp} and the database files
     * a connection attaches ({@code ATTACH DATABASE 'file' AS s}), for that connection only, with no IF [NOT] EXISTS;
     * {@code CREATE SCHEMA} and {@code DROP SCHEMA} are syntax errors in every version. {@code createSchema} and
     * {@code dropSchema} give {@code null}.
     */
    @Override
    public boolean supportsSchemas() {
        return false;
    }

    @Override
    public String createSchema(String schemaName, boolean ifNotExists) {
        return null;
    }

    @Override
    public String dropSchema(String schemaName, boolean ifExists, boolean cascade) {
        return null;
    }

    @Override
    public boolean supportsDropSchemaIfExists() {
        return false;
    }

    /**
     * {@code CREATE TRIGGER name timing event ON table FOR EACH ROW [WHEN (condition)] BEGIN …; END}, in every
     * version: SQLite reads WHEN itself, and takes the table with its schema. SQLite has row triggers only:
     * {@code FOR EACH STATEMENT} is a syntax error, and a row trigger does not fire once for a statement (not at all
     * for one of no rows), so none is written. The body names its tables without a schema: SQLite refuses a qualified
     * table name in a statement of a trigger. {@code OR REPLACE} SQLite has not, and it is left out.
     *
     * @throws UnsupportedOperationException of {@link Trigger.TriggerScope#STATEMENT}
     */
    @Override
    public String createTrigger(String triggerName, Trigger.TriggerTiming timing, Trigger.TriggerEvent event,
            TableReference table, Trigger.TriggerScope scope, String whenCondition, String body, boolean orReplace) {
        if (scope == Trigger.TriggerScope.STATEMENT) {
            throw new UnsupportedOperationException(
                    "SQLite has row triggers only: FOR EACH STATEMENT of " + triggerName + " cannot be written");
        }
        return super.createTrigger(triggerName, timing, event, table, scope, whenCondition, body, orReplace);
    }

    @Override
    public boolean supportsSequences() {
        return false;
    }

    @Override
    public boolean supportsDropConstraintIfExists() {
        // SQLite can't drop constraints at all — fall back to drop+recreate.
        return false;
    }

    @Override
    public boolean supportsCreateOrReplaceView() {
        return false;
    }

    /** SQLite has no {@code ALTER INDEX ... RENAME}. */
    @Override
    public boolean supportsRenameIndex() {
        return false;
    }

    /** SQLite can't rename constraints — same reason it can't drop them. */
    @Override
    public boolean supportsRenameConstraint() {
        return false;
    }

    /**
     * SQLite has no {@code ALTER VIEW ... RENAME}, and {@code ALTER TABLE v RENAME TO w} refuses a view ("view v may
     * not be altered"), in every version.
     */
    @Override
    public boolean supportsRenameView() {
        return false;
    }

    /**
     * {@code ALTER TABLE t RENAME TO u}, the new name by itself ({@code RENAME TO "main"."u"} is a syntax error), in
     * every version. The foreign keys of other tables follow the table in every version; its views and triggers from
     * 3.25.0 on. Before, the rename is done and leaves them naming the old table, which fails when they run
     * ("no such table"). A connection that turns {@code PRAGMA legacy_alter_table} on gets that behaviour back.
     */
    @Override
    public boolean supportsRenameTable() {
        return true;
    }

    @Override
    public boolean supportsDropTableCascade() {
        return false;
    }

    /**
     * SQLite does not support the ANSI derived-column-list aliasing
     * {@code (VALUES ...) AS t (c1, c2)} ("near \"(\": syntax error"); inline
     * datasets render as the generic {@code select ... union all select ...}
     * form instead (SQLite allows SELECT without FROM).
     */
    @Override
    public StringBuilder generateInline(java.util.List<String> columnNames, java.util.List<String> columnTypes,
            java.util.List<String[]> valueList) {
        return generateInlineGeneric(columnNames, columnTypes, valueList, null, false);
    }

    /**
     * {@code NULLS FIRST} / {@code NULLS LAST} from 3.30.0; 3.25 refuses them with a syntax error, and the NULLs are
     * then ranked with a CASE. SQLite itself puts NULLs first ascending and last descending. A dialect made without a
     * connection takes such a version.
     */
    @Override
    public boolean supportsNullsLast() {
        return supportsNullsOrdering();
    }

    @Override
    protected boolean supportsNullsOrdering() {
        return dialectVersion.isUnknownOrAtLeast(3, 30);
    }

    /** {@code x ASC NULLS LAST} from 3.30, before {@code CASE WHEN x IS NULL THEN 1 ELSE 0 END, x ASC}. */
    @Override
    public StringBuilder generateOrderByNulls(CharSequence expr, boolean ascending, boolean collateNullsLast) {
        if (supportsNullsOrdering()) {
            return generateOrderByNullsAnsi(expr, ascending, collateNullsLast);
        }
        return super.generateOrderByNulls(expr, ascending, collateNullsLast);
    }

    /**
     * SQLite has no static column types: for expression columns (no source table,
     * e.g. {@code sum(x)}) the xerial driver derives the reported JDBC type from
     * the CURRENT ROW's storage class. A {@code sum()} over a NUMERIC-affinity
     * column therefore reports {@code INTEGER} whenever the first row's total
     * happens to be integral, an INT/LONG accessor is chosen once for the whole
     * result, and every later REAL row is silently truncated by {@code getInt()}
     * (e.g. a closure-table salary rollup losing exactly the sum of all
     * fractional parts). For numeric expression columns use the OBJECT accessor,
     * which reads each row with its own storage class. Plain table columns keep
     * the default mapping — their metadata comes from the declared type and is
     * row-independent.
     */
    @Override
    public BestFitColumnType getType(ResultSetMetaData metaData, int columnIndex) throws SQLException {
        final int columnType = metaData.getColumnType(columnIndex + 1);
        switch (columnType) {
        case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL, Types.REAL,
                Types.FLOAT, Types.DOUBLE: {
            final String table = metaData.getTableName(columnIndex + 1);
            if (table == null || table.isEmpty()) {
                logTypeInfo(metaData, columnIndex, BestFitColumnType.OBJECT);
                return BestFitColumnType.OBJECT;
            }
            break;
        }
        default:
            break;
        }
        return super.getType(metaData, columnIndex);
    }

    @Override
    public String name() {
        return SUPPORTED_PRODUCT_NAME.toLowerCase();
    }

    @Override
    public org.eclipse.daanse.sql.dialect.api.IdentifierCaseFolding caseFolding() {
        return org.eclipse.daanse.sql.dialect.api.IdentifierCaseFolding.PRESERVE;
    }

    @Override
    public org.eclipse.daanse.sql.dialect.api.generator.PaginationGenerator paginationGenerator() {
        var local = cachedPaginationGenerator;
        if (local != null)
            return local;
        local = new org.eclipse.daanse.sql.dialect.api.generator.PaginationGenerator() {
            @Override
            public String paginate(java.util.OptionalLong limit, java.util.OptionalLong offset) {
                if (limit.isEmpty() && offset.isEmpty())
                    return "";
                StringBuilder sb = new StringBuilder();
                if (limit.isPresent()) {
                    long l = limit.getAsLong();
                    if (l < 0)
                        throw new IllegalArgumentException("limit must be >= 0");
                    sb.append(" LIMIT ").append(l);
                } else {
                    sb.append(" LIMIT -1");
                }
                if (offset.isPresent()) {
                    long o = offset.getAsLong();
                    if (o < 0)
                        throw new IllegalArgumentException("offset must be >= 0");
                    sb.append(" OFFSET ").append(o);
                }
                return sb.toString();
            }

            @Override
            public PreparedPaging paginatePrepared(boolean withLimit, boolean withOffset) {
                if (withLimit && withOffset) {
                    return new PreparedPaging(
                        "LIMIT ? OFFSET ?",
                        List.of(PagingParam.LIMIT, PagingParam.OFFSET)
                    );
                } else if (withLimit) {
                    return new PreparedPaging(
                        "LIMIT ?",
                        List.of(PagingParam.LIMIT)
                    );
                } else if (withOffset) {
                    return new PreparedPaging(
                        "OFFSET ?",
                        List.of(PagingParam.OFFSET)
                    );
                } else {
                    return new PreparedPaging("", List.of());
                }
            }
        };
        cachedPaginationGenerator = local;
        return local;
    }

    @Override
    public org.eclipse.daanse.sql.dialect.api.generator.ReturningGenerator returningGenerator() {
        var local = cachedReturningGenerator;
        if (local != null)
            return local;
        if (!dialectVersion.isUnknownOrAtLeast(3, 35)) {
            local = super.returningGenerator();
            cachedReturningGenerator = local;
            return local;
        }
        local = new org.eclipse.daanse.sql.dialect.api.generator.ReturningGenerator() {
            @Override
            public boolean supportsReturning() {
                return true;
            }

            @Override
            public java.util.Optional<String> returning(java.util.List<String> columns) {
                if (columns == null || columns.isEmpty())
                    return java.util.Optional.empty();
                if (columns.size() == 1 && "*".equals(columns.get(0))) {
                    return java.util.Optional.of(" RETURNING *");
                }
                StringBuilder sb = new StringBuilder(" RETURNING ");
                boolean first = true;
                for (String c : columns) {
                    if (!first)
                        sb.append(", ");
                    sb.append(quoteIdentifier(c));
                    first = false;
                }
                return java.util.Optional.of(sb.toString());
            }
        };
        cachedReturningGenerator = local;
        return local;
    }

    /**
     * {@code INSERT … ON CONFLICT (key) DO UPDATE SET c = excluded.c} from 3.24.0, which updates the row in place,
     * its key and the columns not listed kept; with no column to update {@code DO NOTHING}, insert-or-ignore. 3.22
     * knows no {@code ON CONFLICT} after {@code INSERT} (a syntax error), and {@code INSERT OR REPLACE} is no
     * stand-in: it deletes the row and inserts a new one, the columns not listed lost. The key must be a PRIMARY KEY
     * or a UNIQUE constraint. A dialect made without a connection takes such a version.
     */
    @Override
    public org.eclipse.daanse.sql.dialect.api.generator.MergeGenerator mergeGenerator() {
        var local = cachedMergeGenerator;
        if (local != null)
            return local;
        if (!dialectVersion.isUnknownOrAtLeast(3, 24)) {
            local = super.mergeGenerator();
            cachedMergeGenerator = local;
            return local;
        }
        local = new org.eclipse.daanse.sql.dialect.api.generator.MergeGenerator() {
            @Override
            public boolean supportsMerge() {
                return true;
            }

            @Override
            public java.util.Optional<String> upsert(UpsertSpec spec, java.util.List<String> values) {
                if (values == null || values.size() != spec.insertColumns().size()) {
                    throw new IllegalArgumentException("values must match insertColumns in length");
                }
                StringBuilder sb = new StringBuilder("INSERT INTO ").append(qualified(spec.target())).append(" (");
                appendQuotedCsv(sb, spec.insertColumns());
                sb.append(") VALUES (");
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0)
                        sb.append(", ");
                    sb.append(values.get(i));
                }
                sb.append(") ON CONFLICT (");
                appendQuotedCsv(sb, spec.keyColumns());
                if (spec.updateColumns().isEmpty()) {
                    // insert-or-ignore: an empty DO UPDATE SET is a syntax error
                    return java.util.Optional.of(sb.append(") DO NOTHING").toString());
                }
                sb.append(") DO UPDATE SET ");
                for (int i = 0; i < spec.updateColumns().size(); i++) {
                    if (i > 0)
                        sb.append(", ");
                    String col = quoteIdentifier(spec.updateColumns().get(i)).toString();
                    sb.append(col).append(" = excluded.").append(col);
                }
                return java.util.Optional.of(sb.toString());
            }
        };
        cachedMergeGenerator = local;
        return local;
    }

    /** SQLite cannot store comments on tables or columns. */
    @Override
    public java.util.Optional<String> commentOnTable(TableReference table, String comment) {
        return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<String> commentOnColumn(TableReference table, String columnName, String comment,
            org.eclipse.daanse.sql.model.schema.ColumnMetaData currentMeta) {
        return java.util.Optional.empty();
    }

    /**
     * SQLite: a partial index, {@code CREATE INDEX … WHERE condition}, from 3.8.0; a dialect made
     * without a connection takes such a version.
     */
    @Override
    public boolean supportsFilteredIndexes() {
        return dialectVersion.isUnknownOrAtLeast(3, 8);
    }

    /** SQLite keeps the order of each column of an index. */
    @Override
    public boolean supportsDescendingIndexColumns() {
        return true;
    }

    /** SQLite checks a deferrable foreign key at the end of the transaction where it is deferred. */
    @Override
    public boolean supportsDeferrableForeignKeys() {
        return true;
    }

    /** SQLite keeps no comments. */
    @Override
    public boolean supportsCommentOnView() {
        return false;
    }

    /** SQLite has no roles. */
    @Override
    public boolean supportsRoles() {
        return false;
    }
}
