package com.meshpay.auth.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotenvLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsEnvFromProjectRootWhenRunningFromNestedDirectories() throws IOException {
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Path codeSourceDirectory = Files.createDirectories(projectRoot.resolve("target/classes"));
        Path workingDirectory = Files.createDirectories(projectRoot.resolve("module/subdir"));
        Path dotenvFile = projectRoot.resolve(".env");
        String key = "DOTENV_LOADER_TEST_KEY";
        String previousValue = System.getProperty(key);

        try {
            Files.writeString(dotenvFile, key + "=loaded\n", StandardCharsets.UTF_8);
            System.clearProperty(key);

            DotenvLoader.loadFromSearchRoots(codeSourceDirectory, workingDirectory);

            assertEquals("loaded", System.getProperty(key));
        } finally {
            if (previousValue == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previousValue);
            }
        }
    }

    @Test
    void returnsNullWhenNoEnvFileExists() {
        Path codeSourceDirectory = tempDir.resolve("project/target/classes");
        Path workingDirectory = tempDir.resolve("project/module/subdir");

        assertNull(DotenvLoader.findDotenvFile(codeSourceDirectory, workingDirectory));
    }
}
