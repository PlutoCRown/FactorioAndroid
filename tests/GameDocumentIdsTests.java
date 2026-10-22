package org.factorio.local.launcher;

import java.io.*;
import java.nio.file.*;
import java.util.Comparator;

/** SAF profile identity and private-tree boundaries, independent of Android APIs. */
public final class GameDocumentIdsTests {
    private static int checks;
    private interface Task {void run() throws Exception;}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    private static void rejected(Task task,String message) throws Exception {
        try{task.run();}catch(IOException expected){checks++;return;}throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        String base=GameProfiles.ID;
        GameDocumentIds.Id root=GameDocumentIds.root(base,"saves"),file=root.child("地图.zip"),nested=root.child("folder").child("map.zip");
        check(root.isRoot()&&root.root().equals("saves"),"root identity");
        check(file.document().equals(base+"|saves:地图.zip"),"profile in persistent document ID");
        check(file.parent().document().equals(root.document()),"file parent retains profile");
        check(nested.parent().document().equals(base+"|saves:folder"),"nested parent retains profile");
        check(GameDocumentIds.parse(nested.document()).local.equals("saves:folder/map.zip"),"nested round trip");
        for(String id:new String[]{"saves","saves:map.zip","other|saves",base+"|games",base+"|saves:",base+"|mods:../secret",base+"|saves:folder//map.zip",base+"|mods:.hidden",base+"|mods:a\\b",base+"|mods:a:b",base+"|mods:a|b","../"+base+"|saves"})
            rejected(()->GameDocumentIds.parse(id),"reject invalid/unbound ID "+id);
        rejected(()->GameDocumentIds.parse(null),"missing ID");
        Path temporary=Files.createTempDirectory("factorio-document-ids-");
        try {
            File files=temporary.toFile(),profile=GameProfiles.directory(files);
            GameFiles storage=new GameFiles(profile);
            Files.write(storage.resolve(file.local).toPath(),new byte[]{1});
            GameDocumentIds.Id persisted=GameDocumentIds.parse(file.document());
            GameFiles granted=new GameFiles(GameDocumentIds.directory(files,persisted.profile));
            check(Files.readAllBytes(granted.resolve(persisted.local).toPath())[0]==1,"persisted URI resolves shared profile");
            try(GameFiles.Lease held=storage.edit()) {
                rejected(()->{try(GameFiles.Lease unexpected=granted.edit()){}},"SAF uses the same profile edit lease");
            }
            rejected(()->GameDocumentIds.directory(files,"other"),"other directory identifiers are rejected");
            System.out.println("GameDocumentIdsTests: "+checks+" checks passed");
        }finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(temporary)) {
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);
            }
        }
    }
}
