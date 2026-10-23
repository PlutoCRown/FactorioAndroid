package org.factorio.local.launcher;

import android.content.Context;
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

/** SAF exposes game folders and read-only log archives through system-managed grants. */
public final class GameDocumentsProvider extends DocumentsProvider {
    static String authority(Context context) {
        return context.getPackageName() + ".documents";
    }

    private static final String[] ROOT_COLUMNS = {
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_TITLE,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_AVAILABLE_BYTES
    };
    private static final String[] DOC_COLUMNS = {
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    private GameFiles files(GameDocumentIds.Id id) throws IOException {
        return new GameFiles(GameDocumentIds.directory(getContext().getFilesDir(), id.profile));
    }

    private FileNotFoundException failure(Exception e) {
        FileNotFoundException result =
                new FileNotFoundException(
                        getContext()
                                .getString(
                                        e instanceof GameFiles.BusyException
                                                ? R.string.edit_game_busy
                                                : R.string.file_operation_failed));
        result.initCause(e);
        return result;
    }

    static Uri rootUri(Context context, String root) {
        return DocumentsContract.buildRootUri(authority(context), root);
    }

    static String rootDocumentId(Context context, String root) throws Exception {
        if ("logs".equals(root)) return "logs";
        if (GameImporter.current(context) == null)
            throw new FileNotFoundException("No imported game");
        return GameDocumentIds.root(GameSession.profileDirectory(context).getName(), root)
                .document();
    }

    static Uri documentUri(Context context, String root) throws Exception {
        return DocumentsContract.buildDocumentUri(
                authority(context), rootDocumentId(context, root));
    }

    static void changed(Context context, String root) throws Exception {
        context.getContentResolver()
                .notifyChange(
                        DocumentsContract.buildChildDocumentsUri(
                                authority(context), rootDocumentId(context, root)),
                        null);
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        try {
            File logs = SessionLogs.directory(getContext().getFilesDir());
            cursor.newRow()
                    .add(Root.COLUMN_ROOT_ID, "logs")
                    .add(Root.COLUMN_DOCUMENT_ID, "logs")
                    .add(Root.COLUMN_TITLE, getContext().getString(R.string.logs_root_title))
                    .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
                    .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                    .add(Root.COLUMN_MIME_TYPES, "application/zip")
                    .add(Root.COLUMN_AVAILABLE_BYTES, logs.getUsableSpace());
        } catch (IOException error) {
            throw failure(error);
        }
        if (GameImporter.current(getContext()) == null) return cursor;
        try {
            // Saves and mods use the same shared profile as the running game.
            String profile = GameSession.profileDirectory(getContext()).getName();
            for (String root : new String[] {"saves", "mods"}) {
                GameDocumentIds.Id id = GameDocumentIds.root(profile, root);
                File dir = files(id).directory(root);
                cursor.newRow()
                        .add(Root.COLUMN_ROOT_ID, root)
                        .add(Root.COLUMN_DOCUMENT_ID, id.document())
                        .add(
                                Root.COLUMN_TITLE,
                                root.equals("saves")
                                        ? getContext().getString(R.string.save_root_title)
                                        : getContext().getString(R.string.mod_root_title))
                        .add(
                                Root.COLUMN_FLAGS,
                                Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD)
                        .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                        .add(Root.COLUMN_MIME_TYPES, "*/*")
                        .add(Root.COLUMN_AVAILABLE_BYTES, dir.getUsableSpace());
            }
        } catch (Exception e) {
            throw failure(e);
        }
        return cursor;
    }

    private String mime(File file) {
        if (file.isDirectory()) return Document.MIME_TYPE_DIR;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String type =
                dot < 0
                        ? null
                        : MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(
                                        name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return type == null ? "application/octet-stream" : type;
    }

    private void row(MatrixCursor cursor, GameFiles storage, GameDocumentIds.Id id)
            throws IOException {
        File file = storage.resolve(id.local);
        if (!file.exists())
            throw new FileNotFoundException(getContext().getString(R.string.file_missing));
        int flags =
                file.isDirectory()
                        ? Document.FLAG_DIR_SUPPORTS_CREATE
                        : Document.FLAG_SUPPORTS_WRITE;
        if (!id.isRoot()) flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        cursor.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, id.document())
                .add(
                        Document.COLUMN_DISPLAY_NAME,
                        id.isRoot()
                                ? (id.root().equals("saves")
                                        ? getContext().getString(R.string.saves_label)
                                        : getContext().getString(R.string.mods_label))
                                : file.getName())
                .add(Document.COLUMN_MIME_TYPE, mime(file))
                .add(Document.COLUMN_FLAGS, flags)
                .add(Document.COLUMN_SIZE, file.isDirectory() ? null : file.length())
                .add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    @Override
    public Cursor queryDocument(String document, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOC_COLUMNS : projection);
        if (logDocument(document))
            try {
                logRow(cursor, document);
                return cursor;
            } catch (IOException error) {
                throw failure(error);
            }
        try {
            GameDocumentIds.Id id = GameDocumentIds.parse(document);
            row(cursor, files(id), id);
            return cursor;
        } catch (IOException e) {
            throw failure(e);
        }
    }

    @Override
    public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOC_COLUMNS : projection);
        if (logDocument(parent))
            try {
                if (!"logs".equals(parent)) throw new IOException("Not a log directory");
                File[] children = SessionLogs.directory(getContext().getFilesDir()).listFiles();
                if (children == null) throw new IOException("Log directory unavailable");
                java.util.Arrays.sort(
                        children, java.util.Comparator.comparing(File::getName).reversed());
                for (File child : children)
                    if (child.getName().matches("session-[0-9]+\\.zip")) {
                        try {
                            logRow(cursor, "logs:" + child.getName());
                        } catch (IOException ignored) {
                        }
                    }
                cursor.setNotificationUri(
                        getContext().getContentResolver(),
                        DocumentsContract.buildChildDocumentsUri(authority(getContext()), "logs"));
                return cursor;
            } catch (IOException error) {
                throw failure(error);
            }
        try {
            GameDocumentIds.Id id = GameDocumentIds.parse(parent);
            GameFiles storage = files(id);
            File dir = storage.resolve(id.local);
            File[] children = dir.listFiles();
            if (children == null)
                throw new IOException(getContext().getString(R.string.folder_read_failed));
            for (File child : children) {
                if (child.getName().startsWith(".")) continue;
                try {
                    row(cursor, storage, id.child(child.getName()));
                } catch (IOException ignored) {
                } // Never expose symlinks.
            }
            cursor.setNotificationUri(
                    getContext().getContentResolver(),
                    DocumentsContract.buildChildDocumentsUri(authority(getContext()), parent));
            return cursor;
        } catch (IOException e) {
            throw failure(e);
        }
    }

    @Override
    public boolean isChildDocument(String parent, String child) {
        if (logDocument(parent) || logDocument(child))
            try {
                return "logs".equals(parent)
                        && !"logs".equals(child)
                        && SessionLogs.resolve(getContext().getFilesDir(), child).isFile();
            } catch (IOException error) {
                return false;
            }
        try {
            GameDocumentIds.Id p = GameDocumentIds.parse(parent), c = GameDocumentIds.parse(child);
            if (!p.profile.equals(c.profile) || !p.root().equals(c.root())) return false;
            GameFiles storage = files(p);
            File directory = storage.resolve(p.local), file = storage.resolve(c.local);
            return !directory.equals(file) && file.toPath().startsWith(directory.toPath());
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(
            String document, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (logDocument(document))
            try {
                if (signal != null) signal.throwIfCanceled();
                if (!"r".equals(mode) || "logs".equals(document))
                    throw new IOException("Logs are read-only files");
                SessionExitDiagnostics.collect(getContext());
                return ParcelFileDescriptor.open(
                        SessionLogs.refresh(getContext().getFilesDir(), document),
                        ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (Exception error) {
                throw failure(error);
            }
        GameFiles.Lease lease = null;
        try {
            if (signal != null) signal.throwIfCanceled();
            GameDocumentIds.Id id = GameDocumentIds.parse(document);
            GameFiles storage = files(id);
            File file = storage.resolve(id.local);
            if (!file.isFile())
                throw new IOException(getContext().getString(R.string.file_missing));
            int access = ParcelFileDescriptor.parseMode(mode);
            if ((access & ParcelFileDescriptor.MODE_WRITE_ONLY) == 0)
                return ParcelFileDescriptor.open(file, access);
            lease = storage.edit();
            final GameFiles.Lease held = lease;
            ParcelFileDescriptor descriptor =
                    ParcelFileDescriptor.open(
                            file,
                            access,
                            new Handler(Looper.getMainLooper()),
                            error -> {
                                try {
                                    held.close();
                                } catch (IOException ignored) {
                                }
                                notifyParent(id);
                            });
            lease = null;
            return descriptor;
        } catch (IOException e) {
            throw failure(e);
        } finally {
            if (lease != null)
                try {
                    lease.close();
                } catch (IOException ignored) {
                }
        }
    }

    private void notifyParent(GameDocumentIds.Id id) {
        getContext()
                .getContentResolver()
                .notifyChange(
                        DocumentsContract.buildChildDocumentsUri(
                                authority(getContext()), id.parent().document()),
                        null);
    }

    private static boolean logDocument(String id) {
        return "logs".equals(id) || (id != null && id.startsWith("logs:"));
    }

    private void logRow(MatrixCursor cursor, String id) throws IOException {
        File file = SessionLogs.resolve(getContext().getFilesDir(), id);
        boolean root = "logs".equals(id);
        cursor.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, id)
                .add(
                        Document.COLUMN_DISPLAY_NAME,
                        root ? getContext().getString(R.string.logs_root_title) : file.getName())
                .add(Document.COLUMN_MIME_TYPE, root ? Document.MIME_TYPE_DIR : "application/zip")
                .add(Document.COLUMN_FLAGS, 0)
                .add(Document.COLUMN_SIZE, root ? null : file.length())
                .add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    @Override
    public String createDocument(String parent, String type, String name)
            throws FileNotFoundException {
        try {
            GameDocumentIds.Id id = GameDocumentIds.parse(parent);
            GameFiles storage = files(id);
            try (GameFiles.Lease lease = storage.edit()) {
                if (!storage.resolve(id.local).isDirectory())
                    throw new IOException(getContext().getString(R.string.not_directory));
                GameDocumentIds.Id child = id.child(storage.unusedName(id.local, name));
                File file = storage.resolve(child.local);
                if (!(Document.MIME_TYPE_DIR.equals(type) ? file.mkdir() : file.createNewFile()))
                    throw new IOException(getContext().getString(R.string.file_create_failed));
                notifyParent(child);
                return child.document();
            }
        } catch (IOException e) {
            throw failure(e);
        }
    }

    @Override
    public String renameDocument(String document, String name) throws FileNotFoundException {
        try {
            GameDocumentIds.Id id = GameDocumentIds.parse(document);
            GameFiles storage = files(id);
            try (GameFiles.Lease lease = storage.edit()) {
                if (id.isRoot())
                    throw new IOException(getContext().getString(R.string.root_rename_denied));
                GameDocumentIds.Id parent = id.parent();
                GameFiles.checkName(name);
                File source = storage.resolve(id.local);
                if (source.getName().equals(name)) return id.document();
                GameDocumentIds.Id next = parent.child(storage.unusedName(parent.local, name));
                Files.move(source.toPath(), storage.resolve(next.local).toPath());
                notifyParent(id);
                return next.document();
            }
        } catch (IOException e) {
            throw failure(e);
        }
    }

    @Override
    public void deleteDocument(String document) throws FileNotFoundException {
        try {
            GameDocumentIds.Id id = GameDocumentIds.parse(document);
            GameFiles storage = files(id);
            try (GameFiles.Lease lease = storage.edit()) {
                if (id.isRoot())
                    throw new IOException(getContext().getString(R.string.root_delete_denied));
                deleteTree(storage, id.local);
                notifyParent(id);
            }
        } catch (IOException e) {
            throw failure(e);
        }
    }

    private void deleteTree(GameFiles storage, String id) throws IOException {
        File file = storage.resolve(id);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null)
                throw new IOException(getContext().getString(R.string.folder_read_failed));
            for (File child : children) deleteTree(storage, storage.childId(id, child.getName()));
        }
        Files.delete(file.toPath());
    }
}
