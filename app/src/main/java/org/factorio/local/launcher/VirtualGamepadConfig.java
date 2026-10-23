package org.factorio.local.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** Journal one temporary input-method override; keep every other game-written preference. */
final class VirtualGamepadConfig {
    private static Path journal(Path config) {
        return config.resolveSibling("virtual-gamepad-input.properties");
    }

    private static String read(Path config) throws IOException {
        if (Files.size(config) > 1024 * 1024) throw new IOException("Game config is too large");
        return new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
    }

    private static String effective(String line) {
        if (line.startsWith("\ufeff")) line = line.substring(1);
        int semi = line.indexOf(';'), hash = line.indexOf('#');
        int end = semi < 0 ? hash : hash < 0 ? semi : Math.min(semi, hash);
        return (end < 0 ? line : line.substring(0, end)).trim();
    }

    static String inputMethod(String text) throws IOException {
        boolean input = false;
        String value = null;
        for (String raw : text.split("\\r?\\n", -1)) {
            String line = effective(raw);
            if (line.startsWith("[")) input = line.equals("[input]");
            if (input && line.matches("input-method\\s*=.*")) {
                if (value != null) throw new IOException("Duplicate input-method setting");
                value = line.substring(line.indexOf('=') + 1).trim();
            }
        }
        return value;
    }

    static String withInputMethod(String text, String value) throws IOException {
        if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0))
            throw new IOException("Invalid input-method value");
        inputMethod(text);
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder out = new StringBuilder();
        boolean input = false, found = false;
        String[] lines = text.split("\\r?\\n", -1);
        int count = lines.length;
        if (count > 0 && lines[count - 1].isEmpty()) count--;
        for (int i = 0; i < count; i++) {
            String line = effective(lines[i]);
            if (line.startsWith("[")) {
                if (input && value != null)
                    out.append("input-method=").append(value).append(newline);
                input = line.equals("[input]");
                if (input && found) throw new IOException("Duplicate input section");
                found |= input;
            }
            if (input && line.matches("input-method\\s*=.*")) continue;
            out.append(lines[i]).append(newline);
        }
        if (!found && value != null) out.append("[input]").append(newline);
        if ((input || !found) && value != null)
            out.append("input-method=").append(value).append(newline);
        return out.toString();
    }

    private static void atomicWrite(Path path, byte[] bytes) throws IOException {
        Path pending = path.resolveSibling(path.getFileName() + ".pending");
        Files.write(pending, bytes);
        Files.move(
                pending, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static void enable(Path config) throws IOException {
        restore(config);
        String text = read(config), previous = inputMethod(text);
        if ("game-controller".equals(previous)) return;
        Properties state = new Properties();
        state.setProperty("hadInputMethod", Boolean.toString(previous != null));
        if (previous != null) state.setProperty("previousInputMethod", previous);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        state.store(out, "Temporary virtual gamepad input mode");
        atomicWrite(journal(config), out.toByteArray());
        atomicWrite(
                config, withInputMethod(text, "game-controller").getBytes(StandardCharsets.UTF_8));
    }

    static void restore(Path config) throws IOException {
        Path marker = journal(config);
        if (!Files.isRegularFile(marker)) return;
        if (Files.size(marker) > 4096)
            throw new IOException("Invalid virtual gamepad input journal");
        Properties state = new Properties();
        try (InputStream in = Files.newInputStream(marker)) {
            state.load(in);
        }
        if (Files.isRegularFile(config)) {
            String text = read(config);
            // A new explicit choice made in the game takes precedence over our saved value.
            if ("game-controller".equals(inputMethod(text))) {
                String previous =
                        Boolean.parseBoolean(state.getProperty("hadInputMethod"))
                                ? state.getProperty("previousInputMethod", "keyboard-and-mouse")
                                : null;
                atomicWrite(
                        config, withInputMethod(text, previous).getBytes(StandardCharsets.UTF_8));
            }
        }
        Files.delete(marker);
    }
}
