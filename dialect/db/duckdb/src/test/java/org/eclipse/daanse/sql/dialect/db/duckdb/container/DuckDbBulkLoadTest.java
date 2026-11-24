/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.duckdb.container;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.daanse.sql.dialect.api.generator.BulkLoadGenerator;
import org.eclipse.daanse.sql.dialect.db.duckdb.DuckDbDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * DuckDB loads a delimited file itself with {@code INSERT INTO t (cols) SELECT * FROM read_csv(…)}, in every version
 * from 1.0 on. The CLI of each version runs the statement the dialect writes, in Docker, on files put into the
 * container, and the dialect is built for the version the CLI reports. Files have the CSV importer's form: a line of
 * the column names and a line of their SQL types before the data, the NULL literal {@code NULL}. Every value must
 * arrive as the file has it, in the type of its column: a text of leading zeros or of a number stays the text, quoted
 * values keep their delimiters and quotes, the NULL literal is NULL; with a semicolon, a tab or a quote as delimiter,
 * a quote in the file's path and in the NULL literal, no line or one line before the data, a column of the table left
 * to its default, and past the rows read to guess the types. A blank line is left out; a comment line of {@code #} is
 * left out from 1.1 on, while 1.0 refuses the file, so the importer loads it row by row.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class DuckDbBulkLoadTest {

    private static final Optional<SchemaReference> MAIN = Optional.of(new SchemaReference(Optional.empty(), "main"));

    @ParameterizedTest(name = "duckdb:{0} -> comment lines left out {1}")
    @CsvSource({ "1.0.0, false", "1.1.3, true", "1.2.2, true", "1.3.2, true", "1.4.4, true", "1.5.6, true",
            "2.0.1, true" })
    void bulkLoad(String tag, boolean commentsLeftOut) {
        DuckDbDialect dialect = DuckDbCli.dialect(tag);
        BulkLoadGenerator bulk = dialect.bulkLoadGenerator();
        assertThat(bulk.supportsBulkLoad()).as("supportsBulkLoad").isTrue();

        Map<String, String> files = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder();
        Map<String, String> expected = new LinkedHashMap<>();

        // the importer's form, values the guessing of types could change, quoted values
        files.put("/data/items.csv", "id,code,price,label,day,flag\n"
                + "INTEGER,VARCHAR,DECIMAL(10,2),VARCHAR,DATE,SMALLINT\n"
                + "1,007,1.50,\"a, b\",2024-01-05,1\n"
                + "2,NULL,2.00,\"say \"\"hi\"\"\",NULL,0\n"
                + "3,0042,NULL,1.50,2024-02-29,NULL\n");
        String load = load(bulk, "items", List.of("id", "code", "price", "label", "day", "flag"), "/data/items.csv",
                ',', 2, "NULL");
        assertThat(load).isEqualTo("INSERT INTO \"main\".\"items\" (\"id\", \"code\", \"price\", \"label\", \"day\","
                + " \"flag\") SELECT * FROM read_csv('/data/items.csv', header=false, skip=2, delim=',',"
                + " nullstr='NULL')");
        sql.append("CREATE TABLE items(id INTEGER, code VARCHAR, price DECIMAL(10,2), label VARCHAR, day DATE,"
                + " flag SMALLINT); ").append(load).append(";");
        read(sql, "items", "id || '|' || " + shown("code") + " || '|' || " + shown("price") + " || '|' || label"
                + " || '|' || " + shown("day") + " || '|' || " + shown("flag"), "id");
        expected.put("items", "1|007|1.50|a, b|2024-01-05|1;2|null|2.00|say \"hi\"|null|0;3|0042|null|1.50|2024-02-29|null");

        // a semicolon, a tab and a quote as delimiter
        files.put("/data/semi.csv", "a;b\nINTEGER;VARCHAR\n1;it's\n2;x\n");
        table(sql, bulk, "semi", "/data/semi.csv", ';', 2, "NULL");
        expected.put("semi", "1|it's;2|x");
        files.put("/data/tab.csv", "a\tb\nINTEGER\tVARCHAR\n1\tp q\n");
        table(sql, bulk, "tab", "/data/tab.csv", '\t', 2, "NULL");
        expected.put("tab", "1|p q");
        files.put("/data/quote.csv", "a'b\nINTEGER'VARCHAR\n1'x\n2'NULL\n");
        table(sql, bulk, "quote", "/data/quote.csv", '\'', 2, "NULL");
        expected.put("quote", "1|x;2|null");

        // a quote in the path, other NULL literals, a quote among them
        files.put("/data/o'brien dir/f.csv", "a,b\nINTEGER,VARCHAR\n5,y\n");
        table(sql, bulk, "path", "/data/o'brien dir/f.csv", ',', 2, "NULL");
        expected.put("path", "5|y");
        files.put("/data/backslash.csv", "a,b\nINTEGER,VARCHAR\n1,\\N\n2,NULL\n");
        table(sql, bulk, "backslash", "/data/backslash.csv", ',', 2, "\\N");
        expected.put("backslash", "1|null;2|NULL");
        files.put("/data/na.csv", "a,b\nINTEGER,VARCHAR\n1,n'a\n2,z\n");
        table(sql, bulk, "na", "/data/na.csv", ',', 2, "n'a");
        expected.put("na", "1|null;2|z");

        // no line and one line before the data
        files.put("/data/none.csv", "1,x\n2,y\n");
        table(sql, bulk, "none", "/data/none.csv", ',', 0, "NULL");
        expected.put("none", "1|x;2|y");
        files.put("/data/one.csv", "a,b\n1,x\n");
        table(sql, bulk, "one", "/data/one.csv", ',', 1, "NULL");
        expected.put("one", "1|x");

        // a column of the table not in the file keeps its default
        files.put("/data/extra.csv", "a,b\nINTEGER,VARCHAR\n1,x\n");
        sql.append(" CREATE TABLE extra(a INTEGER, b VARCHAR, loaded VARCHAR DEFAULT 'yes'); ")
                .append(load(bulk, "extra", List.of("a", "b"), "/data/extra.csv", ',', 2, "NULL")).append(";");
        read(sql, "extra", "a || '|' || b || '|' || loaded", "a");
        expected.put("extra", "1|x|yes");

        // a text first past the rows read to guess the types
        files.put("/data/big.csv", "a,b\nINTEGER,VARCHAR\n"
                + IntStream.range(0, 30000).mapToObj(i -> i + "," + i).collect(Collectors.joining("\n"))
                + "\n30000,abc\n");
        sql.append(" CREATE TABLE big(a INTEGER, b VARCHAR); ")
                .append(load(bulk, "big", List.of("a", "b"), "/data/big.csv", ',', 2, "NULL")).append(";")
                .append(" SELECT 'big=' || count(*) || '|' || max(a) || '|' || (SELECT b FROM big WHERE a = 30000)")
                .append(" FROM big;");
        expected.put("big", "30001|30000|abc");

        // a blank line
        files.put("/data/blank.csv", "a,b\nINTEGER,VARCHAR\n1,x\n\n2,y\n");
        table(sql, bulk, "blank", "/data/blank.csv", ',', 2, "NULL");
        expected.put("blank", "1|x;2|y");

        DuckDbCli.Result r = DuckDbCli.run(tag, sql.toString(), files);
        assertThat(r.failed()).as("every file loads: " + r.err()).isFalse();
        Map<String, String> got = new LinkedHashMap<>();
        for (String line : r.out()) {
            int eq = line.indexOf('=');
            got.put(line.substring(0, eq), line.substring(eq + 1));
        }
        assertThat(got).as("the rows of every file").containsExactlyEntriesOf(expected);

        // a comment line: left out from 1.1 on; 1.0 refuses the file
        String comment = "a,b\nINTEGER,VARCHAR\n1,x\n# a comment\n2,y\n";
        StringBuilder commented = new StringBuilder();
        table(commented, bulk, "commented", "/data/commented.csv", ',', 2, "NULL");
        DuckDbCli.Result c = DuckDbCli.run(tag, commented.toString(), Map.of("/data/commented.csv", comment));
        if (commentsLeftOut) {
            assertThat(c.failed()).as(c.err()).isFalse();
            assertThat(c.out()).as("the comment line left out").containsExactly("commented=1|x;2|y");
        } else {
            assertThat(c.failed()).as("1.0 refuses a file with a comment line").isTrue();
            assertThat(c.err()).contains("sniffing");
        }
    }

    /** A table of an INTEGER a and a VARCHAR b, the file loaded into it, and its rows read. */
    private static void table(StringBuilder sql, BulkLoadGenerator bulk, String name, String file, char delimiter,
            int skip, String nullLiteral) {
        sql.append(" CREATE TABLE \"").append(name).append("\"(a INTEGER, b VARCHAR); ")
                .append(load(bulk, name, List.of("a", "b"), file, delimiter, skip, nullLiteral)).append(";");
        read(sql, name, "a || '|' || " + shown("b"), "a");
    }

    private static String load(BulkLoadGenerator bulk, String table, List<String> columns, String file,
            char delimiter, int skip, String nullLiteral) {
        return bulk.loadFromDelimitedFile(new TableReference(MAIN, table), columns, Path.of(file), delimiter, skip,
                nullLiteral).orElseThrow();
    }

    /** One line, {@code name=} and the rows, each as the expression gives it, joined with ; in their order. */
    private static void read(StringBuilder sql, String name, String row, String order) {
        sql.append(" SELECT '").append(name).append("=' || string_agg(").append(row).append(", ';' ORDER BY ")
                .append(order).append(") FROM \"").append(name).append("\";");
    }

    /** The CLI writes NULL as an empty field in 1.0 and as NULL later: a NULL is shown as null. */
    private static String shown(String column) {
        return "COALESCE(CAST(" + column + " AS VARCHAR), 'null')";
    }
}
