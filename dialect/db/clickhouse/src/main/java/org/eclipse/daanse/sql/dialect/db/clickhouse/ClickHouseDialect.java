/*
 * Copyright (c) 2022 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * History:
 *  This files came from the mondrian project. Some of the Flies
 *  (mostly the Tests) did not have License Header.
 *  But the Project is EPL Header. 2002-2022 Hitachi Vantara.
 *
 * Contributors:
 *   Hitachi Vantara.
 *   SmartCity Jena - initial  Java 8, Junit5
 */
package org.eclipse.daanse.sql.dialect.db.clickhouse;

import java.util.List;
import java.util.Optional;

import org.eclipse.daanse.sql.model.schema.TableReference;
import org.eclipse.daanse.sql.dialect.api.generator.AggregationGenerator;
import org.eclipse.daanse.sql.model.sql.BitOperation;
import org.eclipse.daanse.sql.model.sql.NullsOrder;
import org.eclipse.daanse.sql.model.sql.OrderedColumn;
import org.eclipse.daanse.sql.model.sql.SortDirection;
import org.eclipse.daanse.sql.dialect.db.common.AbstractJdbcDialect;

public class ClickHouseDialect extends AbstractJdbcDialect {

    /** ClickHouse rejects plain {@code CREATE INDEX} without a data-skipping {@code TYPE}
     *  clause (INCORRECT_QUERY 80) — b-tree index DDL is not expressible. */
    @Override
    public boolean supportsIndexDdl() {
        return false;
    }

    private static final String SUPPORTED_PRODUCT_NAME = "CLICKHOUSE";

    /**
     * {@code SMALLINT}.
     *
     * <p>
     * ClickHouse maps SMALLINT onto Int16 and reads it back as a number.
     */
    @Override
    public String booleanTypeName() {
        return "SMALLINT";
    }

    /**
     * No.
     *
     * <p>
     * ClickHouse refuses {@code setAutoCommit(false)} with a
     * {@code SQLFeatureNotSupportedException} — there is nothing to turn off,
     * every statement stands alone. A writer that wraps a table load in one
     * transaction cannot even begin.
     */
    @Override
    public boolean supportsTransactions() {
        return false;
    }

    /**
     * Wraps a nullable column's type in {@code Nullable(…)}.
     *
     * <p>
     * ClickHouse columns are not nullable by default and there is no
     * {@code NULL} modifier to add afterwards — nullability is part of the type
     * itself. A column declared {@code Int32} rejects every null; the same
     * column declared {@code Nullable(Int32)} accepts them.
     */
    @Override
    public String nativeType(org.eclipse.daanse.sql.model.schema.ColumnMetaData meta) {
        String type = super.nativeType(meta);
        if (meta.nullability() == org.eclipse.daanse.sql.model.schema.ColumnMetaData.Nullability.NULLABLE) {
            return "Nullable(" + type + ")";
        }
        return type;
    }

    /**
     * Appends {@code ENGINE = MergeTree() ORDER BY (…)}.
     *
     * <p>
     * ClickHouse refuses a table without one: <em>Code 42, ORDER BY or PRIMARY
     * KEY clause is missing</em>. MergeTree is the general-purpose engine, and
     * it sorts by the primary key where the schema declares one. Where it does
     * not, {@code ORDER BY tuple()} says "no ordering" — the legal way to
     * express what every other database means by a table without a key.
     *
     * <p>
     * The {@code NOT NULL} the base implementation appends is left out here:
     * nullability rides in the type (see {@link #nativeType}), and ClickHouse
     * rejects the suffix.
     */
    @Override
    public String createTable(org.eclipse.daanse.sql.model.schema.TableReference table,
            List<org.eclipse.daanse.sql.model.schema.ColumnDefinition> columns,
            org.eclipse.daanse.sql.model.schema.PrimaryKey primaryKey, boolean ifNotExists) {
        StringBuilder sb = new StringBuilder();
        sb.append(ifNotExists && supportsCreateTableIfNotExists() ? "CREATE TABLE IF NOT EXISTS " : "CREATE TABLE ");
        sb.append(qualified(table)).append(" (\n");
        boolean first = true;
        for (org.eclipse.daanse.sql.model.schema.ColumnDefinition cd : columns) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("  ").append(quoteIdentifier(cd.column().name()));
            sb.append(' ').append(nativeType(cd.columnMetaData()));
            cd.columnMetaData().columnDefault().ifPresent(d -> sb.append(" DEFAULT ").append(d));
        }
        sb.append("\n)");
        sb.append(createTableSuffix(primaryKey == null ? List.of()
                : primaryKey.columns().stream().map(c -> quoteIdentifier(c.name()).toString()).toList()));
        return sb.toString();
    }

    /**
     * {@code ENGINE = MergeTree() ORDER BY (…)}. MergeTree is the
     * general-purpose engine and sorts by the key where there is one; where
     * there is none, {@code ORDER BY tuple()} is how ClickHouse spells "no
     * ordering".
     */
    @Override
    public String createTableSuffix(List<String> quotedOrderByColumns) {
        if (quotedOrderByColumns == null || quotedOrderByColumns.isEmpty()) {
            return " ENGINE = MergeTree() ORDER BY tuple()";
        }
        return " ENGINE = MergeTree() ORDER BY (" + String.join(", ", quotedOrderByColumns) + ")";
    }

    /** JDBC-free constructor for SQL generation. */
    public ClickHouseDialect() {
        super(org.eclipse.daanse.sql.dialect.api.DialectInitData.ansiDefaults());
    }

    /** Construct from a captured snapshot — the canonical entry point. */
    public ClickHouseDialect(org.eclipse.daanse.sql.dialect.api.DialectInitData init) {
        super(init);
    }

    /** What RE2 has not of a Java expression: lookaround, back references, atomic and possessive, class intersection,
     *  Java's own escapes and flags. Of an escaped backslash it may say so too: that expression is matched in Java. */
    private static final java.util.regex.Pattern NOT_RE2 = java.util.regex.Pattern.compile(
            "\\(\\?[=!<>]|\\\\[1-9]|\\\\k<|[*+?}]\\+|&&|\\\\[ZGRXhHV]|\\(\\?[a-zA-Z-]*[xdUc][a-zA-Z-]*[:)]");

    /**
     * Yes, on every version: {@code match} is RE2's, which reads the {@code \A}, {@code \z}, {@code (?:…)},
     * {@code (?i)} and {@code (?-s)} the condition is written with, and {@code \Q…\E}, {@code \d}, {@code \w},
     * {@code \s} and {@code \p{L}} of a Java expression.
     */
    @Override
    public boolean allowsRegularExpressionInWhereClause() {
        return true;
    }

    /**
     * {@code s IS NOT NULL AND match(s, '(?-s)\A(?:…)\z')}: MDX {@code MATCHES} is Java's {@code Pattern.matches},
     * the whole value, so the expression is anchored at both ends; RE2's dot takes a line break, as Java's does not,
     * unless {@code (?-s)} says so. Empty of an expression Java cannot read, and of one with what RE2 has not
     * ({@link #NOT_RE2}), which the server would refuse: it is then matched in Java. RE2's {@code (?i)} folds the
     * case of letters beyond ASCII too, which Java's does only with {@code (?u)}.
     */
    @Override
    public Optional<String> generateRegularExpression(String source, String javaRegex) {
        try {
            java.util.regex.Pattern.compile(javaRegex);
        } catch (java.util.regex.PatternSyntaxException e) {
            return Optional.empty();
        }
        if (NOT_RE2.matcher(javaRegex).find()) {
            return Optional.empty();
        }
        String regex = withoutUnicodeCaseFlag(
                org.eclipse.daanse.sql.dialect.db.common.DialectUtil.cleanUnicodeAwareCaseFlag(javaRegex));
        StringBuilder sb = new StringBuilder();
        sb.append(source).append(" IS NOT NULL AND match(").append(source).append(", ");
        quoteStringLiteral(sb, "(?-s)\\A(?:" + regex + ")\\z");
        sb.append(')');
        return Optional.of(sb.toString());
    }

    private static final java.util.regex.Pattern FLAG_GROUP = java.util.regex.Pattern
            .compile("\\(\\?([a-zA-Z-]+)([:)])");

    /**
     * Java's {@code u} taken out of each flag group, {@code (?iu)} to {@code (?i)}: RE2 knows no {@code u}, and its
     * {@code (?i)} folds the case beyond ASCII as Java's does with {@code u}. A group left without a flag goes, or
     * becomes {@code (?:}.
     */
    private static String withoutUnicodeCaseFlag(String regex) {
        java.util.regex.Matcher m = FLAG_GROUP.matcher(regex);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String flags = m.group(1).replace("u", "");
            String group = flags.isEmpty() || "-".equals(flags) ? (":".equals(m.group(2)) ? "(?:" : "")
                    : "(?" + flags + m.group(2);
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(group));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Casts with ClickHouse's own type names, and a cast that gives NULL where the value does not fit:
     * {@code accurateCastOrNull(x, 'T')}, on every version. It is NULL for what does not parse, for what overflows
     * ({@code CAST(300 AS Int8)} gives 44), and for what would lose part of itself ({@code 3.7} as {@code Int32});
     * a date of a month or day out of range it takes as 1970-01-01 or the next month, and a too large Float64 as inf.
     * {@code accurateCastOrNull(…, 'Bool')} throws on what is no boolean on 22.x and 23.x and takes {@code 'true'}
     * for NULL on 21.12: before 24.3 a boolean is {@code true}, {@code false}, {@code 1} or {@code 0}, else NULL.
     */
    @Override
    public org.eclipse.daanse.sql.dialect.api.generator.CastGenerator castGenerator() {
        boolean boolType = dialectVersion.isUnknownOrAtLeast(21, 12);
        boolean boolTryCast = dialectVersion.isUnknownOrAtLeast(24, 3);
        return new org.eclipse.daanse.sql.dialect.api.generator.CastGenerator() {
            @Override
            public String nativeType(org.eclipse.daanse.sql.model.type.Datatype type) {
                return switch (type) {
                case VARCHAR, BINARY -> "String";
                case INTEGER -> "Int32";
                case BIGINT -> "Int64";
                case SMALLINT -> "Int16";
                case FLOAT, DOUBLE -> "Float64";
                case REAL -> "Float32";
                case NUMERIC, DECIMAL -> "Decimal(38, 10)";
                case BOOLEAN -> boolType ? "Bool" : "UInt8";
                case DATE -> "Date";
                case TIMESTAMP -> "DateTime64(3)";
                case UUID -> "UUID";
                default -> type.getValue().toUpperCase(java.util.Locale.ROOT);
                };
            }

            @Override
            public boolean supportsTryCast() {
                return true;
            }

            @Override
            public String tryCast(String expr, org.eclipse.daanse.sql.model.type.Datatype type) {
                if (type == org.eclipse.daanse.sql.model.type.Datatype.BOOLEAN && !boolTryCast) {
                    String s = "lower(toString(" + expr + "))";
                    return "if(" + s + " IN ('true', 'false', '1', '0'), " + s + " IN ('true', '1'), NULL)";
                }
                return "accurateCastOrNull(" + expr + ", '" + nativeType(type) + "')";
            }
        };
    }

    /**
     * From 24.10, a refreshable materialized view: it computes its query when it is created and keeps the rows until
     * it is refreshed ({@code SYSTEM REFRESH VIEW}), as the contract asks. 24.3 and 24.8 refuse one without an
     * experimental setting (Code 344), 23.8 knows no {@code REFRESH}; the classic ClickHouse materialized view feeds a
     * table from the rows inserted into another and keeps no result of its query. The dialect has it refresh only after ten
     * years, a refresh on demand in effect. The first refresh runs in the background as the view is created: a read
     * at once may find it empty, {@code SYSTEM WAIT VIEW} waits for it. A dialect made without a connection takes
     * such a version.
     */
    @Override
    public boolean supportsMaterializedViews() {
        return dialectVersion.isUnknownOrAtLeast(24, 10);
    }

    /**
     * {@code CREATE MATERIALIZED VIEW [IF NOT EXISTS] v REFRESH AFTER 10 YEAR ENGINE = MergeTree() ORDER BY tuple()
     * AS …}: the rows kept in a MergeTree table of their own (see {@link #supportsMaterializedViews}).
     */
    @Override
    public Optional<String> createMaterializedView(TableReference view, String selectSql, boolean ifNotExists) {
        if (!supportsMaterializedViews()) {
            return Optional.empty();
        }
        if (selectSql == null || selectSql.isBlank()) {
            throw new IllegalArgumentException("selectSql must not be blank for CREATE MATERIALIZED VIEW");
        }
        return Optional.of("CREATE MATERIALIZED VIEW " + (ifNotExists ? "IF NOT EXISTS " : "") + qualified(view)
                + " REFRESH AFTER 10 YEAR ENGINE = MergeTree() ORDER BY tuple() AS " + selectSql);
    }

    /** {@code DROP VIEW [IF EXISTS] v}: ClickHouse has no {@code DROP MATERIALIZED VIEW} (a syntax error). */
    @Override
    public Optional<String> dropMaterializedView(TableReference view, boolean ifExists) {
        if (!supportsMaterializedViews()) {
            return Optional.empty();
        }
        return Optional.of("DROP VIEW " + (ifExists ? "IF EXISTS " : "") + qualified(view));
    }

    /**
     * {@code LIMIT n OFFSET m}, {@code LIMIT n}, {@code OFFSET m}, on every version, with an ORDER BY or without. The
     * standard {@code OFFSET m ROWS FETCH NEXT n ROWS ONLY} is refused without an ORDER BY on every version (a syntax
     * error on 21.x, Code 628 on 23.8 and later), and {@code FETCH} without {@code OFFSET} on 21.x; MySQL's huge limit
     * for an offset alone gives no row at all on 26.6. The parameters of the prepared form go in that order too.
     */
    @Override
    public org.eclipse.daanse.sql.dialect.api.generator.PaginationGenerator paginationGenerator() {
        return new org.eclipse.daanse.sql.dialect.api.generator.PaginationGenerator() {
            @Override
            public String paginate(java.util.OptionalLong limit, java.util.OptionalLong offset) {
                StringBuilder sb = new StringBuilder();
                limit.ifPresent(l -> {
                    if (l < 0) {
                        throw new IllegalArgumentException("limit must be >= 0");
                    }
                    sb.append(" LIMIT ").append(l);
                });
                offset.ifPresent(o -> {
                    if (o < 0) {
                        throw new IllegalArgumentException("offset must be >= 0");
                    }
                    sb.append(" OFFSET ").append(o);
                });
                return sb.toString();
            }

            @Override
            public PreparedPaging paginatePrepared(boolean withLimit, boolean withOffset) {
                if (withLimit && withOffset) {
                    return new PreparedPaging("LIMIT ? OFFSET ?", List.of(PagingParam.LIMIT, PagingParam.OFFSET));
                } else if (withLimit) {
                    return new PreparedPaging("LIMIT ?", List.of(PagingParam.LIMIT));
                } else if (withOffset) {
                    return new PreparedPaging("OFFSET ?", List.of(PagingParam.OFFSET));
                }
                return new PreparedPaging("", List.of());
            }
        };
    }

    /**
     * {@code WITH RECURSIVE} from 24.4; 24.3 and before refuse it with a syntax error (Code 62), and the dialect writes
     * no RECURSIVE there. A recursive expression runs on the new analyzer only, on by default from 24.3: a session
     * that turns it off ({@code allow_experimental_analyzer = 0}) refuses one, which no flag of the dialect can say.
     * Expressions that are not recursive run on every version. A dialect made without a connection takes such a
     * version.
     */
    @Override
    public org.eclipse.daanse.sql.dialect.api.generator.CteGenerator cteGenerator() {
        boolean recursive = dialectVersion.isUnknownOrAtLeast(24, 4);
        return new org.eclipse.daanse.sql.dialect.api.generator.CteGenerator() {
            @Override
            public boolean supportsRecursiveCte() {
                return recursive;
            }
        };
    }

    /**
     * From 22.9. {@code GROUP BY GROUPING SETS} comes with 22.6, but 22.6 leaves the rows of one unsorted by an
     * {@code ORDER BY}, and 22.6 to 22.8 give {@code GROUPING()} the other way round, 1 for a column grouped by, until
     * 22.9 turns {@code force_grouping_standard_compatibility} on. A total's key of a column that is not Nullable is
     * the type's default, '' or 0, not NULL, unless the session sets {@code group_by_use_nulls = 1}:
     * {@code GROUPING()} tells it from a value. A dialect made without a connection takes such a version.
     */
    @Override
    public boolean supportsGroupingSets() {
        return dialectVersion.isUnknownOrAtLeast(22, 9);
    }

    /**
     * {@code CREATE DATABASE [IF NOT EXISTS] "s"}: a schema in ClickHouse is a database; {@code CREATE SCHEMA} is a
     * syntax error (Code 62) in every version.
     */
    @Override
    public String createSchema(String schemaName, boolean ifNotExists) {
        return "CREATE DATABASE " + (ifNotExists ? "IF NOT EXISTS " : "") + quoteIdentifier(schemaName);
    }

    /**
     * {@code DROP DATABASE [IF EXISTS] "s"}, which drops its tables with it: ClickHouse has no {@code CASCADE} here,
     * and {@code DROP SCHEMA} is a syntax error (Code 62) in every version.
     */
    @Override
    public String dropSchema(String schemaName, boolean ifExists, boolean cascade) {
        return "DROP DATABASE " + (ifExists ? "IF EXISTS " : "") + quoteIdentifier(schemaName);
    }

    @Override
    public boolean supportsSequences() {
        return false;
    }

    @Override
    public boolean supportsDropConstraintIfExists() {
        return false;
    }

    /** ClickHouse has no index rename — it has no b-tree index DDL at all (see {@link #supportsIndexDdl}). */
    @Override
    public boolean supportsRenameIndex() {
        return false; // skipping indexes: only DROP INDEX / ADD INDEX
    }

    /**
     * {@code ALTER TABLE … RENAME COLUMN} from 20.4; 20.3 refuses it with a syntax error (Code 62), and
     * {@code renameColumn} then gives {@code null}. A dialect made without a connection takes such a version.
     */
    @Override
    public boolean supportsRenameColumn() {
        return dialectVersion.isUnknownOrAtLeast(20, 4);
    }

    /** ClickHouse has no constraint rename. */
    @Override
    public boolean supportsRenameConstraint() {
        return false;
    }

    /**
     * No, though ClickHouse takes {@code CREATE OR REPLACE VIEW} on every version from 21.3 on, the first of the
     * images run: it replaces whatever is there under the name, a table too, which it drops with its rows, where
     * MySQL, Oracle and PostgreSQL refuse to replace anything but a view. With {@code orReplace} the dialect writes a
     * plain {@code CREATE VIEW}, which fails on anything there of the name and drops no table.
     */
    @Override
    public boolean supportsCreateOrReplaceView() {
        return false;
    }

    @Override
    public boolean requiresDrillthroughMaxRowsInLimit() {
        return true;
    }

    /**
     * ClickHouse rejects a bare {@code UNION} when the server-side
     * {@code union_default_mode} setting is empty (the default):
     * {@code Code: 558 DB::Exception: Expected ALL or DISTINCT in SelectWithUnion
     * query}. Spell the duplicate-eliminating union explicitly.
     *
     * <p>
     * {@code UNION DISTINCT} comes with 21.1. Before, ClickHouse has only {@code UNION ALL} and refuses both
     * spellings with a syntax error (Code 62): no keyword eliminates duplicates there.
     */
    @Override
    public String unionDistinctKeyword() {
        return "union distinct";
    }

    @Override
    public void quoteStringLiteral(StringBuilder buf, String s) {
        buf.append('\'');

        String s0 = s.replace("\\", "\\\\");
        s0 = s0.replace("'", "\\'");
        buf.append(s0);

        buf.append('\'');
    }

    @Override
    public String name() {
        return SUPPORTED_PRODUCT_NAME.toLowerCase();
    }

    // Unified BitOperation methods

    @Override
    public java.util.Optional<String> generateBitAggregation(BitOperation operation, CharSequence operand) {
        StringBuilder buf = new StringBuilder(64);
        StringBuilder result = switch (operation) {
        case AND -> buf.append("groupBitAnd(").append(operand).append(")");
        case OR -> buf.append("groupBitOr(").append(operand).append(")");
        case XOR -> buf.append("groupBitXor(").append(operand).append(")");
        case NAND -> buf.append("NOT(groupBitAnd(").append(operand).append("))");
        case NOR -> buf.append("NOT(groupBitOr(").append(operand).append("))");
        case NXOR -> buf.append("NOT(groupBitXor(").append(operand).append("))");
        };
        return java.util.Optional.of(result.toString());
    }

    @Override
    public boolean supportsBitAggregation(BitOperation operation) {
        return true; // ClickHouse supports all bit operations
    }

    /**
     * {@code arrayStringConcat(…, 'sep')} over the values of the group.
     *
     * <p>
     * ClickHouse has no ordered string aggregate: {@code groupConcat} comes only with 24.8 and takes no
     * {@code ORDER BY}, and {@code groupArrayArray}, written here before, takes arrays and refused a column of
     * anything else (Code 43). Without an order the list is {@code groupArray} ({@code groupUniqArray} for DISTINCT),
     * which leaves the NULLs out. With an order the group's rows are sorted by the columns, the NULL values
     * dropped (see {@link #sortedRows}). DISTINCT keeps the first place of a value. Every function used is in every
     * ClickHouse from 21.8 on.
     */
    @Override
    public java.util.Optional<String> generateListAgg(CharSequence operand, boolean distinct, String separator,
            String coalesce, String onOverflowTruncate, List<OrderedColumn> columns) {
        StringBuilder sep = new StringBuilder();
        quoteStringLiteral(sep, separator == null ? "," : separator);
        String values;
        if (columns == null || columns.isEmpty()) {
            values = "arrayMap(_v -> toString(_v), " + (distinct ? "groupUniqArray(" : "groupArray(") + operand
                    + "))";
        } else {
            String sorted = sortedRows(operand, true, columns);
            values = "arrayMap(_r -> toString(assumeNotNull(tupleElement(_r, 1))), " + sorted + ")";
            if (distinct) {
                values = "arrayDistinct(" + values + ")";
            }
        }
        return java.util.Optional.of("arrayStringConcat(" + values + ", " + sep + ")");
    }

    /**
     * The n-th value of the rows of the group, in the order of the columns: the n-th element of the group's rows
     * sorted as {@link #generateListAgg} sorts them, the NULL values counted or left out as asked. An aggregate, not
     * the window function {@code nth_value … OVER}, which in a grouped query runs over the groups (V-82). n counts
     * from 1, below 1 is 1; a group with fewer values gives NULL, not the type's default an array gives past its end.
     * Every function used is in every ClickHouse from 21.8 on.
     */
    @Override
    public java.util.Optional<String> generateNthValueAgg(CharSequence operand, boolean ignoreNulls, Integer n,
            List<OrderedColumn> columns) {
        int nth = AggregationGenerator.nthIndex(n);
        return java.util.Optional.of("arrayMap(_a -> if(length(_a) >= " + nth + ", tupleElement(_a[" + nth
                + "], 1), NULL), [" + sortedRows(operand, ignoreNulls, columns) + "])[1]");
    }

    @Override
    public boolean supportsNthValue() {
        return true;
    }

    @Override
    public boolean supportsNthValueIgnoreNulls() {
        return true;
    }

    /**
     * The rows of the group as {@code tuple(operand, key1, …)}, sorted by the columns, the rows of a NULL operand left
     * out where asked. ClickHouse has no ORDER BY in an aggregate: the group is collected with {@code groupArray}, and
     * sorted once per key, the last key first. {@code arraySort} is not stable, so each pass breaks ties by the place
     * in the pass before; {@code arrayReverseSort} is how a key sorts descending whatever its type. The NULLs of a key
     * go last unless the column asks for them first, as in ClickHouse's own {@code ORDER BY}.
     */
    private String sortedRows(CharSequence operand, boolean dropNullOperands, List<OrderedColumn> columns) {
        List<OrderedColumn> keys = columns == null ? List.of() : columns;
        StringBuilder rows = new StringBuilder("groupArray(tuple(").append(operand);
        for (OrderedColumn c : keys) {
            rows.append(", ");
            if (c.tableName() != null) {
                quoteIdentifier(rows, c.tableName(), c.columnName());
            } else {
                quoteIdentifier(rows, c.columnName());
            }
        }
        rows.append("))");
        String sorted = dropNullOperands ? "arrayFilter(_r -> isNotNull(tupleElement(_r, 1)), " + rows + ")"
                : rows.toString();
        for (int k = keys.size() - 1; k >= 0; k--) {
            OrderedColumn c = keys.get(k);
            boolean desc = c.sortDirection().orElse(SortDirection.ASC) == SortDirection.DESC;
            boolean nullsFirst = c.nullsOrder().orElse(NullsOrder.LAST) == NullsOrder.FIRST;
            String key = "tupleElement(_r, " + (k + 2) + ")";
            // arraySort puts the lower flag first, arrayReverseSort the higher one
            String flag = (nullsFirst != desc ? "isNotNull(" : "isNull(") + key + ")";
            sorted = "arrayMap(_a -> " + (desc ? "arrayReverseSort" : "arraySort") + "((_r, _i) -> (" + flag
                    + ", assumeNotNull(" + key + "), " + (desc ? "-_i" : "_i") + "), _a, arrayEnumerate(_a)), ["
                    + sorted + "])[1]";
        }
        return sorted;
    }

    /**
     * {@code PERCENTILE_DISC(p) WITHIN GROUP (ORDER BY c)}: the first value of the sorted group whose cumulative share
     * reaches p, the {@code max(1, ceil(p n))}-th of the group's n values that are not NULL. ClickHouse has no such
     * aggregate, and none of its quantiles gives it: {@code quantileExactLow(0.25)} of 10, 20, 30, 40 is 20, not 10.
     * An empty group gives NULL. Every function used is in every ClickHouse from 21.3 on.
     */
    @Override
    public java.util.Optional<String> generatePercentileDisc(double percentile, boolean desc, String tableName,
            String columnName) {
        return java.util.Optional.of("arrayMap(_a -> if(length(_a) = 0, NULL, _a[toUInt64(greatest(1, ceil("
                + percentile + " * length(_a))))]), [" + sortedGroup(desc, tableName, columnName) + "])[1]");
    }

    /**
     * {@code PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY c)}: the value at {@code 1 + p (n - 1)} of the sorted group,
     * interpolated between its two neighbours. {@code quantileExactInclusive(p)} is that from 23.8, but 21.x gives
     * the 2nd value for p = 1; the dialect computes it on the sorted group on every version. An empty group gives
     * NULL.
     */
    @Override
    public java.util.Optional<String> generatePercentileCont(double percentile, boolean desc, String tableName,
            String columnName) {
        String lo = "_a[toUInt64(floor(_r))]";
        String hi = "_a[toUInt64(ceil(_r))]";
        return java.util.Optional.of("arrayMap(_a -> if(length(_a) = 0, NULL, arrayMap(_r -> " + lo
                + " + (_r - floor(_r)) * (" + hi + " - " + lo + "), [1 + " + percentile + " * (length(_a) - 1)])[1]), ["
                + sortedGroup(desc, tableName, columnName) + "])[1]");
    }

    @Override
    public boolean supportsPercentileDisc() {
        return true;
    }

    @Override
    public boolean supportsPercentileCont() {
        return true;
    }

    /** The group's values that are not NULL, sorted ascending or descending: {@code groupArray} leaves NULLs out. */
    private String sortedGroup(boolean desc, String tableName, String columnName) {
        StringBuilder col = new StringBuilder();
        if (tableName != null) {
            quoteIdentifier(col, tableName, columnName);
        } else {
            quoteIdentifier(col, columnName);
        }
        return (desc ? "arrayReverseSort(" : "arraySort(") + "groupArray(" + col + "))";
    }

    @Override
    public boolean supportsListAgg() {
        return true;
    }

    /** ClickHouse: {@code RENAME TABLE a TO b} — there is no ALTER TABLE … RENAME TO. */
    @Override
    public String renameTable(TableReference table, String newName) {
        if (!supportsRenameTable()) {
            return null;
        }
        return new StringBuilder("RENAME TABLE ").append(qualified(table)).append(" TO ")
                .append(quoteIdentifier(newName)).toString();
    }

    /**
     * ClickHouse views are table-like; {@code RENAME TABLE} covers them. Renders
     * the statement directly rather than delegating to {@link #renameTable} so
     * the two capability flags stay independent.
     */
    @Override
    public String renameView(TableReference view, String newName) {
        if (!supportsRenameView()) {
            return null;
        }
        return new StringBuilder("RENAME TABLE ").append(qualified(view)).append(" TO ")
                .append(quoteIdentifier(newName)).toString();
    }

    /**
     * No. ClickHouse takes {@code RENAME TABLE a TO b, c TO d} as one statement, but runs the renames one after the
     * other and undoes none when a later one fails: the tables renamed before stay renamed, on every version and in
     * an Atomic database too. Only {@code EXCHANGE TABLES} swaps atomically, which a list of renames does not say.
     * {@code renameTables} writes one {@code RENAME TABLE} per rename, as it says where there is no atomicity.
     */
    @Override
    public boolean supportsAtomicMultiRenameTable() {
        return false;
    }

    /**
     * {@code ALTER TABLE t MODIFY COMMENT '…'}, for a view too ({@link #supportsCommentOnView}); {@code null} clears
     * it. {@code MODIFY COMMENT} comes with 21.11, 21.10 refuses it with a syntax error (Code 62); before, no comment
     * is written. A dialect made without a connection takes such a version.
     */
    @Override
    public java.util.Optional<String> commentOnTable(TableReference table, String comment) {
        boolean view = TableReference.TYPE_VIEW.equals(table.type());
        if (view ? !supportsCommentOnView() : !dialectVersion.isUnknownOrAtLeast(21, 11)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of("ALTER TABLE " + qualified(table) + " MODIFY COMMENT " + chLiteral(comment));
    }

    /**
     * {@code ALTER TABLE t COMMENT COLUMN c '…'}; {@code null} clears it. A view's column from 22.1, as the comment
     * of a view ({@link #supportsCommentOnView}).
     */
    @Override
    public java.util.Optional<String> commentOnColumn(TableReference table, String columnName, String comment,
            org.eclipse.daanse.sql.model.schema.ColumnMetaData currentMeta) {
        if (TableReference.TYPE_VIEW.equals(table.type()) && !supportsCommentOnView()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of("ALTER TABLE " + qualified(table) + " COMMENT COLUMN "
                + quoteIdentifier(columnName) + " " + chLiteral(comment));
    }

    private static String chLiteral(String comment) {
        String s = comment == null ? "" : comment;
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** ClickHouse has no named table constraints; the primary key stays unnamed. */
    @Override
    public boolean supportsNamedPrimaryKey() {
        return false;
    }

    /**
     * ClickHouse lets a user without a password in from any host, so a user is created with no host
     * to come from, {@code CREATE USER "x" HOST NONE}, and logs in once it is altered to have a
     * password and hosts.
     *
     * <p>
     * Users, as all access control by SQL, come with 20.4. A dialect made without a connection takes such a version.
     */
    @Override
    public boolean supportsUsers() {
        return dialectVersion.isUnknownOrAtLeast(20, 4);
    }

    /**
     * Roles, {@code CREATE ROLE [IF NOT EXISTS]}, {@code DROP ROLE [IF EXISTS]}, granted to a user with the admin
     * option too, come with 20.4, as all access control by SQL. A dialect made without a connection takes such a
     * version.
     */
    @Override
    public boolean supportsRoles() {
        return dialectVersion.isUnknownOrAtLeast(20, 4);
    }

    @Override
    public Optional<String> createUser(String userName, boolean ifNotExists) {
        if (!supportsUsers()) {
            return Optional.empty();
        }
        return Optional.of("CREATE USER " + (ifNotExists ? "IF NOT EXISTS " : "") + quoteIdentifier(userName)
                + " HOST NONE");
    }

    /**
     * From 22.1. {@code ALTER TABLE … MODIFY COMMENT} takes a view as a table from 21.11, and {@code COMMENT COLUMN}
     * a view's column, but 21.11 and 21.12 write the comment of a view after its query into the view's metadata,
     * which they cannot read again: the next ALTER of the view fails with a syntax error in its {@code .sql} file,
     * and the server does not start again. A dialect made without a connection takes such a version.
     */
    @Override
    public boolean supportsCommentOnView() {
        return dialectVersion.isUnknownOrAtLeast(22, 1);
    }
}
