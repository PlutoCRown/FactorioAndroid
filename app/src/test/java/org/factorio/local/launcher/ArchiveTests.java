package org.factorio.local.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ArchiveTests {
    static int passed;

    static byte[] header(String name, long size, int type) throws Exception {
        byte[] b = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(n, 0, b, 0, Math.min(n.length, 100));
        byte[] s = String.format(Locale.ROOT, "%011o", size).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(s, 0, b, 124, s.length);
        Arrays.fill(b, 148, 156, (byte) ' ');
        b[156] = (byte) type;
        byte[] magic = "ustar\00000".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, b, 257, magic.length);
        checksum(b);
        return b;
    }

    static void checksum(byte[] b) {
        Arrays.fill(b, 148, 156, (byte) ' ');
        int sum = 0;
        for (byte v : b) sum += v & 255;
        byte[] c = String.format(Locale.ROOT, "%06o\000 ", sum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(c, 0, b, 148, 8);
    }

    static void entry(ByteArrayOutputStream out, String name, byte[] content, int type)
            throws Exception {
        out.write(header(name, content.length, type));
        out.write(content);
        out.write(new byte[(512 - content.length % 512) % 512]);
    }

    static byte[] end(ByteArrayOutputStream out) throws Exception {
        out.write(new byte[1024]);
        return out.toByteArray();
    }

    static void accept(String label, byte[] archive, int expected) throws Exception {
        int count = 0;
        try (SafeTarReader r = new SafeTarReader(new ByteArrayInputStream(archive))) {
            while (r.next() != null) count++;
        }
        if (count != expected) throw new AssertionError(label + ": count=" + count);
        passed++;
        System.out.println("PASS " + label);
    }

    static void reject(String label, byte[] archive) throws Exception {
        try (SafeTarReader r = new SafeTarReader(new ByteArrayInputStream(archive))) {
            while (r.next() != null) {}
        } catch (IOException expected) {
            passed++;
            System.out.println("PASS " + label);
            return;
        }
        throw new AssertionError("Accepted unsafe archive: " + label);
    }

    static byte[] one(String name, int type) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        entry(out, name, new byte[0], type);
        return end(out);
    }

    static byte[] pax(String key, String value) {
        String body = key + "=" + value + "\n";
        int length = body.getBytes(StandardCharsets.UTF_8).length + 2;
        while (true) {
            String record = length + " " + body;
            int actual = record.getBytes(StandardCharsets.UTF_8).length;
            if (actual == length) return record.getBytes(StandardCharsets.UTF_8);
            length = actual;
        }
    }

    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream normal = new ByteArrayOutputStream();
        entry(normal, "factorio/", new byte[0], '5');
        entry(normal, "factorio/data/base/info.json", "{}".getBytes(StandardCharsets.UTF_8), '0');
        accept("ordinary directory and file", end(normal), 2);
        for (String p :
                new String[] {
                    "../escape",
                    "factorio/../../escape",
                    "/factorio/file",
                    "factorio/a/../b",
                    "factorio/a\\b",
                    "factorio//b",
                    "factorio/./b",
                    "other/file",
                    "factorio/C:/x"
                }) reject("path " + p, one(p, '0'));
        reject("symlink", one("factorio/link", '2'));
        reject("hard link", one("factorio/link", '1'));
        reject("sparse/unsupported entry", one("factorio/sparse", 'S'));
        reject("oversized file", header("factorio/huge", 3L * 1024 * 1024 * 1024, '0'));
        byte[] bad = one("factorio/file", '0');
        bad[0] ^= 1;
        reject("header checksum", bad);
        reject("truncated header", new byte[] {1, 2, 3});
        ByteArrayOutputStream truncated = new ByteArrayOutputStream();
        truncated.write(header("factorio/file", 1000, '0'));
        truncated.write(new byte[5]);
        reject("truncated payload", truncated.toByteArray());
        ByteArrayOutputStream duplicate = new ByteArrayOutputStream();
        entry(duplicate, "factorio/a", new byte[0], '0');
        entry(duplicate, "factorio/a", new byte[0], '0');
        reject("duplicate entry", end(duplicate));
        ByteArrayOutputStream lng = new ByteArrayOutputStream();
        String longPath = "factorio/" + String.join("", Collections.nCopies(150, "x"));
        entry(lng, "././@LongLink", (longPath + "\000").getBytes(StandardCharsets.UTF_8), 'L');
        entry(lng, "placeholder", new byte[0], '0');
        accept("GNU long name", end(lng), 1);
        ByteArrayOutputStream paxOk = new ByteArrayOutputStream();
        entry(paxOk, "PaxHeaders/file", pax("path", "factorio/safe"), 'x');
        entry(paxOk, "placeholder", new byte[0], '0');
        accept("PAX path", end(paxOk), 1);
        ByteArrayOutputStream paxBad = new ByteArrayOutputStream();
        entry(paxBad, "PaxHeaders/file", pax("path", "factorio/../escape"), 'x');
        entry(paxBad, "placeholder", new byte[0], '0');
        reject("PAX traversal", end(paxBad));
        ByteArrayOutputStream dangling = new ByteArrayOutputStream();
        entry(dangling, "PaxHeaders/file", pax("path", "factorio/file"), 'x');
        reject("dangling PAX", end(dangling));
        byte[] trailer = Arrays.copyOf(one("factorio/a", '0'), 2048);
        trailer[trailer.length - 1] = 1;
        reject("nonzero trailer", trailer);
        ByteArrayOutputStream directoryData = new ByteArrayOutputStream();
        entry(directoryData, "factorio/", new byte[] {1}, '5');
        reject("directory payload", end(directoryData));
        System.out.println("Archive tests passed: " + passed);
    }
}
