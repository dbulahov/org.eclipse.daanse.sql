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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.duckdb.DuckDbDialect;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame.OutputType;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.images.builder.Transferable;

import com.github.dockerjava.api.DockerClient;

/**
 * The DuckDB CLI of a given version, run in Docker ({@code duckdb/duckdb:<tag>}, the CLI alone, without a shell).
 * DuckDB is embedded and serves no connection: each call runs its statements in a database in memory and exits, so
 * every call sets up its own tables. The CLI stops at the first statement that fails.
 */
final class DuckDbCli {

    private static final Pattern VERSION = Pattern.compile("v(\\d+)\\.(\\d+)\\.\\d+");

    /** What a call printed, and how it exited. */
    record Result(long exitCode, List<String> out, String err) {

        /**
         * Whether a statement failed: the CLI of 1.2.0 to 1.2.2 exits with 0 on an error too, so its message on
         * stderr counts as well.
         */
        boolean failed() {
            return exitCode != 0 || err.contains("Error:");
        }
    }

    private DuckDbCli() {
    }

    /**
     * Runs the statements and gives the lines of their rows, one value per line ({@code -list}, no header), without
     * blank lines; a NULL is an empty line in 1.0 and NULL later.
     */
    static Result run(String tag, String sql) {
        return run(tag, sql, Map.of());
    }

    /** {@link #run(String, String)} with files put into the container first, each at its absolute path. */
    static Result run(String tag, String sql, Map<String, String> files) {
        try (GenericContainer<?> duck = new GenericContainer<>("duckdb/duckdb:" + tag)
                .withCommand("/duckdb", "-list", "-noheader", "-c", sql).withStartupCheckStrategy(new Exited())) {
            files.forEach((path, content) -> duck.withCopyToContainer(
                    Transferable.of(content.getBytes(StandardCharsets.UTF_8)), path));
            duck.start();
            long exitCode = duck.getCurrentContainerInfo().getState().getExitCodeLong();
            List<String> out = duck.getLogs(OutputType.STDOUT).lines().filter(l -> !l.isBlank()).toList();
            return new Result(exitCode, out, duck.getLogs(OutputType.STDERR));
        }
    }

    /** The version the CLI of the tag reports, checked against the tag: {@code v1.2.2} for {@code 1.2.2}. */
    static DuckDbDialect dialect(String tag) {
        Result r = run(tag, "SELECT version();");
        String version = r.out().get(0);
        Matcher m = VERSION.matcher(version);
        if (r.failed() || !m.matches() || !version.equals("v" + tag)) {
            throw new IllegalStateException("duckdb:" + tag + " reports " + r.out() + r.err());
        }
        return new DuckDbDialect(DialectInitData.ansiDefaults().withVersion(Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2))));
    }

    /** Started once the container has exited, whatever its exit code: a failed statement is a result too. */
    private static final class Exited extends StartupCheckStrategy {

        Exited() {
            withTimeout(Duration.ofMinutes(2));
        }

        @Override
        public StartupStatus checkStartupState(DockerClient dockerClient, String containerId) {
            return "exited".equals(getCurrentState(dockerClient, containerId).getStatus()) ? StartupStatus.SUCCESSFUL
                    : StartupStatus.NOT_YET_KNOWN;
        }
    }
}
