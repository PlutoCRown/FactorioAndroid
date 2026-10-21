package org.factorio.local.launcher;

import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.nio.file.Files;
import java.util.Locale;

/** SAF exposes saves/mods directly, with system-managed grants and no storage permission. */
public final class GameDocumentsProvider extends DocumentsProvider {
    static final String AUTHORITY="org.factorio.local.launcher.documents";
    private static final String[] ROOT_COLUMNS={Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS,Root.COLUMN_ICON,Root.COLUMN_MIME_TYPES,Root.COLUMN_AVAILABLE_BYTES};
    private static final String[] DOC_COLUMNS={Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE,Document.COLUMN_LAST_MODIFIED};
    @Override public boolean onCreate(){return true;}
    private GameFiles files() throws FileNotFoundException {
        try{return new GameFiles(GameSession.profileDirectory(getContext()));}catch(Exception e){throw failure(e);}
    }
    private FileNotFoundException failure(Exception e){FileNotFoundException result=new FileNotFoundException(getContext().getString(e instanceof GameFiles.BusyException?R.string.edit_game_busy:R.string.file_operation_failed));result.initCause(e);return result;}
    static Uri rootUri(String root){return DocumentsContract.buildRootUri(AUTHORITY,root);}
    static void changed(android.content.Context context,String root) {
        context.getContentResolver().notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY,root),null);
    }
    @Override public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        MatrixCursor cursor=new MatrixCursor(projection==null?ROOT_COLUMNS:projection);
        if(GameImporter.current(getContext())==null)return cursor;
        for(String root:new String[]{"saves","mods"}) {
            try {
                File dir=files().directory(root);
                cursor.newRow().add(Root.COLUMN_ROOT_ID,root).add(Root.COLUMN_DOCUMENT_ID,root)
                    .add(Root.COLUMN_TITLE,root.equals("saves")?getContext().getString(R.string.save_root_title):getContext().getString(R.string.mod_root_title))
                    .add(Root.COLUMN_FLAGS,Root.FLAG_SUPPORTS_CREATE|Root.FLAG_SUPPORTS_IS_CHILD)
                    .add(Root.COLUMN_ICON,R.mipmap.ic_launcher).add(Root.COLUMN_MIME_TYPES,"*/*").add(Root.COLUMN_AVAILABLE_BYTES,dir.getUsableSpace());
            }catch(IOException e){throw failure(e);}
        }
        return cursor;
    }
    private String mime(File file) {
        if(file.isDirectory())return Document.MIME_TYPE_DIR;
        String name=file.getName();int dot=name.lastIndexOf('.');
        String type=dot<0?null:MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot+1).toLowerCase(Locale.ROOT));
        return type==null?"application/octet-stream":type;
    }
    private void row(MatrixCursor cursor,String id) throws IOException {
        File file=files().resolve(id);if(!file.exists())throw new FileNotFoundException(getContext().getString(R.string.file_missing));
        boolean root=id.indexOf(':')<0;
        int flags=file.isDirectory()?Document.FLAG_DIR_SUPPORTS_CREATE:Document.FLAG_SUPPORTS_WRITE;
        if(!root)flags|=Document.FLAG_SUPPORTS_DELETE|Document.FLAG_SUPPORTS_RENAME;
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID,id).add(Document.COLUMN_DISPLAY_NAME,root?(id.equals("saves")?getContext().getString(R.string.saves_label):getContext().getString(R.string.mods_label)):file.getName())
            .add(Document.COLUMN_MIME_TYPE,mime(file)).add(Document.COLUMN_FLAGS,flags)
            .add(Document.COLUMN_SIZE,file.isDirectory()?null:file.length()).add(Document.COLUMN_LAST_MODIFIED,file.lastModified());
    }
    @Override public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException {
        MatrixCursor cursor=new MatrixCursor(projection==null?DOC_COLUMNS:projection);
        try{row(cursor,id);return cursor;}catch(IOException e){throw failure(e);}
    }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String sortOrder) throws FileNotFoundException {
        MatrixCursor cursor=new MatrixCursor(projection==null?DOC_COLUMNS:projection);
        try {
            GameFiles storage=files();File dir=storage.resolve(parent);File[] children=dir.listFiles();
            if(children==null)throw new IOException(getContext().getString(R.string.folder_read_failed));
            for(File child:children) {
                if(child.getName().startsWith("."))continue;
                try{row(cursor,storage.childId(parent,child.getName()));}catch(IOException ignored) {} // Never expose symlinks.
            }
            cursor.setNotificationUri(getContext().getContentResolver(),DocumentsContract.buildChildDocumentsUri(AUTHORITY,parent));return cursor;
        }catch(IOException e){throw failure(e);}
    }
    @Override public boolean isChildDocument(String parent,String child) {
        try {
            File p=files().resolve(parent),c=files().resolve(child);
            return !p.equals(c)&&c.toPath().startsWith(p.toPath());
        }catch(IOException e){return false;}
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {
        GameFiles.Lease lease=null;
        try {
            if(signal!=null)signal.throwIfCanceled();
            GameFiles storage=files();File file=storage.resolve(id);if(!file.isFile())throw new IOException(getContext().getString(R.string.file_missing));
            int access=ParcelFileDescriptor.parseMode(mode);
            if((access&ParcelFileDescriptor.MODE_WRITE_ONLY)==0)return ParcelFileDescriptor.open(file,access);
            lease=storage.edit();final GameFiles.Lease held=lease;
            ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,access,new Handler(Looper.getMainLooper()),error->{
                try{held.close();}catch(IOException ignored){}notifyParent(id);
            });
            lease=null;return descriptor;
        }catch(IOException e){throw failure(e);}finally{if(lease!=null)try{lease.close();}catch(IOException ignored){}}
    }
    private void notifyParent(String id) {
        int slash=id.lastIndexOf('/'),colon=id.indexOf(':');
        String parent=slash>=0?id.substring(0,slash):colon>=0?id.substring(0,colon):id;
        getContext().getContentResolver().notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY,parent),null);
    }
    @Override public String createDocument(String parent,String type,String name) throws FileNotFoundException {
        GameFiles storage=files();
        try(GameFiles.Lease lease=storage.edit()) {
            if(!storage.resolve(parent).isDirectory())throw new IOException(getContext().getString(R.string.not_directory));
            String id=storage.childId(parent,storage.unusedName(parent,name));File file=storage.resolve(id);
            if(!(Document.MIME_TYPE_DIR.equals(type)?file.mkdir():file.createNewFile()))throw new IOException(getContext().getString(R.string.file_create_failed));
            notifyParent(id);return id;
        }catch(IOException e){throw failure(e);}
    }
    @Override public String renameDocument(String id,String name) throws FileNotFoundException {
        GameFiles storage=files();
        try(GameFiles.Lease lease=storage.edit()) {
            int colon=id.indexOf(':');if(colon<0)throw new IOException(getContext().getString(R.string.root_rename_denied));
            int slash=id.lastIndexOf('/');String parent=id.substring(0,slash<0?colon:slash);
            GameFiles.checkName(name);File source=storage.resolve(id);
            if(source.getName().equals(name))return id;
            String next=storage.childId(parent,storage.unusedName(parent,name));
            Files.move(source.toPath(),storage.resolve(next).toPath());notifyParent(id);return next;
        }catch(IOException e){throw failure(e);}
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        GameFiles storage=files();
        try(GameFiles.Lease lease=storage.edit()) {
            if(id.indexOf(':')<0)throw new IOException(getContext().getString(R.string.root_delete_denied));
            deleteTree(storage,id);notifyParent(id);
        }catch(IOException e){throw failure(e);}
    }
    private void deleteTree(GameFiles storage,String id) throws IOException {
        File file=storage.resolve(id);
        if(file.isDirectory()) {
            File[] children=file.listFiles();if(children==null)throw new IOException(getContext().getString(R.string.folder_read_failed));
            for(File child:children)deleteTree(storage,storage.childId(id,child.getName()));
        }
        Files.delete(file.toPath());
    }
}
