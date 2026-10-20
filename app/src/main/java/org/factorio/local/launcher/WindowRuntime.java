package org.factorio.local.launcher;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.zip.*;

/** Private, hash-pinned resources for the embedded window diagnostic. */
public final class WindowRuntime {
    private static byte[] asset(Context context,String name,int limit) throws Exception {
        try(InputStream in=context.getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[65536];
            for(int n;(n=in.read(b))!=-1;) {
                if(out.size()+n>limit) throw new IOException("Window resource exceeds limit");
                out.write(b,0,n);
            }
            return out.toByteArray();
        }
    }
    private static String sha(byte[] bytes) throws Exception {
        return GameImporter.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static JSONObject prepare(Context context) throws Exception {
        JSONObject manifest=new JSONObject(new String(asset(context,"window-runtime.json",16384),StandardCharsets.UTF_8));
        String id=manifest.getString("id");
        if(!id.matches("xlorie-[0-9a-f]{12}")) throw new IOException("Window runtime identity invalid");
        File library=new File(context.getApplicationInfo().nativeLibraryDir,"libXlorie.so");
        if(library.length()!=manifest.getLong("libraryBytes") || !sha(Files.readAllBytes(library.toPath())).equals(manifest.getString("librarySha256")))
            throw new IOException("Installed window library checksum mismatch");
        byte[] archive=asset(context,"window-xkb.zip",4*1024*1024);
        if(!sha(archive).equals(manifest.getString("xkbArchiveSha256"))) throw new IOException("XKB resource checksum mismatch");
        File root=new File(context.getFilesDir(),"window-runtime/"+id),xkb=new File(root,"xkb");
        if(!xkb.isDirectory()&&!xkb.mkdirs()) throw new IOException("Cannot create private XKB directory");
        int count=0;long total=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive))) {
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;) {
                String name=entry.getName();
                if(entry.isDirectory()||!name.matches("[A-Za-z0-9_+.,()/@=-]+")||name.startsWith("/")||name.contains("..")||name.contains("//"))
                    throw new IOException("Invalid pinned XKB member");
                File target=new File(xkb,name).getCanonicalFile();
                if(!target.toPath().startsWith(xkb.getCanonicalFile().toPath())) throw new IOException("Invalid XKB destination");
                File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs()) throw new IOException("Cannot create XKB parent");
                try(OutputStream out=new FileOutputStream(target)) {
                    byte[] b=new byte[16384];for(int n;(n=zip.read(b))!=-1;) {
                        total+=n;if(total>8*1024*1024) throw new IOException("XKB data exceeds limit");out.write(b,0,n);
                    }
                }
                if(++count>1000) throw new IOException("Too many XKB members");
            }
        }
        if(count!=manifest.getInt("xkbFiles")||total!=manifest.getLong("xkbUnpackedBytes")) throw new IOException("XKB inventory mismatch");
        return manifest.put("xkbDirectory",xkb.getAbsolutePath()).put("nativeLibrary",library.getAbsolutePath());
    }
    static File prepareClients(Context context) throws Exception {
        JSONObject manifest=new JSONObject(new String(asset(context,"window-clients.json",16384),StandardCharsets.UTF_8));
        File directory=new File(context.getFilesDir(),"runtime/termux-x11-clients-1");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create X11 library directory");
        org.json.JSONArray libraries=manifest.getJSONArray("libraries");
        java.util.Set<String> expected=new java.util.HashSet<>(java.util.Arrays.asList("libX11.so.6","libxcb.so.1","libXau.so.6","libXdmcp.so.6","libXext.so.6","libXcursor.so.1","libXfixes.so.3","libXrender.so.1","libXi.so.6","libXrandr.so.2"));
        for(int i=0;i<libraries.length();i++) {
            JSONObject row=libraries.getJSONObject(i);String name=row.getString("name"),packaged=row.getString("packagedName");
            if(!expected.remove(name)||!packaged.matches("librea_[a-z0-9]+\\.so"))throw new IOException("Invalid X11 library entry");
            File installed=new File(context.getApplicationInfo().nativeLibraryDir,packaged);
            if(installed.length()!=row.getLong("bytes")||!sha(Files.readAllBytes(installed.toPath())).equals(row.getString("sha256")))throw new IOException("Installed X11 library checksum mismatch: "+name);
            Path alias=new File(directory,name).toPath();
            if(Files.isSymbolicLink(alias))Files.delete(alias);
            else if(Files.exists(alias))throw new IOException("Unexpected X11 library alias file");
            Files.createSymbolicLink(alias,installed.toPath());
        }
        if(!expected.isEmpty())throw new IOException("Incomplete X11 client library set");
        return directory;
    }
}
