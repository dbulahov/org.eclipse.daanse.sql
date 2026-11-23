/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.mysql.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.mysql.MySqlDialect;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * MDX {@code MATCHES} is Java's {@code Pattern.matches}: the whole value, case-sensitive unless the expression says
 * {@code (?i)}. The dialect is built from a live connection, and the condition it writes must pick on the server the
 * rows Java would: anchored at both ends, the case kept or ignored as asked, whatever collation the column has, a dot
 * escaped, a NULL never; and an expression Java cannot read gives no condition. Before MySQL 8.0.4 REGEXP is Henry
 * Spencer's, which knows none of the ICU constructs the condition needs: the dialect must write none, and leave the
 * expression to be matched in Java.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class MySqlRegularExpressionTest {

    /** The server's default collation, case-insensitive (latin1_swedish_ci on 5.7, utf8mb4_0900_ai_ci from 8.0). */
    private static final String CREATE = "CREATE TABLE words(id INT PRIMARY KEY, w VARCHAR(20))";
    private static final String INSERT = """
            INSERT INTO words VALUES (1, 'apple'), (2, 'Apple'), (3, 'APPLE'), (4, 'pineapple'), (5, 'banana'),
                (6, 'a.b'), (7, 'axb'), (8, NULL)""";

    private static final List<String> EXPRESSIONS = List.of("apple", "a.*", "(?i)a.*", "(?i)apple", ".*apple", "A.*",
            "a\\.b", "[a-c].*", "ban(an)+a", "apple|banana");

    @ParameterizedTest(name = "mysql:{0} -> in SQL {1}")
    @CsvSource({ "5.7, false", "8.0, true", "8.4, true", "9, true" })
    void regular_expression(String tag, boolean inSql) throws Exception {
        // the server's own configuration: Testcontainers' default sets innodb_log_file_size, which MySQL 9 refuses
        try (MySQLContainer<?> my = new MySQLContainer<>("mysql:" + tag).withConfigurationOverride("mysql-conf")
                .withDatabaseName("rt").withUsername("rt").withPassword("rt")) {
            my.start();
            try (Connection c = DriverManager.getConnection(my.getJdbcUrl(), my.getUsername(), my.getPassword());
                    Statement st = c.createStatement()) {
                st.execute(CREATE);
                st.execute(INSERT);
                MySqlDialect dialect = new MySqlDialect(DialectInitData.fromConnection(c));
                assertThat(dialect.allowsRegularExpressionInWhereClause()).isEqualTo(inSql);

                if (!inSql) {
                    for (String javaRegex : EXPRESSIONS) {
                        assertThat(dialect.regexGenerator().generateRegularExpression("w", javaRegex))
                                .as(javaRegex + " is not written for Henry Spencer's REGEXP").isEmpty();
                    }
                    assertThatThrownBy(() -> st.executeQuery("SELECT w FROM words WHERE w REGEXP '(?-i)\\\\A(?:a)\\\\z'"))
                            .as("5.7 refuses the ICU constructs").isInstanceOf(SQLException.class)
                            .hasMessageContaining("repetition-operator operand invalid");
                    return;
                }

                assertMatches(st, dialect, "apple", "apple");
                assertMatches(st, dialect, "a.*", "apple", "a.b", "axb");
                assertMatches(st, dialect, "(?i)a.*", "apple", "Apple", "APPLE", "a.b", "axb");
                assertMatches(st, dialect, "(?i)apple", "apple", "Apple", "APPLE");
                assertMatches(st, dialect, ".*apple", "apple", "pineapple");
                assertMatches(st, dialect, "A.*", "Apple", "APPLE");
                assertMatches(st, dialect, "a\\.b", "a.b");
                assertMatches(st, dialect, "[a-c].*", "apple", "banana", "a.b", "axb");
                assertMatches(st, dialect, "ban(an)+a", "banana");
                assertMatches(st, dialect, "apple|banana", "apple", "banana");

                assertThat(dialect.regexGenerator().generateRegularExpression("w", "a(")).as("not a Java expression")
                        .isEmpty();
            }
        }
    }

    private static void assertMatches(Statement st, MySqlDialect dialect, String javaRegex, String... expected)
            throws SQLException {
        String condition = dialect.regexGenerator().generateRegularExpression("w", javaRegex).orElseThrow()
                .toString();
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT w FROM words WHERE " + condition + " ORDER BY id")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        assertThat(out).as(javaRegex + " -> " + condition).containsExactly(expected);
    }
}
