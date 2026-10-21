package org.factorio.local.launcher;

import java.io.*;

/** Measured stage progress; compressed bytes are not an overall time estimate. */
final class ImportProgress {
    enum Stage { OPEN, EXTRACT, VERIFY, FINISH }
    final Stage stage;
    final long inputBytes,totalBytes,outputBytes,elapsedSeconds;
    final int files;
    ImportProgress(Stage stage,long input,long total,long output,int files,long elapsed) {
        this.stage=stage;this.inputBytes=input;this.totalBytes=total;this.outputBytes=output;
        this.files=files;this.elapsedSeconds=elapsed;
    }
    float fraction() {
        // XZ may read ahead; reserve completion until its checksum and tar trailer pass.
        return stage==Stage.EXTRACT&&totalBytes>0?Math.min(.99f,(float)((double)inputBytes/totalBytes)):-1f;
    }
    static final class Input extends FilterInputStream {
        long bytes;
        Input(InputStream input){super(input);}
        @Override public int read()throws IOException {int value=in.read();if(value>=0)bytes++;return value;}
        @Override public int read(byte[] b,int off,int len)throws IOException {int n=in.read(b,off,len);if(n>0)bytes+=n;return n;}
        @Override public long skip(long n)throws IOException {long skipped=in.skip(n);bytes+=skipped;return skipped;}
    }
    static final class Reporter {
        final GameImporter.Progress callback;final long started=System.nanoTime();
        long last;Stage previous;
        Reporter(GameImporter.Progress callback){this.callback=callback;}
        void update(Stage stage,long input,long total,long output,int files,boolean force) {
            long now=System.nanoTime();
            if(callback!=null&&(force||stage!=previous||now-last>=500_000_000L)) {
                callback.update(new ImportProgress(stage,input,total,output,files,(now-started)/1_000_000_000L));
                last=now;previous=stage;
            }
        }
    }
}
