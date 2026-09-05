package com.meshpay.auth.util;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.logging.Logger;

/**
 * Loads environment variables from a .env file into system properties.
 * Searches upward from the code source directory and from the working directory.
 * Skips loading during test runtime to avoid interfering with test isolation.
 */
public final class DotenvLoader {

    private static final Logger log = Logger.getLogger(DotenvLoader.class.getName());

    private DotenvLoader() {
    }

    public static void load() {
        if (isTestRuntime()) {
            return;
        }

        Path codeSourceDir = resolveCodeSourceDirectory();
        Path workingDir = Path.of("").toAbsolutePath().normalize();
        Path file = findDotenvFile(codeSourceDir, workingDir);

        if (file == null) {
            log.info("No .env file found, using OS environment variables");
            return;
        }

        log.info("Loading environment from: " + file.toAbsolutePath());
        int loaded = 0;

        try {
            for (String line : Files.readAllLines(file)) {
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

    // Visible for testing
    static Path findDotenvFile(Path codeSourceDirectory, Path workingDirectory) {
        java.util.List<Path> candidates = new ArrayList<>();
        addCandidates(candidates, codeSourceDirectory);
        addCandidates(candidates, workingDirectory);

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

    private static void addCandidates(java.util.List<Path> candidates, Path startDirectory) {
        if (startDirectory == null) {
            return;
        }
        for (Path current = startDirectory.toAbsolutePath().normalize();
             current != null; current = current.getParent()) {
            candidates.add(current.resolve(".env"));
        }
    }

    private static Path resolveCodeSourceDirectory() {
        try {
            var codeSource = DotenvLoader.class.getProtectionDomain().getCodeSource();
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
