package org.factorio.local.launcher;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Shared GNU dependency inventory for the single direct Zink Surface renderer. */
final class RuntimeLibraries {
    private RuntimeLibraries() {}
    static void requireLibrary(File file) throws IOException {
        if(!file.isFile()||file.length()<64)throw new IOException("Runtime component missing: "+file.getName());
        try(DataInputStream in=new DataInputStream(new FileInputStream(file))) {
            byte[] header=new byte[20];in.readFully(header);
            if(header[0]!=127||header[1]!='E'||header[2]!='L'||header[3]!='F'||header[4]!=2||header[5]!=1||
                (header[18]&255)!=183||header[19]!=0)
                throw new IOException("Runtime component is not little-endian ARM64 ELF: "+file.getName());
        }
    }
    static void alias(File root,String name,File target) throws Exception {
        if(!name.matches("(?:dri/)?[A-Za-z0-9_.+-]+")||name.contains(".."))throw new IOException("Invalid runtime alias");
        File file=new File(root,name),parent=file.getParentFile();
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot create runtime directory");
        Path link=file.toPath();
        if(Files.isSymbolicLink(link))Files.delete(link);
        else if(Files.exists(link))throw new IOException("Unexpected runtime alias file");
        Files.createSymbolicLink(link,target.toPath());
    }
    static JSONObject prepare(Context context) throws Exception {
        JSONObject manifest;
        try(InputStream input=context.getAssets().open("direct-dependencies.json");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[4096];for(int n;(n=input.read(b))!=-1;){if(out.size()+n>65536)throw new IOException("Runtime inventory too large");out.write(b,0,n);}
            manifest=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }
        String id="direct-dependencies";
        File directory=new File(context.getFilesDir(),"runtime/"+id);
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot prepare runtime dependencies");
        JSONArray rows=manifest.getJSONArray("libraries");
        Set<String> names=new HashSet<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);String packaged=row.getString("packagedName");
            if(!packaged.matches("librea_[a-z0-9_]+\\.so"))throw new IOException("Invalid dependency filename");
            File installed=new File(context.getApplicationInfo().nativeLibraryDir,packaged);
            requireLibrary(installed);
            JSONArray aliases=row.getJSONArray("aliases");if(aliases.length()<1||aliases.length()>4)throw new IOException("Invalid dependency alias set");
            for(int j=0;j<aliases.length();j++){String name=aliases.getString(j);if(!names.add(name))throw new IOException("Duplicate dependency alias");alias(directory,name,installed);}
        }
        return new JSONObject().put("id",id).put("directory",directory.getAbsolutePath()).put("libraries",rows.length());
    }
}
