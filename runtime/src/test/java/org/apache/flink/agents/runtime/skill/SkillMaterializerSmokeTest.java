package org.apache.flink.agents.runtime.skill;

import com.sun.net.httpserver.HttpServer;
import org.apache.flink.agents.runtime.skill.repository.SkillMaterializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.startsWith;

class SkillMaterializerSmokeTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void downloadStopsWhenActualBytesExceedLimitAndCleansUp() throws Exception {
        long limit = SkillMaterializer.MAX_DOWNLOAD_BYTES;
        long bytesToSend = limit + 1;

        System.out.println();
        System.out.println("==================================================");
        System.out.println("TEST 1: Download byte limit + cleanup");
        System.out.println("==================================================");
        System.out.println("Configured download limit : " + limit + " bytes");
        System.out.println("Server will stream        : " + bytesToSend + " bytes");
        System.out.println("Content-Length             : NOT PROVIDED");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext(
                "/skill.zip",
                exchange -> {
                    exchange.sendResponseHeaders(200, 0);

                    try (OutputStream out = exchange.getResponseBody()) {
                        byte[] chunk = new byte[64 * 1024];
                        Arrays.fill(chunk, (byte) 'x');

                        long remaining = bytesToSend;
                        long sent = 0;

                        while (remaining > 0) {
                            int count = (int) Math.min(chunk.length, remaining);

                            try {
                                out.write(chunk, 0, count);
                                out.flush();
                                sent += count;
                            } catch (IOException e) {
                                System.out.println(
                                        "Server connection closed after sending "
                                                + sent
                                                + " bytes");
                                break;
                            }

                            remaining -= count;
                        }
                    }
                });

        server.start();

        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        long before = countTempZipFiles(tmpDir);

        System.out.println("Temporary ZIP files BEFORE : " + before);

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/skill.zip";

        IOException exception =
                assertThrows(
                        IOException.class,
                        () -> SkillMaterializer.downloadToTempFile(url, 60_000, true));

        System.out.println("Exception                   : " + exception.getMessage());

        long after = countTempZipFiles(tmpDir);

        System.out.println("Temporary ZIP files AFTER  : " + after);

        assertTrue(
                exception.getMessage().contains("limit"),
                "Expected exception to mention the size limit");

        assertEquals(before, after, "Partial downloaded ZIP was left behind");

        System.out.println("RESULT: PASS");
        System.out.println("  ✓ Download exceeded configured limit");
        System.out.println("  ✓ Download was rejected");
        System.out.println("  ✓ Partial ZIP was cleaned up");
    }

    @Test
    void declaredContentLengthOverLimitIsRejected() throws Exception {
        long limit = SkillMaterializer.MAX_DOWNLOAD_BYTES;
        long declaredLength = limit + 1;

        System.out.println();
        System.out.println("==================================================");
        System.out.println("TEST 2: Content-Length pre-flight check");
        System.out.println("==================================================");
        System.out.println("Configured download limit : " + limit + " bytes");
        System.out.println("Declared Content-Length   : " + declaredLength + " bytes");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext(
                "/skill.zip",
                exchange -> {
                    exchange.getResponseHeaders()
                            .set("Content-Length", String.valueOf(declaredLength));

                    exchange.sendResponseHeaders(200, declaredLength);
                    exchange.close();
                });

        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/skill.zip";

        IOException exception =
                assertThrows(
                        IOException.class,
                        () -> SkillMaterializer.downloadToTempFile(url, 60_000, true));

        System.out.println("Exception                   : " + exception.getMessage());

        assertTrue(
                exception.getMessage().contains("exceeding the limit"),
                "Expected Content-Length limit error");

        System.out.println("RESULT: PASS");
        System.out.println("  ✓ Oversized Content-Length rejected before download");
    }

    @Test
    void archiveWithTooManyEntriesIsRejected() throws Exception {
        int limit = SkillMaterializer.MAX_EXTRACT_ENTRIES;
        int entryCount = limit + 1;

        System.out.println();
        System.out.println("==================================================");
        System.out.println("TEST 3: ZIP entry-count limit");
        System.out.println("==================================================");
        System.out.println("Configured entry limit     : " + limit);
        System.out.println("ZIP entries created        : " + entryCount);

        Path zip = Files.createTempFile("skill-smoke-", ".zip");

        try {
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                for (int i = 0; i < entryCount; i++) {
                    out.putNextEntry(new ZipEntry("entry-" + i + ".txt"));
                    out.closeEntry();
                }
            }

            System.out.println("ZIP size                   : " + Files.size(zip) + " bytes");

            Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
            long before = countTempSkillDirectories(tmpDir);

            System.out.println("Temporary skill dirs BEFORE: " + before);

            IOException exception =
                    assertThrows(IOException.class, () -> SkillMaterializer.extractZipSafely(zip));

            System.out.println("Exception                   : " + exception.getMessage());

            long after = countTempSkillDirectories(tmpDir);

            System.out.println("Temporary skill dirs AFTER : " + after);

            assertTrue(
                    exception.getMessage().contains("entries"), "Expected entry-count limit error");

            assertEquals(before, after, "Partial extraction directory was left behind");

            System.out.println("RESULT: PASS");
            System.out.println("  ✓ Too many ZIP entries rejected");
            System.out.println("  ✓ Temporary extraction directory cleaned up");
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    @Test
    void actualDecompressedBytesOverPerEntryLimitAreRejected() throws Exception {
        long declaredSize = 1;
        long actualSize = SkillMaterializer.MAX_EXTRACT_ENTRY_BYTES + 1;

        System.out.println();
        System.out.println("==================================================");
        System.out.println("TEST 4: Actual decompressed per-entry limit");
        System.out.println("==================================================");
        System.out.println("Declared entry size       : " + declaredSize + " bytes");
        System.out.println("Actual decompressed size  : " + actualSize + " bytes");
        System.out.println(
                "Configured per-entry limit: "
                        + SkillMaterializer.MAX_EXTRACT_ENTRY_BYTES
                        + " bytes");

        Path zip = Files.createTempFile("flink-agents-smoke-", ".zip");

        try {
            writeSingleEntryZip(zip, "large-entry.bin", actualSize);

            // Make the ZIP metadata claim the entry is only 1 byte.
            // The actual DEFLATE stream still expands to actualSize bytes.
            forgeDeclaredUncompressedSize(zip, declaredSize);

            try (ZipFile zf = new ZipFile(zip.toFile())) {
                ZipEntry entry = zf.entries().nextElement();

                assertEquals(
                        declaredSize,
                        entry.getSize(),
                        "Smoke fixture must have an in-limit declared size");
            }

            Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
            long before = countExtractionDirs(tmpDir);

            IOException exception =
                    assertThrows(IOException.class, () -> SkillMaterializer.extractZipSafely(zip));

            long after = countExtractionDirs(tmpDir);

            System.out.println("Exception                  : " + exception.getMessage());

            assertTrue(
                    exception.getMessage().contains("per-entry limit"),
                    "Expected actual per-entry limit error, got: " + exception.getMessage());

            assertEquals(before, after, "Partial extraction directory was left behind");

            System.out.println("RESULT: PASS");
            System.out.println("  ✓ Declared size passed pre-check");
            System.out.println("  ✓ Actual decompressed bytes exceeded limit");
            System.out.println("  ✓ Extraction was rejected");
            System.out.println("  ✓ Partial extraction was cleaned up");
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private static long countTempZipFiles(Path tmpDir) throws IOException {
        try (Stream<Path> paths = Files.list(tmpDir)) {
            return paths.filter(
                            path -> {
                                String name = path.getFileName().toString();
                                return name.startsWith("flink-agents-skills-")
                                        && name.endsWith(".zip");
                            })
                    .count();
        }
    }

    private static Path writeSingleEntryZip(Path zip, String entryName, long entryBytes)
            throws IOException {

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {

            zos.putNextEntry(new ZipEntry(entryName));

            byte[] chunk = new byte[64 * 1024];
            Arrays.fill(chunk, (byte) 'x');

            long remaining = entryBytes;

            while (remaining > 0) {
                int toWrite = (int) Math.min(chunk.length, remaining);
                zos.write(chunk, 0, toWrite);
                remaining -= toWrite;
            }

            zos.closeEntry();
        }

        return zip;
    }

    private static void forgeDeclaredUncompressedSize(Path zip, long declaredSize)
            throws IOException {

        if (declaredSize < 0 || declaredSize > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("declaredSize must fit in a ZIP 32-bit size field");
        }

        byte[] bytes = Files.readAllBytes(zip);

        byte[] localHeaderSignature = {'P', 'K', 3, 4};
        byte[] centralDirectorySignature = {'P', 'K', 1, 2};

        if (!startsWith(bytes, localHeaderSignature)) {
            throw new IOException("ZIP does not start with a local file header");
        }

        int centralDirectoryOffset = lastIndexOfBytes(bytes, centralDirectorySignature);

        if (centralDirectoryOffset < 0) {
            throw new IOException("ZIP does not contain a central directory entry");
        }

        // Local file header:
        // uncompressed size is at offset 22.
        writeLittleEndianInt(bytes, 22, declaredSize);

        // Central directory:
        // uncompressed size is at offset 24.
        writeLittleEndianInt(bytes, centralDirectoryOffset + 24, declaredSize);

        Files.write(zip, bytes);
    }

    private static int lastIndexOfBytes(byte[] bytes, byte[] target) {
        for (int i = bytes.length - target.length; i >= 0; i--) {
            boolean matches = true;

            for (int j = 0; j < target.length; j++) {
                if (bytes[i + j] != target[j]) {
                    matches = false;
                    break;
                }
            }

            if (matches) {
                return i;
            }
        }

        return -1;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {

        if (bytes.length < prefix.length) {
            return false;
        }

        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }

        return true;
    }

    private static void writeLittleEndianInt(byte[] bytes, int offset, long value) {

        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        bytes[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        bytes[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static long countExtractionDirs(Path tmpDir) throws IOException {
        try (Stream<Path> ls = Files.list(tmpDir)) {
            return ls.filter(
                            p ->
                                    p.getFileName().toString().startsWith("flink-agents-skills-")
                                            && Files.isDirectory(p))
                    .count();
        }
    }

    private static long countTempSkillDirectories(Path tmpDir) throws IOException {
        try (Stream<Path> paths = Files.list(tmpDir)) {
            return paths.filter(
                            path -> {
                                String name = path.getFileName().toString();
                                return name.startsWith("flink-agents-skills-")
                                        && Files.isDirectory(path);
                            })
                    .count();
        }
    }
}
