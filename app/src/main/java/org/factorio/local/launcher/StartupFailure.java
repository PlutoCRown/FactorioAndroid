package org.factorio.local.launcher;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Read only this launch's appended log; stdout can stay buffered inside SDL's error dialog. */
final class StartupFailure {
    static String read(File log, long start) throws IOException {
        if (!log.isFile()) return null;
        try (RandomAccessFile input = new RandomAccessFile(log, "r")) {
            long end = input.length();
            if (end <= start) return null;
            input.seek(Math.max(start, end - 16384));
            byte[] bytes = new byte[(int) (end - input.getFilePointer())];
            input.readFully(bytes);
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                if (line.matches(
                                ".*Error SDLWindow\\.cpp:[0-9]+: Failed to create an application window\\. SDL_Error: .+")
                        || line.matches(
                                ".*Error Util\\.cpp:[0-9]+: Failed to create OpenGL context: .+"))
                    return line.trim().substring(0, Math.min(line.trim().length(), 1024));
            }
            return null;
        }
    }
}
