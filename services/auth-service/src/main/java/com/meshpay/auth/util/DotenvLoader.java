package com.meshpay.auth.util;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Loads environment variables from a .env file into system properties.
 * Searches in order: next to the running JAR, then from CWD upward.
 * Lines starting with # are comments. Empty lines are skipped.
 * Format: KEY=VALUE (one per line).
 */
public final class DotenvLoader {

    private static final Logger log = Logger.getLogger(DotenvLoader.class.getName());

    private DotenvLoader() {
    }

    public static void load() {
        if (isTestRuntime()) {
            log.fine("Skipping .env loading during test runtime");
            return;
        }

        loadFromSearchRoots(resolveCodeSourceDirectory(), Path.of("").toAbsolutePath().normalize());
    }

    static void loadFromSearchRoots(Path codeSourceDirectory, Path workingDirectory) {
        Path file = findDotenvFile(codeSourceDirectory, workingDirectory);
        if (file == null) {
            log.info("No .env file found, using OS environment variables");
            return;
        }

        log.info("Loading environment from: " + file.toAbsolutePath());
        int loaded = 0;

        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                int eqIndex = trimmed.indexOf('=');
                if (eqIndex <= 0) {
                    continue;
                }

                String key = trimmed.substring(0, eqIndex).trim();
                String value = trimmed.substring(eqIndex + 1).trim();

                System.setProperty(key, value);
                loaded++;
            }
        } catch (IOException e) {
            log.warning("Failed to read .env file: " + e.getMessage());
            return;
        }

        log.info("Loaded " + loaded + " variables from .env");
    }

    static Path findDotenvFile(Path codeSourceDirectory, Path workingDirectory) {
        List<Path> candidates = new ArrayList<>();

        addDotenvCandidates(candidates, codeSourceDirectory);
        addDotenvCandidates(candidates, workingDirectory);

        for (Path candidate : candidates) {
            try {
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    return candidate.toRealPath();
                }
            } catch (IOException ignored) {
            }
        }

        return null;
    }

    private static void addDotenvCandidates(List<Path> candidates, Path startDirectory) {
        if (startDirectory == null) {
            return;
        }

        for (Path current = startDirectory.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            candidates.add(current.resolve(".env"));
        }
    }

    private static Path resolveCodeSourceDirectory() {
        try {
            CodeSource codeSource = DotenvLoader.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return null;
            }

            Path location = Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
            return Files.isRegularFile(location) ? location.getParent() : location;
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Unable to resolve application location", e);
        }
    }

    private static boolean isTestRuntime() {
        String classPath = System.getProperty("java.class.path", "");
        return classPath.contains("surefire")
                || classPath.contains("failsafe")
                || classPath.contains("test-classes");
    }
}
