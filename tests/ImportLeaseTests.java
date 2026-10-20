package org.factorio.local.launcher;
import android.content.Context;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Exercise the real importer against the exact lease used by GameSession. */
public final class ImportLeaseTests {
    private static void blocked(Context context)throws Exception {
        try {GameImporter.importGame(context,null,null);throw new AssertionError("Import should be blocked");}
        catch(GameImporter.GameInUseException expected) {}
        if(context.resolverCalls!=0)throw new AssertionError("Blocked import opened a package");
    }
    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("hold")) {
            try(RandomAccessFile f=new RandomAccessFile(args[1],"rw");FileLock lease=f.getChannel().lock()) {
                System.out.println("READY");System.out.flush();System.in.read();
            }
            return;
        }
        Path root=Files.createTempDirectory("factorio-import-lease-");Context context=new Context(root.toFile());
        File lockPath=new File(GameSession.profileDirectory(context),"session.lock");
        try(RandomAccessFile file=new RandomAccessFile(lockPath,"rw");FileLock lease=file.getChannel().lock()){blocked(context);}
        System.out.println("PASS same-process lease prevents package replacement");
        Process child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java.exe").getPath(),"-cp",System.getProperty("java.class.path"),ImportLeaseTests.class.getName(),"hold",lockPath.getPath()).start();
        try {
            String ready=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8)).readLine();
            if(!"READY".equals(ready))throw new AssertionError("Child did not acquire session lease");
            blocked(context);System.out.println("PASS separate game process lease prevents package replacement");
        }finally{child.getOutputStream().close();if(!child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("Lease holder did not exit");}}
        byte[] marker="preserve existing import metadata".getBytes(StandardCharsets.UTF_8);Path descriptor=root.resolve("active-game.json");Files.write(descriptor,marker);
        for(int i=0;i<2;i++)try {
            GameImporter.importGame(context,null,null);throw new AssertionError("Host package provider should stop extraction");
        }catch(UnsupportedOperationException expected){if(!expected.getMessage().equals("host-test-import-open"))throw expected;}
        if(context.resolverCalls!=2)throw new AssertionError("Import lease not released after failure");
        System.out.println("PASS failed package open releases lease for retry");
        if(!java.util.Arrays.equals(Files.readAllBytes(descriptor),marker))throw new AssertionError("Metadata overwritten on failed import");
        try(java.util.stream.Stream<Path> children=Files.list(root.resolve("games"))){if(children.findAny().isPresent())throw new AssertionError("Failed import staging was retained");}
        System.out.println("PASS failed import preserves metadata and removes its staging");
        try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:(Iterable<Path>)paths.sorted(java.util.Comparator.reverseOrder())::iterator)Files.delete(p);}
        System.out.println("ImportLeaseTests: 4 passed");
    }
}
