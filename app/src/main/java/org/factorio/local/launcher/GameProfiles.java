package org.factorio.local.launcher;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;

/** Stable profiles belong to a game release/content set, not a test installation or device. */
final class GameProfiles {
    static String id(String version,boolean expansion) throws IOException {
        if(version==null||!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?"))
            throw new IOException("Invalid game release for profile");
        return "factorio-"+version+(expansion?"-space-age":"-base");
    }
    static synchronized File directory(File files,String id,boolean migrate) throws IOException {
        if(id==null||!id.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}"))throw new IOException("Invalid profile identifier");
        File profiles=new File(files,"profiles");
        if(!profiles.isDirectory()&&!profiles.mkdirs()&&!profiles.isDirectory())throw new IOException("Cannot create profiles");
        if(Files.isSymbolicLink(profiles.toPath()))throw new IOException("Profiles cannot be a symbolic link");
        File target=new File(profiles,id);
        // One-time upgrade only: bind old user data to the game active before replacement.
        File legacy=new File(profiles,"default-2.1.22");
        if(migrate&&!target.exists()&&legacy.isDirectory()) {
            if(Files.isSymbolicLink(legacy.toPath()))throw new IOException("Legacy profile cannot be a symbolic link");
            if(Files.isSymbolicLink(new File(legacy,"session.lock").toPath()))throw new IOException("Legacy lock cannot be a symbolic link");
            File migration=new File(profiles,".profile-migration.lock");
            if(Files.isSymbolicLink(migration.toPath()))throw new IOException("Migration lock cannot be a symbolic link");
            try(RandomAccessFile guard=new RandomAccessFile(migration,"rw");FileChannel coordinator=guard.getChannel();FileLock serial=coordinator.lock()) {
                if(!target.exists()&&legacy.isDirectory()) {
                    // Close the old lock handle before the atomic directory rename (also portable on Windows).
                    // The separate migration lease serializes all profile-directory migration participants.
                    try(RandomAccessFile file=new RandomAccessFile(new File(legacy,"session.lock"),"rw");FileChannel channel=file.getChannel()) {
                        FileLock lock;
                        try {lock=channel.tryLock();}catch(OverlappingFileLockException busy){lock=null;}
                        if(lock==null)throw new IOException("Save and end the active session before migrating its profile");
                        lock.release();
                    }
                    Files.move(legacy.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
                }
            }
        }
        if(!target.isDirectory()&&!target.mkdirs()&&!target.isDirectory())throw new IOException("Cannot create profile");
        if(Files.isSymbolicLink(target.toPath()))throw new IOException("Profile cannot be a symbolic link");
        return target;
    }
}
