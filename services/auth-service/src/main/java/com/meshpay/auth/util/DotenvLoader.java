package com.meshpay.auth.util;

import java.io.IOException;
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
        Path file = findDotenvFile();
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

    private static Path findDotenvFile() {
        List<Path> candidates = new ArrayList<>();

        // 1. Next to the running JAR (java -jar mode)
        String jarPath = System.getProperty("java.class.path");
        if (jarPath != null && jarPath.endsWith(".jar")) {
            Path jarDir = Path.of(jarPath).getParent();
            if (jarDir != null) {
                candidates.add(jarDir.resolve(".env"));
            }
        }

        // 2. Next to the class file (IDE mode: target/classes → ../../.env)
        try {
            Path classDir = Path.of(
                    DotenvLoader.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            if (!classDir.toString().contains("!")) {
                candidates.add(classDir.resolve("../../.env"));
            }
        } catch (Exception ignored) {
        }

        // 3. From current working directory upward
        candidates.add(Path.of(".env"));
        candidates.add(Path.of("../.env"));
        candidates.add(Path.of("../../.env"));

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
}
