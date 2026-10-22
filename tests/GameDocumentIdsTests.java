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
        String base=GameProfiles.id("2.1.22",false),expansion=GameProfiles.id("2.1.22",true);
        GameDocumentIds.Id root=GameDocumentIds.root(base,"saves"),file=root.child("地图.zip"),nested=root.child("folder").child("map.zip");
        check(root.isRoot()&&root.root().equals("saves"),"root identity");
        check(file.document().equals(base+"|saves:地图.zip"),"profile in persistent document ID");
        check(file.parent().document().equals(root.document()),"file parent retains profile");
        check(nested.parent().document().equals(base+"|saves:folder"),"nested parent retains profile");
        check(GameDocumentIds.parse(nested.document()).local.equals("saves:folder/map.zip"),"nested round trip");
        check(!GameDocumentIds.root(expansion,"saves").document().equals(root.document()),"expansion grants are distinct");
        check(GameDocumentIds.root(GameProfiles.id("2.2.0-rc.1",false),"mods").profile.endsWith("-base"),"release suffix supported");
        check(GameDocumentIds.root(GameProfiles.id("2.2.0+build",false),"mods").profile.endsWith("-base"),"build suffix supported");
        for(String id:new String[]{"saves","saves:map.zip","default-2.1.22|saves","unassigned|mods","factorio-base|saves","factorio--base|mods",base+"|games",base+"|saves:",base+"|mods:../secret",base+"|saves:folder//map.zip",base+"|mods:.hidden",base+"|mods:a\\b",base+"|mods:a:b",base+"|mods:a|b","../"+base+"|saves"})
            rejected(()->GameDocumentIds.parse(id),"reject invalid/unbound ID "+id);
        rejected(()->GameDocumentIds.parse(null),"missing ID");
        Path temporary=Files.createTempDirectory("factorio-document-ids-");
        try {
            File files=temporary.toFile(),oldProfile=GameProfiles.directory(files,base,false),newProfile=GameProfiles.directory(files,expansion,false);
            GameFiles oldFiles=new GameFiles(oldProfile),newFiles=new GameFiles(newProfile);
            Files.write(oldFiles.resolve(file.local).toPath(),new byte[]{1});
            Files.write(newFiles.resolve(file.local).toPath(),new byte[]{2});
            GameDocumentIds.Id persisted=GameDocumentIds.parse(file.document());
            GameFiles granted=new GameFiles(GameDocumentIds.directory(files,persisted.profile));
            check(Files.readAllBytes(granted.resolve(persisted.local).toPath())[0]==1,"persisted URI still resolves original profile after replacement");
            try(GameFiles.Lease held=oldFiles.edit()) {
                rejected(()->{try(GameFiles.Lease unexpected=granted.edit()){}},"SAF uses the same profile edit lease");
                try(GameFiles.Lease independent=newFiles.edit()){check(true,"other profile has independent edit lease");}
            }
            String absent=GameProfiles.id("2.2.0",false);
            rejected(()->GameDocumentIds.directory(files,absent),"stale URI cannot manufacture missing profile");
            check(!new File(files,"profiles/"+absent).exists(),"missing profile remains absent");
            Path link=new File(files,"profiles/"+absent).toPath();boolean linked=false;
            try{Files.createSymbolicLink(link,oldProfile.toPath());linked=true;}catch(IOException|UnsupportedOperationException|SecurityException unavailable){}
            if(linked){rejected(()->GameDocumentIds.directory(files,absent),"profile symlink cannot redirect grant");Files.delete(link);}
            System.out.println("GameDocumentIdsTests: "+checks+" checks passed");
        }finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(temporary)) {
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);
            }
        }
    }
}
