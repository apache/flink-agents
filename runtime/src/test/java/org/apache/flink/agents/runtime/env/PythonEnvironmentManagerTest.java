/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.agents.runtime.env;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.flink.api.common.JobID;
import org.apache.flink.python.env.PythonDependencyInfo;
import org.apache.flink.util.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pemja.core.PythonInterpreterConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.apache.flink.python.env.AbstractPythonEnvironmentManager.PYFLINK_GATEWAY_DISABLED;
import static org.apache.flink.python.env.AbstractPythonEnvironmentManager.PYTHON_ARCHIVES_DIR;
import static org.apache.flink.python.env.AbstractPythonEnvironmentManager.PYTHON_FILES_DIR;
import static org.apache.flink.python.env.AbstractPythonEnvironmentManager.PYTHON_WORKING_DIR;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link PythonEnvironmentManager} */
public class PythonEnvironmentManagerTest {
    private static String tmpDir;

    @BeforeAll
    static void before() throws IOException {
        File tmpFile = File.createTempFile("python_environment_manager_test", "");
        if (tmpFile.delete() && tmpFile.mkdirs()) {
            tmpDir = tmpFile.getAbsolutePath();
        } else {
            throw new IOException(
                    "Create temp directory: " + tmpFile.getAbsolutePath() + " failed!");
        }

        for (int i = 0; i < 6; i++) {
            File distributedFile = new File(tmpDir, "file" + i);
            try (FileOutputStream out = new FileOutputStream(distributedFile)) {
                out.write(i);
            }
        }

        for (int i = 0; i < 2; i++) {
            File distributedDirectory = new File(tmpDir, "dir" + i);
            if (distributedDirectory.mkdirs()) {
                for (int j = 0; j < 2; j++) {
                    File fileInDirs = new File(tmpDir, "dir" + i + File.separator + "file" + j);
                    try (FileOutputStream out = new FileOutputStream(fileInDirs)) {
                        out.write(i);
                        out.write(j);
                    }
                }
            } else {
                throw new IOException(
                        "Create temp dir: " + distributedDirectory.getAbsolutePath() + " failed!");
            }
        }
        for (int i = 0; i < 2; i++) {
            File zipFile = new File(tmpDir, "zip" + i);
            try (ZipArchiveOutputStream zipOut =
                    new ZipArchiveOutputStream(new FileOutputStream(zipFile))) {
                ZipArchiveEntry zipfile0 = new ZipArchiveEntry("zipDir" + i + "/zipfile0");
                zipfile0.setUnixMode(0711);
                zipOut.putArchiveEntry(zipfile0);
                zipOut.write(new byte[] {1, 1, 1, 1, 1});
                zipOut.closeArchiveEntry();
                ZipArchiveEntry zipfile1 = new ZipArchiveEntry("zipDir" + i + "/zipfile1");
                zipfile1.setUnixMode(0644);
                zipOut.putArchiveEntry(zipfile1);
                zipOut.write(new byte[] {2, 2, 2, 2, 2});
                zipOut.closeArchiveEntry();
            }
            File zipExpected =
                    new File(String.join(File.separator, tmpDir, "zipExpected" + i, "zipDir" + i));
            if (!zipExpected.mkdirs()) {
                throw new IOException(
                        "Create temp dir: " + zipExpected.getAbsolutePath() + " failed!");
            }
            File zipfile0 = new File(zipExpected, "zipfile0");
            try (FileOutputStream out = new FileOutputStream(zipfile0)) {
                out.write(new byte[] {1, 1, 1, 1, 1});
            }
            File zipfile1 = new File(zipExpected, "zipfile1");
            try (FileOutputStream out = new FileOutputStream(zipfile1)) {
                out.write(new byte[] {2, 2, 2, 2, 2});
            }

            if (!(zipfile0.setReadable(true, true)
                    && zipfile0.setWritable(true, true)
                    && zipfile0.setExecutable(true))) {
                throw new IOException(
                        "Set unixmode 711 to temp file: " + zipfile0.getAbsolutePath() + "failed!");
            }
            if (!(zipfile1.setReadable(true)
                    && zipfile1.setWritable(true, true)
                    && zipfile1.setExecutable(false))) {
                throw new IOException(
                        "Set unixmode 644 to temp file: " + zipfile1.getAbsolutePath() + "failed!");
            }
        }
    }

    @AfterAll
    static void after() {
        if (tmpDir != null) {
            FileUtils.deleteDirectoryQuietly(new File(tmpDir));
            tmpDir = null;
        }
    }

    @Test
    void testPythonExecutable() throws Exception {
        PythonDependencyInfo dependencyInfo =
                new PythonDependencyInfo(
                        new HashMap<>(), null, null, new HashMap<>(), "/usr/local/bin/python");

        try (PythonEnvironmentManager environmentManager =
                createBasicPythonEnvironmentManager(dependencyInfo)) {
            environmentManager.open();
            Map<String, String> environmentVariable = environmentManager.getPythonEnv();

            Map<String, String> expected = getBasicExpectedEnv(environmentManager);
            expected.put("python", "/usr/local/bin/python");
            assertEquals(expected, environmentVariable);

            EmbeddedPythonEnvironment env =
                    (EmbeddedPythonEnvironment) environmentManager.createEnvironment();

            assertEquals(expected, env.getEnv());

            PythonInterpreterConfig config = env.getConfig();
            assertEquals("/usr/local/bin/python", config.getPythonExec());
            assertEquals(PythonInterpreterConfig.ExecType.MULTI_THREAD, config.getExecType());
        }
    }

    @Test
    void testPythonFiles() throws Exception {
        // use LinkedHashMap to preserve the path order in environment variable
        Map<String, String> pythonFiles = new LinkedHashMap<>();
        pythonFiles.put(String.join(File.separator, tmpDir, "zip0"), "test_zip.zip");
        pythonFiles.put(String.join(File.separator, tmpDir, "file1"), "test_file1.py");
        pythonFiles.put(String.join(File.separator, tmpDir, "file2"), "test_file2.egg");
        pythonFiles.put(String.join(File.separator, tmpDir, "dir0"), "test_dir");
        PythonDependencyInfo dependencyInfo =
                new PythonDependencyInfo(pythonFiles, null, null, new HashMap<>(), "python");

        try (PythonEnvironmentManager environmentManager =
                createBasicPythonEnvironmentManager(dependencyInfo)) {
            environmentManager.open();
            String baseDir = environmentManager.getBaseDirectory();
            Map<String, String> environmentVariable = environmentManager.getPythonEnv();

            String[] expectedUserPythonPaths =
                    new String[] {
                        String.join(File.separator, baseDir, PYTHON_FILES_DIR, "zip0", "test_zip"),
                        String.join(File.separator, baseDir, PYTHON_FILES_DIR, "file1"),
                        String.join(
                                File.separator,
                                baseDir,
                                PYTHON_FILES_DIR,
                                "file2",
                                "test_file2.egg"),
                        String.join(File.separator, baseDir, PYTHON_FILES_DIR, "dir0", "test_dir")
                    };
            String expectedPythonPath = String.join(File.pathSeparator, expectedUserPythonPaths);

            assertEquals(expectedPythonPath, environmentVariable.get("PYTHONPATH"));
            assertFileEquals(
                    new File(String.join(File.separator, tmpDir, "file1")),
                    new File(
                            String.join(
                                    File.separator,
                                    baseDir,
                                    PYTHON_FILES_DIR,
                                    "file1",
                                    "test_file1.py")),
                    false);
            assertFileEquals(
                    new File(String.join(File.separator, tmpDir, "zipExpected0")),
                    new File(
                            String.join(
                                    File.separator, baseDir, PYTHON_FILES_DIR, "zip0", "test_zip")),
                    false);
            assertFileEquals(
                    new File(String.join(File.separator, tmpDir, "file2")),
                    new File(
                            String.join(
                                    File.separator,
                                    baseDir,
                                    PYTHON_FILES_DIR,
                                    "file2",
                                    "test_file2.egg")),
                    false);
            assertFileEquals(
                    new File(String.join(File.separator, tmpDir, "dir0")),
                    new File(
                            String.join(
                                    File.separator, baseDir, PYTHON_FILES_DIR, "dir0", "test_dir")),
                    false);

            EmbeddedPythonEnvironment env =
                    (EmbeddedPythonEnvironment) environmentManager.createEnvironment();
            PythonInterpreterConfig config = env.getConfig();
            assertArrayEquals(expectedPythonPath.split(File.pathSeparator), config.getPaths());
        }
    }

    @Test
    void testArchives() throws Exception {
        Map<String, String> archives = new LinkedHashMap<>();
        archives.put(String.join(File.separator, tmpDir, "zip0"), "py312.zip");
        archives.put(String.join(File.separator, tmpDir, "zip1"), "py312");
        PythonDependencyInfo dependencyInfo =
                new PythonDependencyInfo(
                        new HashMap<>(), null, null, archives, "py312.zip/bin/python");

        try (PythonEnvironmentManager environmentManager =
                createBasicPythonEnvironmentManager(dependencyInfo)) {
            environmentManager.open();
            String tmpBase = environmentManager.getBaseDirectory();
            Map<String, String> environmentVariable = environmentManager.getPythonEnv();

            Map<String, String> expected = getBasicExpectedEnv(environmentManager);
            expected.put(
                    PYTHON_WORKING_DIR, String.join(File.separator, tmpBase, PYTHON_ARCHIVES_DIR));
            expected.put("python", "py312.zip/bin/python");
            assertEquals(expected, environmentVariable);

            assertFileEquals(
                    new File(String.join(File.separator, tmpDir, "zipExpected0")),
                    new File(
                            String.join(File.separator, tmpBase, PYTHON_ARCHIVES_DIR, "py312.zip")),
                    true);
            assertFileEquals(
                    new File(String.join(File.separator, tmpDir, "zipExpected1")),
                    new File(String.join(File.separator, tmpBase, PYTHON_ARCHIVES_DIR, "py312")),
                    true);

            EmbeddedPythonEnvironment env =
                    (EmbeddedPythonEnvironment) environmentManager.createEnvironment();

            assertEquals(expected, env.getEnv());

            PythonInterpreterConfig config = env.getConfig();
            assertEquals(
                    String.join(File.separator, tmpBase, PYTHON_ARCHIVES_DIR),
                    config.getWorkingDirectory());
            assertEquals(
                    String.join(File.separator, tmpBase, PYTHON_ARCHIVES_DIR, "py312.zip"),
                    config.getPythonHome());
        }
    }

    private static void assertFileEquals(File expectedFile, File actualFile, boolean checkUnixMode)
            throws IOException {
        assertTrue(actualFile.exists());
        assertTrue(expectedFile.exists());
        if (expectedFile.getAbsolutePath().equals(actualFile.getAbsolutePath())) {
            return;
        }

        if (checkUnixMode) {
            Set<PosixFilePermission> expectedPerm =
                    Files.getPosixFilePermissions(Paths.get(expectedFile.toURI()));
            Set<PosixFilePermission> actualPerm =
                    Files.getPosixFilePermissions(Paths.get(actualFile.toURI()));
            assertEquals(expectedPerm, actualPerm);
        }

        final BasicFileAttributes expectedFileAttributes =
                Files.readAttributes(expectedFile.toPath(), BasicFileAttributes.class);
        if (expectedFileAttributes.isDirectory()) {
            assertTrue(actualFile.isDirectory());
            String[] expectedSubFiles = expectedFile.list();
            assertArrayEquals(expectedSubFiles, actualFile.list());
            if (expectedSubFiles != null) {
                for (String fileName : expectedSubFiles) {
                    assertFileEquals(
                            new File(expectedFile.getAbsolutePath(), fileName),
                            new File(actualFile.getAbsolutePath(), fileName),
                            false);
                }
            }
        } else {
            if (expectedFileAttributes.size() > 0) {
                assertTrue(org.apache.commons.io.FileUtils.contentEquals(expectedFile, actualFile));
            }
        }
    }

    private static Map<String, String> getBasicExpectedEnv(
            PythonEnvironmentManager environmentManager) {
        Map<String, String> map = new HashMap<>();
        String tmpBase = environmentManager.getBaseDirectory();
        map.put("python", "python");
        map.put("BOOT_LOG_DIR", tmpBase);
        map.put(PYFLINK_GATEWAY_DISABLED, "true");
        return map;
    }

    private static PythonEnvironmentManager createBasicPythonEnvironmentManager(
            PythonDependencyInfo dependencyInfo) {
        return new PythonEnvironmentManager(
                dependencyInfo, new String[] {tmpDir}, new HashMap<>(), new JobID());
    }
}
