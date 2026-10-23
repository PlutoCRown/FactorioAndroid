package org.factorio.local.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A fresh current log prevents a previous session's Goodbye from ending this one. */
final class SessionEngineLog {
    static void begin(File log)throws IOException {
        if(!log.exists())return;
        if(Files.isSymbolicLink(log.toPath())||!log.isFile())throw new IOException("Current engine log is not a regular file");
        Files.move(log.toPath(),new File(log.getParentFile(),"factorio-previous.log").toPath(),StandardCopyOption.REPLACE_EXISTING);
    }
    static Map<String,String> evidence(File log)throws IOException {
        if(!log.isFile())return Collections.emptyMap();
        try(RandomAccessFile input=new RandomAccessFile(log,"r")) {
            long start=Math.max(0,input.length()-65536);input.seek(start);
            byte[] bytes=new byte[(int)(input.length()-start)];input.readFully(bytes);
            String text=new String(bytes,StandardCharsets.UTF_8);
            if(start>0){int newline=text.indexOf('\n');text=newline<0?"":text.substring(newline+1);}
            LiveGraphics events=new LiveGraphics();events.accept(text);events.finish();return events.snapshot();
        }
    }
}
