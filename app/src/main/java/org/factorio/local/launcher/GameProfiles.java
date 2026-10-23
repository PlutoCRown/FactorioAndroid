package org.factorio.local.launcher;

import java.io.*;
import java.nio.file.Files;

/** One shared writable profile for every imported game version and content set. */
final class GameProfiles {
    static final String ID = "default";

    static synchronized File directory(File files) throws IOException {
        File profiles = new File(files, "profiles");
        if (Files.isSymbolicLink(profiles.toPath()))
            throw new IOException("Profiles cannot be a symbolic link");
        if (!profiles.isDirectory() && !profiles.mkdirs() && !profiles.isDirectory())
            throw new IOException("Cannot create profiles");
        File target = new File(profiles, ID);
        if (Files.isSymbolicLink(target.toPath()))
            throw new IOException("Profile cannot be a symbolic link");
        if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory())
            throw new IOException("Cannot create profile");
        return target;
    }
}
