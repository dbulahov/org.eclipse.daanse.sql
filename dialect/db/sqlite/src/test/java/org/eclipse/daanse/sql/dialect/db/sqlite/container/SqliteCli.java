/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.daanse.sql.dialect.db.sqlite.container;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.daanse.sql.dialect.api.DialectInitData;
import org.eclipse.daanse.sql.dialect.db.sqlite.SqliteDialect;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame.OutputType;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;

import com.github.dockerjava.api.DockerClient;

/**
 * The sqlite3 CLI of a given version, run in Docker. SQLite is embedded and serves no connection, and the JDBC driver
 * brings an SQLite of its own: the version is the CLI's. A version is named by the image that has it:
 * {@code keinos/sqlite3:<version>} from 3.38, and before, a release of a distribution whose package is that version,
 * built once into an image of its own name and kept ({@code ubuntu:18.04} with 3.22.0, {@code alpine:3.8} with 3.25.3,
 * {@code alpine:3.12} with 3.32.1, {@code alpine:3.14} with 3.35.5). Each call runs its statements in a database in
 * memory and exits, stopping at the first that fails ({@code -bail}).
 */
final class SqliteCli {

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    /** What a call printed, and how it exited. */
    record Result(long exitCode, List<String> out, String err) {

        /** Whether a statement failed: {@code Error:} in older CLIs, {@code Parse error} or the like in newer. */
        boolean failed() {
            return exitCode != 0 || err.toLowerCase(java.util.Locale.ROOT).contains("error");
        }
    }

    private SqliteCli() {
    }

    /** Runs the statements and gives the lines of their rows, the values split by {@code |}, no header. */
    static Result run(String version, String sql) {
        try (GenericContainer<?> cli = container(version)
                .withCommand("sqlite3", "-batch", "-bail", "-noheader", "-list", ":memory:", sql)
                .withStartupCheckStrategy(new Exited())) {
            cli.start();
            long exitCode = cli.getCurrentContainerInfo().getState().getExitCodeLong();
            List<String> out = cli.getLogs(OutputType.STDOUT).lines().toList();
            return new Result(exitCode, out, cli.getLogs(OutputType.STDERR));
        }
    }

    /** The dialect for the version the CLI reports, checked against the one asked for. */
    static SqliteDialect dialect(String version) {
        Result r = run(version, "SELECT sqlite_version();");
        String reported = r.out().isEmpty() ? "" : r.out().get(0);
        Matcher m = VERSION.matcher(reported);
        if (r.failed() || !m.matches() || !reported.equals(version)) {
            throw new IllegalStateException("sqlite " + version + " reports " + r.out() + r.err());
        }
        return new SqliteDialect(DialectInitData.ansiDefaults().withVersion(Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2))));
    }

    private static GenericContainer<?> container(String version) {
        String base = switch (version) {
        case "3.22.0" -> "ubuntu:18.04";
        case "3.25.3" -> "alpine:3.8";
        case "3.32.1" -> "alpine:3.12";
        case "3.35.5" -> "alpine:3.14";
        default -> null;
        };
        if (base == null) {
            return new GenericContainer<>("keinos/sqlite3:" + version);
        }
        String install = base.startsWith("ubuntu")
                ? "apt-get update && apt-get install -y --no-install-recommends sqlite3 && rm -rf /var/lib/apt/lists/*"
                : "apk add --no-cache sqlite";
        // named and not deleted: built once, then taken from Docker's cache
        return new GenericContainer<>(new ImageFromDockerfile("daanse-sqlite3:" + version, false)
                .withDockerfileFromBuilder(b -> b.from(base).run(install).build()));
    }

    /** Started once the container has exited, whatever its exit code: a failed statement is a result too. */
    private static final class Exited extends StartupCheckStrategy {

        Exited() {
            withTimeout(Duration.ofMinutes(5));
        }

        @Override
        public StartupStatus checkStartupState(DockerClient dockerClient, String containerId) {
            return "exited".equals(getCurrentState(dockerClient, containerId).getStatus()) ? StartupStatus.SUCCESSFUL
                    : StartupStatus.NOT_YET_KNOWN;
        }
    }
}
