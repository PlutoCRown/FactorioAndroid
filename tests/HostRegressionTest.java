import org.junit.Test;

/** Keep the host checks, including keyboard composition, in the normal Gradle test task. */
public final class HostRegressionTest {
    private void run(String name) throws Exception {
        try {Class.forName(name).getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
        catch(java.lang.reflect.InvocationTargetException e) {
            Throwable cause=e.getCause();if(cause instanceof Error)throw (Error)cause;
            if(cause instanceof Exception)throw (Exception)cause;throw new RuntimeException(cause);
        }
    }
    @Test public void archive() throws Exception {run("ArchiveTests");}
    @Test public void config() throws Exception {
        run("org.factorio.local.launcher.GameProfileConfigTests");
        run("org.factorio.local.launcher.GameProfilesTests");run("org.factorio.local.launcher.GameDocumentIdsTests");
    }
    @Test public void graphics() throws Exception {run("org.factorio.local.launcher.LiveGraphicsTests");}
    @Test public void touch() throws Exception {run("com.termux.x11.TouchMouseStateTests");}
    @Test public void gameFiles() throws Exception {run("org.factorio.local.launcher.GameFilesTests");}
    @Test public void languages() throws Exception {run("org.factorio.local.launcher.LanguagePolicyTests");}
    @Test public void keyboard() throws Exception {run("org.factorio.local.launcher.KeyboardTests");}
    @Test public void importLock() throws Exception {run("org.factorio.local.launcher.ImportLeaseTests");}
}
