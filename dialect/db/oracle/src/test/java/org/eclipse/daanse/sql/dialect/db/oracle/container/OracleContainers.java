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

import java.time.Duration;

import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * A container of an Oracle image: Oracle XE (11, 18, 21) and Oracle Free (23) are served by two Testcontainers modules.
 * The password of the container's user is that of SYS and SYSTEM too. Oracle 11g has no pluggable database: it is
 * reached by its SID, XE.
 */
final class OracleContainers {

    private OracleContainers() {
    }

    @SuppressWarnings("resource")
    static JdbcDatabaseContainer<?> of(String image) {
        if (image.contains("oracle-free")) {
            return new org.testcontainers.oracle.OracleContainer(image).withStartupTimeout(Duration.ofMinutes(5));
        }
        org.testcontainers.containers.OracleContainer xe = new org.testcontainers.containers.OracleContainer(image)
                .withUsername("rt").withPassword("rt");
        if (image.contains("oracle-xe:11")) {
            xe = xe.usingSid();
        }
        return xe.withStartupTimeout(Duration.ofMinutes(5));
    }
}
