/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.clickhouse.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.clickhouse.ClickHouseDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * MDX {@code MATCHES} is Java's {@code Pattern.matches}: the whole value, case-sensitive unless the expression says
 * {@code (?i)}, a dot no line break. ClickHouse's {@code match} is RE2's, on every version. The dialect is built from
 * a live connection, and the condition it writes must pick on the server the rows Java picks: anchored at both ends,
 * the case kept or ignored, a dot escaped or taking no line break, {@code \Q…\E}, {@code \d}, {@code \w}, a NULL never.
 * An expression with what RE2 has not, lookaround, a back reference, a possessive quantifier, which the server
 * refuses, or a class intersection, which it reads otherwise, gives no condition, nor one Java cannot read: it is
 * matched in Java.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class ClickHouseRegularExpressionTest {

    private static final List<String> WORDS = Arrays.asList("apple", "Apple", "APPLE", "pineapple", "banana", "a.b",
            "axb", "a\nb", "a+b", "a1 b", null);

    private static final List<String> EXPRESSIONS = List.of("apple", "a.*", "(?i)a.*", "(?i)apple", ".*apple", "A.*",
            "a\\.b", "a.b", "[a-c].*", "ban(an)+a", "apple|banana", "\\Qa+b\\E", "\\w\\d\\s\\w", "(?s)a.b",
            "(?iu)APPLE", "(?i:a)pple");

    /** What RE2 has not, which the server refuses. */
    private static final List<String> NOT_RE2 = List.of("a(?=p).*", "(?<=a)pple", "(a)\\1.*", "a++pple",
            "(?>a)pple");

    @ParameterizedTest(name = "clickhouse:{0}")
    @ValueSource(strings = { "21.3", "21.8", "23.8", "24.8", "25.8", "26.6" })
    void regular_expression(String tag) throws Exception {
        try (ClickHouseContainer ch = ClickHouseContainers.container(tag)) {
            ch.start();
            try (Connection c = DriverManager.getConnection(ClickHouseContainers.jdbcUrl(ch, tag), ch.getUsername(),
                    ch.getPassword()); Statement st = c.createStatement()) {
                st.execute("CREATE TABLE words(id Int32, w Nullable(String)) ENGINE = MergeTree() ORDER BY id");
                StringBuilder insert = new StringBuilder("INSERT INTO words VALUES ");
                for (int i = 0; i < WORDS.size(); i++) {
                    String w = WORDS.get(i);
                    insert.append(i == 0 ? "" : ", ").append('(').append(i).append(", ")
                            .append(w == null ? "NULL" : "'" + w.replace("\n", "\\n") + "'").append(')');
                }
                st.execute(insert.toString());
                ClickHouseDialect dialect = new ClickHouseDialect(DialectInitData.fromConnection(c));
                assertThat(dialect.allowsRegularExpressionInWhereClause()).isTrue();

                for (String javaRegex : EXPRESSIONS) {
                    assertMatchesAsJava(st, dialect, javaRegex);
                }
                assertThat(matching(st, dialect, "a.b")).as("a dot takes no line break").doesNotContain("a\nb");

                for (String javaRegex : NOT_RE2) {
                    assertThat(dialect.regexGenerator().generateRegularExpression("w", javaRegex))
                            .as(javaRegex + " has what RE2 has not").isEmpty();
                    StringBuilder raw = new StringBuilder("SELECT w FROM words WHERE match(w, ");
                    dialect.quoteStringLiteral(raw, javaRegex);
                    assertThatThrownBy(() -> st.executeQuery(raw.append(")").toString()))
                            .as("the server refuses " + javaRegex).isInstanceOf(SQLException.class);
                }
                String intersection = "[a-z&&[^b]]+";
                assertThat(dialect.regexGenerator().generateRegularExpression("w", intersection))
                        .as("a class intersection").isEmpty();
                assertThat(rows(st, "SELECT toString(match('apple', '\\\\A(?:" + intersection + ")\\\\z'))"))
                        .as("RE2 takes it, and reads it otherwise than Java, which matches apple").containsExactly("0");
                assertThat(dialect.regexGenerator().generateRegularExpression("w", "a(")).as("not a Java expression")
                        .isEmpty();
            }
        }
    }

    /** The rows the condition picks are the rows Java's {@code Pattern.matches} picks. */
    private static void assertMatchesAsJava(Statement st, ClickHouseDialect dialect, String javaRegex)
            throws SQLException {
        Pattern p = Pattern.compile(javaRegex);
        List<String> java = WORDS.stream().filter(w -> w != null && p.matcher(w).matches()).toList();
        assertThat(java).as(javaRegex + " picks some row").isNotEmpty();
        assertThat(matching(st, dialect, javaRegex)).as(javaRegex + " as Java").containsExactlyElementsOf(java);
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static List<String> matching(Statement st, ClickHouseDialect dialect, String javaRegex)
            throws SQLException {
        String condition = dialect.regexGenerator().generateRegularExpression("w", javaRegex).orElseThrow()
                .toString();
        String sql = "SELECT w FROM words WHERE " + condition + " ORDER BY id";
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new SQLException(e.getMessage() + " in " + sql, e);
        }
        return out;
    }
}
