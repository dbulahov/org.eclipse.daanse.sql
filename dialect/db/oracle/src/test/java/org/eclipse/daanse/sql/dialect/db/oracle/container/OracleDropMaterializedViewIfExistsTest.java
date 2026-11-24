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
 * Oracle drops a materialized view only if it exists, {@code DROP MATERIALIZED VIEW IF EXISTS}, from 23 (23ai) on. The
 * dialect is built from a live connection: from 23 on it writes IF EXISTS, the view it drops is gone and the drop of a
 * missing one passes; before, on 18 and 21, it writes a plain DROP MATERIALIZED VIEW, for the server refuses IF EXISTS,
 * and the drop of a missing view fails with ORA-12003.
 */
@EnabledIfSystemProperty(named = "integration.docker", matches = "true")
class OracleDropMaterializedViewIfExistsTest {

    private static final String SELECT = "SELECT REGION, SUM(AMOUNT) AS TOTAL FROM SALES GROUP BY REGION";

    @ParameterizedTest(name = "{0} -> DROP MATERIALIZED VIEW IF EXISTS {1}")
    @CsvSource({ "gvenzl/oracle-xe:18-slim-faststart, false", "gvenzl/oracle-xe:21-slim-faststart, false",
            "gvenzl/oracle-free:23.9-slim-faststart, true",
            "gvenzl/oracle-free:23.26.4-slim-faststart, true" })
    void dropMaterializedViewIfExists(String image, boolean expected) throws Exception {
        try (JdbcDatabaseContainer<?> ora = OracleContainers.of(image)) {
            ora.start();
            try (Connection c = DriverManager.getConnection(ora.getJdbcUrl(), ora.getUsername(), ora.getPassword());
                    Statement st = c.createStatement()) {
                String schema = ora.getUsername().toUpperCase();
                TableReference totals = new TableReference(Optional.of(new SchemaReference(Optional.empty(), schema)),
                        "REGION_TOTALS", TableReference.TYPE_VIEW);
                st.execute("CREATE TABLE SALES (REGION VARCHAR2(10), AMOUNT NUMBER)");
                st.execute("INSERT INTO SALES VALUES ('EU', 10)");
                OracleDialect dialect = new OracleDialect(DialectInitData.fromConnection(c));

                st.execute(dialect.createMaterializedView(totals, SELECT, false).orElseThrow());
                assertThat(exists(st)).as("created").isTrue();
                String drop = dialect.dropMaterializedView(totals, true).orElseThrow();

                if (!expected) {
                    assertThat(drop).as("a plain drop before 23")
                            .isEqualTo("DROP MATERIALIZED VIEW \"" + schema + "\".\"REGION_TOTALS\"");
                    assertThatThrownBy(() -> st.execute("DROP MATERIALIZED VIEW IF EXISTS REGION_TOTALS"))
                            .as("IF EXISTS is refused before 23").isInstanceOf(SQLException.class);
                    assertThat(exists(st)).as("the refused drop leaves the view").isTrue();
                    st.execute(drop);
                    assertThat(exists(st)).as("dropped").isFalse();
                    assertThatThrownBy(() -> st.execute(drop)).as("a missing one")
                            .isInstanceOf(SQLException.class).hasMessageContaining("ORA-12003");
                    return;
                }

                assertThat(drop).as("IF EXISTS from 23")
                        .isEqualTo("DROP MATERIALIZED VIEW IF EXISTS \"" + schema + "\".\"REGION_TOTALS\"");
                st.execute(drop);
                assertThat(exists(st)).as("dropped").isFalse();
                st.execute(drop);
                assertThatThrownBy(() -> st.execute(dialect.dropMaterializedView(totals, false).orElseThrow()))
                        .as("a missing one without IF EXISTS").isInstanceOf(SQLException.class)
                        .hasMessageContaining("ORA-12003");
            }
        }
    }

    private static boolean exists(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM USER_MVIEWS WHERE MVIEW_NAME = 'REGION_TOTALS'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }
}
