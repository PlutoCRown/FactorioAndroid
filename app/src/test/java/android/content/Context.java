package android.content;

import java.io.File;

/** Host-test replacement only; never compiled into the application. */
public final class Context {
    private final File root;
    public int resolverCalls;

    public Context(File root) {
        this.root = root;
    }

    public File getFilesDir() {
        return root;
    }

    public ContentResolver getContentResolver() {
        resolverCalls++;
        throw new UnsupportedOperationException("host-test-import-open");
    }
}
