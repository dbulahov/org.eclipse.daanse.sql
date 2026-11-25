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

import org.testcontainers.clickhouse.ClickHouseContainer;

/** What the Docker tests of the ClickHouse dialect share. */
final class ClickHouseContainers {

    private ClickHouseContainers() {
    }

    /** A container of the server of the image tag, from {@code clickhouse/clickhouse-server} (21.3 and later). */
    static ClickHouseContainer container(String tag) {
        return new ClickHouseContainer("clickhouse/clickhouse-server:" + tag);
    }

    /**
     * The JDBC URL of the container for a server of the image tag. The driver's default implementation sends
     * {@code async_insert} with every DDL and INSERT, a setting ClickHouse has from 21.11 on; an older server refuses
     * it with Code 115 and the driver reports a 404. Its V1 implementation does not send it.
     */
    static String jdbcUrl(ClickHouseContainer ch, String tag) {
        return before(tag, 21, 11) ? ch.getJdbcUrl() + "?clickhouse.jdbc.v1=true" : ch.getJdbcUrl();
    }

    /** Whether the image tag names a version before {@code major.minor}. */
    static boolean before(String tag, int major, int minor) {
        String[] v = tag.split("\\.");
        int tagMajor = Integer.parseInt(v[0]);
        int tagMinor = v.length > 1 ? Integer.parseInt(v[1]) : 0;
        return tagMajor < major || (tagMajor == major && tagMinor < minor);
    }
}
