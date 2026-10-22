package org.factorio.local.launcher;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exercise release/content profile isolation and the actual migration lease using real files. */
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
            identities(temporary.resolve("identities"));
            migration(temporary.resolve("migration"));
            activeLease(temporary.resolve("lease"));
            paths(temporary.resolve("paths"));
            symlinks(temporary.resolve("links"));
            System.out.println("GameProfilesTests: "+checks+" checks passed");
        }finally{clean(temporary);}
    }
    private static void identities(Path files) throws Exception {
        String base=GameProfiles.id("2.1.22",false),expansion=GameProfiles.id("2.1.22",true);
        String future=GameProfiles.id("3.4.567",false),prerelease=GameProfiles.id("3.4.567-preview.2",false);
        check(new HashSet<>(Arrays.asList(base,expansion,future,prerelease)).size()==4,"each release/content set has a distinct profile");
        check(base.equals(GameProfiles.id("2.1.22",false))&&expansion.equals(GameProfiles.id("2.1.22",true)),"reimport identity is stable for base and Space Age");
        File first=GameProfiles.directory(files.toFile(),base,false);
        write(first.toPath().resolve("userdata/saves/unchanged.zip"),"existing base save");
        File importedAgain=GameProfiles.directory(files.toFile(),GameProfiles.id("2.1.22",false),true);
        check(first.equals(importedAgain)&&"existing base save".equals(read(importedAgain.toPath().resolve("userdata/saves/unchanged.zip"))),"reimport reuses existing profile and save");
        File expansionDirectory=GameProfiles.directory(files.toFile(),expansion,true);
        File futureDirectory=GameProfiles.directory(files.toFile(),future,true);
        check(!expansionDirectory.equals(first)&&!futureDirectory.equals(first),"different release/content directories remain isolated");
        check(!new File(expansionDirectory,"userdata/saves/unchanged.zip").exists()&&!new File(futureDirectory,"userdata/saves/unchanged.zip").exists(),"base save is not moved into another release/content profile");
        check(GameProfiles.id("10.20.300+custom.1",true).contains("10.20.300+custom.1"),"release metadata is accepted without a fixed engine version");
        rejected(()->GameProfiles.id(null,false),"missing release accepted");
        for(String bad:new String[]{"","2.1","v2.1.22","../2.1.22","2.1.22/other","2.1.22\n[path]","2.1.22\\other"})
            rejected(()->GameProfiles.id(bad,false),"unsafe/malformed release accepted: "+bad);
    }
    private static void migration(Path files) throws Exception {
        Path legacy=files.resolve("profiles/default-2.1.22");
        String config="\ufeff; version=999\r\n[general]\r\nlocale=de\r\n[controls]\r\ncustom=retained\r\n";
        write(legacy.resolve("config.ini"),config);write(legacy.resolve("userdata/saves/map.zip"),"save bytes");
        write(legacy.resolve("userdata/mods/mod.zip"),"mod bytes");write(legacy.resolve("profile.json"),"legacy metadata");
        String id=GameProfiles.id("2.1.22",true);
        File migrated=GameProfiles.directory(files.toFile(),id,true);
        check(migrated.toPath().equals(files.resolve("profiles").resolve(id))&&!Files.exists(legacy),"legacy profile moves once to active content identity");
        check(config.equals(read(migrated.toPath().resolve("config.ini"))),"migration preserves exact BOM CRLF config bytes and user locale");
        check("save bytes".equals(read(migrated.toPath().resolve("userdata/saves/map.zip"))),"migration preserves saves");
        check("mod bytes".equals(read(migrated.toPath().resolve("userdata/mods/mod.zip"))),"migration preserves mods");
        check("legacy metadata".equals(read(migrated.toPath().resolve("profile.json"))),"migration preserves profile metadata");
        check(GameProfiles.directory(files.toFile(),id,true).equals(migrated),"migration retry is stable");
        // If both profiles exist, the established destination wins; never merge/overwrite saves.
        write(legacy.resolve("userdata/saves/map.zip"),"second legacy save");
        check(GameProfiles.directory(files.toFile(),id,true).equals(migrated),"existing destination is reused");
        check("save bytes".equals(read(migrated.toPath().resolve("userdata/saves/map.zip")))&&"second legacy save".equals(read(legacy.resolve("userdata/saves/map.zip"))),"existing destination and leftover legacy data both preserved");
        String other=GameProfiles.id("3.0.0",false);
        GameProfiles.directory(files.toFile(),other,false);
        check(Files.exists(legacy.resolve("userdata/saves/map.zip"))&&!Files.exists(files.resolve("profiles").resolve(other).resolve("userdata/saves/map.zip")),"disabled migration leaves legacy data unclaimed");
    }
    private static void activeLease(Path files) throws Exception {
        Path legacy=files.resolve("profiles/default-2.1.22");
        write(legacy.resolve("config.ini"),"[general]\nlocale=zh-CN\n");
        write(legacy.resolve("userdata/saves/live.zip"),"active save");
        String id=GameProfiles.id("3.1.0",false);
        try(RandomAccessFile held=new RandomAccessFile(legacy.resolve("session.lock").toFile(),"rw");FileLock lease=held.getChannel().lock()) {
            rejected(()->GameProfiles.directory(files.toFile(),id,true),"active session lease allowed migration");
            check(Files.isDirectory(legacy)&&!Files.exists(files.resolve("profiles").resolve(id)),"lease refusal leaves directory identity untouched");
            check("active save".equals(read(legacy.resolve("userdata/saves/live.zip")))&&"[general]\nlocale=zh-CN\n".equals(read(legacy.resolve("config.ini"))),"lease refusal preserves live saves and config");
        }
        File migrated=GameProfiles.directory(files.toFile(),id,true);
        check("active save".equals(read(migrated.toPath().resolve("userdata/saves/live.zip"))),"migration succeeds after active lease release");
        try(RandomAccessFile held=new RandomAccessFile(new File(migrated,"session.lock"),"rw");FileLock lease=held.getChannel().tryLock()) {
            check(lease!=null,"migration releases its own lease");
        }
    }
    private static void paths(Path files) throws Exception {
        Files.createDirectories(files);
        rejected(()->GameProfiles.directory(files.toFile(),null,true),"missing profile identifier accepted");
        for(String bad:new String[]{"",".","..","../escape","/absolute","a/b","a\\b","a\nother","a\u0000b",new String(new char[129]).replace('\0','a')})
            rejected(()->GameProfiles.directory(files.toFile(),bad,true),"unsafe profile identifier accepted: "+bad);
        Path ordinary=files.resolve("profiles/ordinary-file");write(ordinary,"retained file");
        rejected(()->GameProfiles.directory(files.toFile(),"ordinary-file",false),"file accepted as profile directory");
        check("retained file".equals(read(ordinary)),"invalid target preserves existing file");
        Path blocked=files.resolve("blocked");write(blocked.resolve("profiles"),"retained root file");
        rejected(()->GameProfiles.directory(blocked.toFile(),"release",false),"file accepted as profiles root");
        check("retained root file".equals(read(blocked.resolve("profiles"))),"invalid profiles root preserved");
    }
    private static void symlinks(Path files) throws Exception {
        Path outside=files.resolve("outside");Files.createDirectories(outside);write(outside.resolve("marker"),"untouched");
        Path root=files.resolve("root");Files.createDirectories(root);
        if(!link(root.resolve("profiles"),outside)) {System.out.println("GameProfilesTests: symbolic-link checks unavailable on this host");return;}
        rejected(()->GameProfiles.directory(root.toFile(),"release",true),"symlink profiles root accepted");
        check(!Files.exists(outside.resolve("release"))&&"untouched".equals(read(outside.resolve("marker"))),"symlink root refusal writes nothing outside");
        Files.delete(root.resolve("profiles"));Files.createDirectories(root.resolve("profiles"));
        link(root.resolve("profiles/release"),outside);
        rejected(()->GameProfiles.directory(root.toFile(),"release",true),"symlink profile target accepted");
        Files.delete(root.resolve("profiles/release"));
        link(root.resolve("profiles/default-2.1.22"),outside);
        rejected(()->GameProfiles.directory(root.toFile(),"release",true),"symlink legacy profile accepted");
        check(!Files.exists(root.resolve("profiles/release"))&&!Files.exists(outside.resolve("session.lock")),"symlink legacy refusal does not create target or external lease");
        Files.delete(root.resolve("profiles/default-2.1.22"));
        Path legacy=root.resolve("profiles/default-2.1.22");write(legacy.resolve("config.ini"),"preserved config");
        write(outside.resolve("external.lock"),"external lock bytes");link(legacy.resolve("session.lock"),outside.resolve("external.lock"));
        rejected(()->GameProfiles.directory(root.toFile(),"release",true),"symlink migration lease accepted");
        check(Files.exists(legacy.resolve("config.ini"))&&!Files.exists(root.resolve("profiles/release"))&&"external lock bytes".equals(read(outside.resolve("external.lock"))),"symlink lease refusal preserves source and external file");
    }
}
