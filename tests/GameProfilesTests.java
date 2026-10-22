package org.factorio.local.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exercise the shared writable directory and private-tree boundaries. */
public final class GameProfilesTests {
    private static int checks;
    private interface Task {void run() throws Exception;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static void rejected(Task task,String message) throws Exception {
        try{task.run();}catch(IOException expected){checks++;return;}
        throw new AssertionError(message);
    }
    private static void write(Path path,String text) throws IOException {
        Files.createDirectories(path.getParent());Files.write(path,text.getBytes(StandardCharsets.UTF_8));
    }
    private static String read(Path path) throws IOException {return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
    private static void clean(Path directory) throws IOException {
        try(java.util.stream.Stream<Path> paths=Files.walk(directory)) {
            for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);
        }
    }
    private static boolean link(Path path,Path target) throws IOException {
        try{Files.createSymbolicLink(path,target);return true;}
        catch(UnsupportedOperationException|SecurityException unavailable){return false;}
        catch(FileSystemException unavailable) {
            // Windows may require developer-mode/admin privileges; that is not a test failure.
            if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win"))return false;
            throw unavailable;
        }
    }
    public static void main(String[] args) throws Exception {
        Path temporary=Files.createTempDirectory("factorio-profiles-");
        try {
            shared(temporary.resolve("shared"));
            paths(temporary.resolve("paths"));
            symlinks(temporary.resolve("links"));
            System.out.println("GameProfilesTests: "+checks+" checks passed");
        }finally{clean(temporary);}
    }
    private static void shared(Path files) throws Exception {
        File first=GameProfiles.directory(files.toFile());
        check(first.toPath().equals(files.resolve("profiles/default")),"fixed shared directory");
        write(first.toPath().resolve("userdata/saves/unchanged.zip"),"existing save");
        File again=GameProfiles.directory(files.toFile());
        check(first.equals(again)&&"existing save".equals(read(again.toPath().resolve("userdata/saves/unchanged.zip"))),"shared data reused");
    }
    private static void paths(Path files) throws Exception {
        Files.createDirectories(files);
        Path ordinary=files.resolve("profiles/default");write(ordinary,"retained file");
        rejected(()->GameProfiles.directory(files.toFile()),"file accepted as profile directory");
        check("retained file".equals(read(ordinary)),"invalid target preserves existing file");
        Path blocked=files.resolve("blocked");write(blocked.resolve("profiles"),"retained root file");
        rejected(()->GameProfiles.directory(blocked.toFile()),"file accepted as profiles root");
        check("retained root file".equals(read(blocked.resolve("profiles"))),"invalid profiles root preserved");
    }
    private static void symlinks(Path files) throws Exception {
        Path outside=files.resolve("outside");Files.createDirectories(outside);write(outside.resolve("marker"),"untouched");
        Path root=files.resolve("root");Files.createDirectories(root);
        if(!link(root.resolve("profiles"),outside)) {System.out.println("GameProfilesTests: symbolic-link checks unavailable on this host");return;}
        rejected(()->GameProfiles.directory(root.toFile()),"symlink profiles root accepted");
        check(!Files.exists(outside.resolve("default"))&&"untouched".equals(read(outside.resolve("marker"))),"symlink root refusal writes nothing outside");
        Files.delete(root.resolve("profiles"));Files.createDirectories(root.resolve("profiles"));
        link(root.resolve("profiles/default"),outside);
        rejected(()->GameProfiles.directory(root.toFile()),"symlink profile target accepted");
        Files.delete(root.resolve("profiles/default"));
    }
}
