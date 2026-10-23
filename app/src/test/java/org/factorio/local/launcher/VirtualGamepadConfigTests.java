package org.factorio.local.launcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;

/** Temporary controller mode restores just its own field after normal exit or interrupted runs. */
public final class VirtualGamepadConfigTests {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static void write(Path p, String text) throws Exception {
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("virtual-gamepad-config-");
        Path config = dir.resolve("config.ini"),
                journal = dir.resolve("virtual-gamepad-input.properties");
        try {
            String original =
                    "\ufeff; arbitrary config version\r\n[input]\r\n; input-method=keyboard-and-mouse\r\nmouse-sensitivity=1\r\n[other]\r\nvalue=old\r\n";
            write(config, original);
            VirtualGamepadConfig.restore(config);
            check(read(config).equals(original), "disabled leaves file unchanged");
            VirtualGamepadConfig.enable(config);
            check(
                    "game-controller".equals(VirtualGamepadConfig.inputMethod(read(config)))
                            && Files.exists(journal),
                    "enables with recovery journal");
            check(
                    read(config).startsWith("\ufeff")
                            && !read(config).replace("\r\n", "").contains("\n"),
                    "BOM and CRLF preserved");
            write(config, read(config).replace("value=old", "value=game-change"));
            VirtualGamepadConfig.restore(config);
            check(
                    VirtualGamepadConfig.inputMethod(read(config)) == null
                            && read(config).contains("value=game-change")
                            && !Files.exists(journal),
                    "restore removes only injected field and retains game changes");
            write(config, "[input]\ninput-method=keyboard-and-mouse\n[graphics]\nquality=custom\n");
            VirtualGamepadConfig.enable(config);
            VirtualGamepadConfig.enable(config);
            VirtualGamepadConfig.restore(config);
            check(
                    "keyboard-and-mouse".equals(VirtualGamepadConfig.inputMethod(read(config))),
                    "restart recovery does not overwrite original mode");
            VirtualGamepadConfig.enable(config);
            write(config, VirtualGamepadConfig.withInputMethod(read(config), "keyboard-and-mouse"));
            VirtualGamepadConfig.restore(config);
            check(
                    "keyboard-and-mouse".equals(VirtualGamepadConfig.inputMethod(read(config))),
                    "explicit in-game choice wins");
            write(config, "[input]\ninput-method=game-controller\n");
            VirtualGamepadConfig.enable(config);
            VirtualGamepadConfig.restore(config);
            check(
                    !Files.exists(journal)
                            && "game-controller"
                                    .equals(VirtualGamepadConfig.inputMethod(read(config))),
                    "existing physical controller mode retained");
            write(config, "[general]\nvalue=1\n");
            VirtualGamepadConfig.enable(config);
            VirtualGamepadConfig.restore(config);
            check(
                    VirtualGamepadConfig.inputMethod(read(config)) == null
                            && read(config).contains("value=1"),
                    "missing input section supported");
            write(config, "[input]\ninput-method=keyboard-and-mouse\n");
            VirtualGamepadConfig.enable(config);
            Files.delete(config);
            VirtualGamepadConfig.restore(config);
            check(
                    !Files.exists(config) && !Files.exists(journal),
                    "deleted config is not resurrected");
            try {
                VirtualGamepadConfig.withInputMethod(
                        "[input]\ninput-method=a\ninput-method=b\n", "game-controller");
                throw new AssertionError("accepted ambiguous config");
            } catch (java.io.IOException expected) {
                checks++;
            }
            System.out.println("VirtualGamepadConfigTests: " + checks + " checks passed");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(p);
            }
        }
    }
}
