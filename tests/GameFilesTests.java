package org.factorio.local.launcher;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class GameFilesTests {
    private static int checks;
    private interface Task {void run() throws Exception;}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    private static void rejected(Task task,String message) throws Exception {
        try{task.run();}catch(IOException expected){checks++;return;}throw new AssertionError(message);
    }
    private static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream output=new ZipOutputStream(bytes)) {
            for(String name:names){output.putNextEntry(new ZipEntry(name));output.write(new byte[]{1,2,3});output.closeEntry();}
        }
        return bytes.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        Path temporary=Files.createTempDirectory("factorio-files-");
        try {
            File profile=temporary.toFile();GameFiles storage=new GameFiles(profile);
            check(storage.directory("saves").equals(new File(profile.getCanonicalFile(),"userdata/saves")),"real saves directory");
            check(storage.directory("mods").isDirectory(),"real mods created");
            for(String id:new String[]{"games","saves:../outside","saves:/absolute","mods:a\\b","saves:folder//file","saves:.hidden","saves:folder/.."})
                rejected(()->storage.resolve(id),"unsafe ID "+id);
            check(storage.resolve("mods:folder/mod.zip").toPath().startsWith(storage.directory("mods").toPath()),"nested scope");
            byte[] valid=zip("map/level.dat","map/info.json");
            check(storage.importSave(new ByteArrayInputStream(valid),"地图.zip").equals("地图.zip"),"unicode save name");
            check(storage.importSave(new ByteArrayInputStream(valid),"地图.zip").equals("地图 (1).zip"),"collision numbered");
            check(Arrays.equals(valid,Files.readAllBytes(storage.resolve("saves:地图.zip").toPath())),"original never overwritten");
            check(storage.importSave(new ByteArrayInputStream(valid),"UPPER.ZIP").equals("UPPER.ZIP"),"case insensitive zip suffix");
            rejected(()->storage.importSave(new ByteArrayInputStream(valid),"map.tar"),"reject wrong extension");
            rejected(()->storage.importSave(new ByteArrayInputStream(zip("random.txt")),"not-save.zip"),"missing level");
            rejected(()->storage.importSave(new ByteArrayInputStream(zip("a/level.dat","b/level.dat")),"ambiguous.zip"),"multiple levels");
            rejected(()->storage.importSave(new ByteArrayInputStream(zip("a/level.dat","../escape")),"unsafe.zip"),"zip traversal");
            rejected(()->storage.importSave(new ByteArrayInputStream(Arrays.copyOf(valid,valid.length-12)),"broken.zip"),"truncated zip");
            rejected(()->storage.importSave(new InputStream(){public int read() throws IOException{throw new IOException("interrupted source");}},"partial.zip"),"copy failure");
            try(GameFiles.Lease held=storage.edit()) {
                rejected(()->storage.importSave(new ByteArrayInputStream(valid),"busy.zip"),"live game/edit lock");
                check(!storage.resolve("saves:busy.zip").exists(),"lock rejection leaves no file");
            }
            check(storage.importSave(new ByteArrayInputStream(valid),"after.zip").equals("after.zip"),"lease released after failures");
            Thread.currentThread().interrupt();
            try{rejected(()->storage.importSave(new ByteArrayInputStream(valid),"cancel.zip"),"cancellation");}
            finally{Thread.interrupted();}
            check(Arrays.stream(storage.directory("saves").list()).noneMatch(name->name.startsWith(".")),"temporary files cleaned");
            // Windows builds need no developer-mode symlink permission. Exercise
            // symlink escape only where the host actually permits creating one.
            Path link=storage.resolve("mods:escape").toPath();boolean linked=false;
            try{Files.createSymbolicLink(link,temporary);linked=true;}catch(IOException|UnsupportedOperationException|SecurityException unavailable){}
            if(linked){rejected(()->storage.resolve("mods:escape"),"symlink escape");Files.delete(link);}
            System.out.println("GameFilesTests: "+checks+" checks passed");
        }finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(temporary)) {
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);
            }
        }
    }
}
