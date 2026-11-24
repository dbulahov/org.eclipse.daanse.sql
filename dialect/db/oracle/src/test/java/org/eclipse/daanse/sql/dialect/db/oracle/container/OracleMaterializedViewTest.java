/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.oracle.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.oracle.OracleDialect;
import org.eclipse.daanse.sql.model.schema.SchemaReference;
import org.eclipse.daanse.sql.model.schema.TableReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Oracle has materialized views of old, and {@code CREATE MATERIALIZED VIEW IF NOT EXISTS} from 23 (23ai) on. The
 * dialect is built from a live connection: on every version the view it creates keeps the rows of its creation until
 * it is refreshed and is dropped again; from 23 on it writes IF NOT EXISTS, which leaves an existing view as it is;
 * before, on 18 and 21, it leaves it out, for the server refuses it, and the statement fails on an existing view with
 * ORA-12006, as the contract says.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class OracleMaterializedViewTest {

    private static final String SELECT = "SELECT REGION, SUM(AMOUNT) AS TOTAL FROM SALES GROUP BY REGION";
    private static final String READ = "SELECT REGION, TOTAL FROM REGION_TOTALS ORDER BY REGION";

    @ParameterizedTest(name = "{0} -> IF NOT EXISTS {1}")
    @CsvSource({ "gvenzl/oracle-xe:18-slim-faststart, false", "gvenzl/oracle-xe:21-slim-faststart, false",
            "gvenzl/oracle-free:23.9-slim-faststart, true",
            "gvenzl/oracle-free:23.26.4-slim-faststart, true" })
    void materializedView(String image, boolean ifNotExists) throws Exception {
        try (JdbcDatabaseContainer<?> ora = OracleContainers.of(image)) {
            ora.start();
            try (Connection c = DriverManager.getConnection(ora.getJdbcUrl(), ora.getUsername(), ora.getPassword());
                    Statement st = c.createStatement()) {
                String schema = ora.getUsername().toUpperCase();
                TableReference totals = new TableReference(Optional.of(new SchemaReference(Optional.empty(), schema)),
                        "REGION_TOTALS", TableReference.TYPE_VIEW);
                st.execute("CREATE TABLE SALES (REGION VARCHAR2(10), AMOUNT NUMBER)");
                st.execute("INSERT INTO SALES VALUES ('EU', 10)");
                st.execute("INSERT INTO SALES VALUES ('EU', 20)");
                st.execute("INSERT INTO SALES VALUES ('US', 30)");
                OracleDialect dialect = new OracleDialect(DialectInitData.fromConnection(c));

                assertThat(dialect.supportsMaterializedViews()).isTrue();

                String create = dialect.createMaterializedView(totals, SELECT, false).orElseThrow();
                assertThat(create).isEqualTo(
                        "CREATE MATERIALIZED VIEW \"" + schema + "\".\"REGION_TOTALS\" AS " + SELECT);
                st.execute(create);
                assertThat(rows(st, READ)).as("the rows of its creation").containsExactly("EU=30", "US=30");

                st.execute("INSERT INTO SALES VALUES ('US', 5)");
                assertThat(rows(st, READ)).as("kept until it is refreshed").containsExactly("EU=30", "US=30");
                st.execute("BEGIN DBMS_MVIEW.REFRESH('REGION_TOTALS'); END;");
                assertThat(rows(st, READ)).as("refreshed").containsExactly("EU=30", "US=35");

                assertThatThrownBy(() -> st.execute(create)).as("a second one of the name")
                        .isInstanceOf(SQLException.class).hasMessageContaining("ORA-12006");

                String again = dialect.createMaterializedView(totals, SELECT, true).orElseThrow();
                if (ifNotExists) {
                    assertThat(again).as("IF NOT EXISTS from 23").isEqualTo(
                            "CREATE MATERIALIZED VIEW IF NOT EXISTS \"" + schema + "\".\"REGION_TOTALS\" AS " + SELECT);
                    st.execute(again);
                    assertThat(rows(st, READ)).as("IF NOT EXISTS left it").containsExactly("EU=30", "US=35");
                } else {
                    assertThat(again).as("no IF NOT EXISTS before 23").isEqualTo(create);
                    assertThatThrownBy(
                            () -> st.execute("CREATE MATERIALIZED VIEW IF NOT EXISTS REGION_TOTALS AS " + SELECT))
                            .as("IF NOT EXISTS is refused before 23").isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> st.execute(again)).as("without IF NOT EXISTS it fails on it")
                            .isInstanceOf(SQLException.class).hasMessageContaining("ORA-12006");
                    assertThat(rows(st, READ)).as("the failed create left it").containsExactly("EU=30", "US=35");
                }

                st.execute(dialect.dropMaterializedView(totals, false).orElseThrow());
                assertThatThrownBy(() -> rows(st, READ)).as("dropped").isInstanceOf(SQLException.class)
                        .hasMessageContaining("ORA-00942");
            }
        }
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1) + "=" + rs.getInt(2));
            }
        }
        return out;
    }
}
