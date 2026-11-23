/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.api.DialectVersion;
import org.eclipse.daanse.sql.dialect.api.IdentifierQuotingPolicy;
import org.junit.jupiter.api.Test;

/**
 * The version a dialect checks carries the patch of the product version, where that names the same major and minor:
 * a check of a patch ({@code 5.7.8}, {@code 8.0.19}) took every version of its minor for its {@code .0} before.
 */
class DialectInitDataVersionTest {

    private static DialectInitData init(String productVersion, int major, int minor) {
        return new DialectInitData("`", "MySQL", productVersion, major, minor, Set.of(), true, 0, Set.of(),
                IdentifierQuotingPolicy.ALWAYS);
    }

    @Test
    void the_patch_of_the_product_version() {
        assertThat(init("8.0.44", 8, 0).version()).isEqualTo(new DialectVersion(8, 0, 44));
        assertThat(init("5.7.44-log", 5, 7).version()).isEqualTo(new DialectVersion(5, 7, 44));
        assertThat(init("11.4.2-MariaDB-ubu2404", 11, 4).version()).isEqualTo(new DialectVersion(11, 4, 2));
        assertThat(init("16.4 (Debian 16.4-1.pgdg120+2)", 16, 4).version()).isEqualTo(new DialectVersion(16, 4, 0));
    }

    @Test
    void a_check_of_a_patch() {
        DialectVersion v8018 = init("8.0.18", 8, 0).version();
        DialectVersion v8019 = init("8.0.19", 8, 0).version();
        assertThat(v8018.atLeast(new DialectVersion(8, 0, 19))).isFalse();
        assertThat(v8019.atLeast(new DialectVersion(8, 0, 19))).isTrue();
        assertThat(init("5.7.7", 5, 7).version().atLeast(new DialectVersion(5, 7, 8))).isFalse();
        assertThat(init("5.7.44", 5, 7).version().atLeast(new DialectVersion(5, 7, 8))).isTrue();
        assertThat(v8019.atLeast(8, 0)).as("a check of a minor as before").isTrue();
    }

    @Test
    void no_patch_of_a_product_version_that_names_another_version() {
        assertThat(init("5.5.5-10.11.6-MariaDB", 10, 11).version()).isEqualTo(new DialectVersion(10, 11, 0));
        assertThat(init("8.0.44", 8, 0).withVersion(5, 7).version()).as("withVersion names another version")
                .isEqualTo(new DialectVersion(5, 7, 0));
    }

    @Test
    void no_version_is_unknown() {
        assertThat(DialectInitData.ansiDefaults().version()).isEqualTo(DialectVersion.UNKNOWN);
        assertThat(init("8.0.44", 0, 0).version()).isEqualTo(DialectVersion.UNKNOWN);
    }
}
