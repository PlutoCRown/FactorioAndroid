package org.factorio.local.launcher;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;

/** Local game library, launch preferences and device information. */
public final class MainActivity extends androidx.activity.ComponentActivity {
    private static final int IMPORT=10, IMPORT_SAVE=11, HOME=0, SETTINGS=1, DEVICE=2;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LauncherUi ui;
    private JSONObject gameReport,deviceReport;
    private int page=HOME;
    private boolean busy;
    private float progressFraction=-1f;
    private String message="";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(resumeExistingGame()){finish();return;}
        WindowPolicy.apply(this);
        worker.execute(()->SessionExitDiagnostics.collect(getApplicationContext()));
        gameReport=GameImporter.current(this);
        try {deviceReport=GameImporter.readJson(new File(getFilesDir(),"device-report.json"));}catch(Exception ignored) {}
        if(state!=null)page=state.getInt("page",HOME);
        if(page==SETTINGS&&gameReport==null)page=HOME;
        migratePreferences();
        ui=new LauncherUi(this);
        ui.mount();
        renderPage();
        getOnBackPressedDispatcher().addCallback(this,new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if(page!=HOME){navigate(HOME);return;}
                if(busy){new AlertDialog.Builder(MainActivity.this).setMessage(getString(R.string.work_in_progress)).setPositiveButton(getString(R.string.keep_waiting),null).show();return;}
                setEnabled(false);getOnBackPressedDispatcher().onBackPressed();setEnabled(true);
            }
        });
        // Preserve the existing developer-only automation entry, without exposing
        // probes, log paths or raw runtime reports in the normal launcher UI.
        if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
                &&getIntent().getBooleanExtra("runtimeProbe",false)) {
            String mode=getIntent().getStringExtra("runtimeProbeMode");probeRuntime("help".equals(mode)?"help":"version");
        }
    }
    private void migratePreferences() {
        // All legacy renderer selections now converge on the sole supported path.
        getPreferences(0).edit().remove("renderer").remove("hostZinkRendererMigration")
            .remove("rendererName").apply();
    }
    private void navigate(int destination) {
        if(destination==SETTINGS&&gameReport==null)return;
        page=destination;renderPage();
    }
    private void renderPage() {applyBusy();}
    private void applyBusy() {if(ui!=null)ui.show(page,busy,message,gameReport,deviceReport,progressFraction);}
    /** UI events stay on the main thread; existing import/session code owns I/O. */
    public void performUiAction(String action) {
        if(action.equals("home")){navigate(HOME);return;}
        if(action.equals("settings")){navigate(SETTINGS);return;}
        if(action.equals("device")){navigate(DEVICE);return;}
        if(busy)return;
        switch(action) {
            case "play":if(gameReport==null)chooseGame();else startGame();break;
            case "choose":chooseGame();break;
            case "save":chooseSave();break;
            case "saves":openGameFolder("saves");break;
            case "mods":openGameFolder("mods");break;
            case "help":downloadHelp();break;
            case "probe":probe();break;
        }
    }
    public void setTouchMouseEnabled(boolean enabled) {if(!busy){getPreferences(0).edit().putBoolean("touchMouseSimulation",enabled).apply();renderPage();}}
    public void setRenderScale(int percent) {if(!busy&&(percent==50||percent==75||percent==100)){getPreferences(0).edit().putInt("renderScalePercent",percent).apply();renderPage();}}
    public void setSpriteLoadingLimit(int limit) {
        if(!busy&&GameProfileConfig.validSpriteLoadingLimit(limit)){
            getPreferences(0).edit().putInt("spriteLoadingLimit",limit).apply();renderPage();
        }
    }
    public void setGraphicsPreset(String preset) {
        if(!busy&&java.util.Arrays.asList("compat","balanced","quality","game").contains(preset)){
            getPreferences(0).edit().putString("graphicsPreset",preset).apply();renderPage();
        }
    }
    private void setBusy(boolean value,String text) {
        busy=value;message=text;progressFraction=-1f;applyBusy();
        getWindow().setFlags(value?WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON:0,WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void chooseGame() {
        if(busy)return;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");startActivityForResult(intent,IMPORT);
    }
    private void chooseSave() {
        if(busy)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try{startActivityForResult(intent,IMPORT_SAVE);}catch(ActivityNotFoundException e){Toast.makeText(this,getString(R.string.picker_missing),Toast.LENGTH_LONG).show();}
    }
    private void openGameFolder(String root) {
        if(busy)return;
        try {
            new GameFiles(GameSession.profileDirectory(this)).directory(root);
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(GameDocumentsProvider.rootUri(root),"vnd.android.document/root"));
        }catch(ActivityNotFoundException e) {
            // OEM file managers may omit ACTION_VIEW for SAF roots. The system
            // browser can still show this exact directory as a document tree.
            Intent browser=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                android.provider.DocumentsContract.buildDocumentUri(GameDocumentsProvider.AUTHORITY,root));
            try{startActivityForResult(browser,12);}catch(ActivityNotFoundException missing){Toast.makeText(this,getString(R.string.folder_sidebar_hint,getString(root.equals("saves")?R.string.save_root_title:R.string.mod_root_title)),Toast.LENGTH_LONG).show();}
        }catch(Exception e){fail(getString(R.string.folder_open_failed),e);}
    }
    private void importSave(Uri uri) {
        setBusy(true,getString(R.string.save_import_progress));
        worker.execute(()-> {
            try {
                String name=getString(R.string.default_save_name);
                try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)) {
                    if(cursor!=null&&cursor.moveToFirst()&&!cursor.isNull(0))name=cursor.getString(0);
                }
                String imported;
                try(InputStream input=getContentResolver().openInputStream(uri)) {
                    if(input==null)throw new IOException(getString(R.string.save_read_failed));
                    imported=new GameFiles(GameSession.profileDirectory(this)).importSave(input,name);
                }
                GameDocumentsProvider.changed(this,"saves");
                runOnUiThread(()-> {if(isDestroyed())return;setBusy(false,getString(R.string.save_imported_name,imported));Toast.makeText(this,getString(R.string.save_import_done),Toast.LENGTH_LONG).show();});
            }catch(Exception e) {
                android.util.Log.e("FactorioLauncher","Save import failed",e);
                runOnUiThread(()-> {if(isFinishing()||isDestroyed())return;setBusy(false,getString(R.string.save_import_failed));
                    new AlertDialog.Builder(this).setTitle(getString(R.string.save_import_failed)).setMessage(getString(e instanceof GameFiles.BusyException?R.string.edit_game_busy:e instanceof GameFiles.SaveFormatException?R.string.save_import_invalid:R.string.save_import_copy_failed))
                        .setPositiveButton(getString(R.string.ok),null).show();});
            }
        });
    }
    private void downloadHelp() {
        new AlertDialog.Builder(this).setTitle(getString(R.string.get_game))
            .setMessage(getString(R.string.download_instructions))
            .setPositiveButton(getString(R.string.open_website),(d,w)-> {try {startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://factorio.com/download/experimental")));}
                catch(ActivityNotFoundException e){message=getString(R.string.website_fallback);applyBusy();}}).setNegativeButton(getString(R.string.close),null).show();
    }
    private void startGame() {
        if(busy||gameReport==null)return;
        Intent game=new Intent(this,com.termux.x11.MainActivity.class).putExtra("playGame",true)
            .putExtra("touchMouseSimulation",getPreferences(0).getBoolean("touchMouseSimulation",true))
            .putExtra("renderScalePercent",getPreferences(0).getInt("renderScalePercent",100))
            .putExtra("spriteLoadingLimit",getPreferences(0).getInt("spriteLoadingLimit",1))
            .putExtra("graphicsPreset",getPreferences(0).getString("graphicsPreset","compat"));startActivity(game);
    }
    private void probe() {
        if(busy)return;setBusy(true,getString(R.string.device_check_progress));
        worker.execute(()-> {try {
            JSONObject result=DeviceProbe.collect(getApplicationContext());
            Files.write(new File(getFilesDir(),"device-report.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));
            runOnUiThread(()-> {if(isDestroyed())return;deviceReport=result;setBusy(false,getString(R.string.device_check_done));renderPage();});
        }catch(Exception e){fail(getString(R.string.device_check_failed),e);}});
    }
    private void probeRuntime(String mode) {
        if(busy)return;setBusy(true,getString(R.string.runtime_check_progress));
        worker.execute(()-> {try {
            JSONObject result=RuntimeProbe.collect(getApplicationContext(),mode);
            String file=mode.equals("version")?"runtime-report.json":mode.equals("help")?"engine-help-report.json":"graphics-report.json";
            Files.write(new File(getFilesDir(),file).toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));
            runOnUiThread(()-> {if(!isDestroyed())setBusy(false,result.optBoolean("passed")?getString(R.string.runtime_check_done):getString(R.string.runtime_check_fail));});
        }catch(Exception e){fail(getString(R.string.check_failed),e);}});
    }
    private void fail(String title,Exception error) {
        android.util.Log.e("FactorioLauncher",title,error);
        runOnUiThread(()-> {if(isFinishing()||isDestroyed())return;setBusy(false,title);
            new AlertDialog.Builder(this).setTitle(title).setMessage(error instanceof GameImporter.GameInUseException?getString(R.string.replace_busy):title.equals(getString(R.string.game_import_failed))?getString(R.string.game_import_retry):getString(R.string.try_again))
                .setPositiveButton(getString(R.string.ok),null).show();});
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null||busy)return;
        if(request==IMPORT_SAVE){importSave(data.getData());return;}
        if(request!=IMPORT)return;
        Uri uri=data.getData();setBusy(true,getString(R.string.game_import_progress));
        worker.execute(()-> {try {
            JSONObject report=GameImporter.importGame(getApplicationContext(),uri,progress->runOnUiThread(()-> {
                if(!isDestroyed()){message=importProgress(progress);progressFraction=progress.fraction();applyBusy();}
            }));
            getContentResolver().notifyChange(android.provider.DocumentsContract.buildRootsUri(GameDocumentsProvider.AUTHORITY),null);
            runOnUiThread(()-> {if(isDestroyed())return;gameReport=report;page=HOME;setBusy(false,getString(R.string.game_added));renderPage();});
        }catch(Exception e){fail(getString(R.string.game_import_failed),e);}});
    }
    private String importProgress(ImportProgress progress) {
        String title;
        switch(progress.stage) {
            case OPEN:title=getString(R.string.import_stage_open);break;
            case EXTRACT:title=getString(R.string.import_stage_extract);break;
            case VERIFY:title=getString(R.string.import_stage_verify);break;
            default:title=getString(R.string.import_stage_finish);
        }
        String details="";
        if(progress.stage==ImportProgress.Stage.EXTRACT) {
            String read=progress.totalBytes>0
                ?getString(R.string.import_read_known,(int)(progress.fraction()*100),progress.inputBytes/1048576.0,progress.totalBytes/1048576.0)
                :getString(R.string.import_read_unknown,progress.inputBytes/1048576.0);
            details="\n"+read+"\n"+getString(R.string.import_extracted,progress.outputBytes/1048576.0,progress.files);
        }
        return title+details+"\n"+getString(R.string.import_elapsed,progress.elapsedSeconds/60,progress.elapsedSeconds%60)
            +"\n"+getString(R.string.import_duration_hint);
    }
    private boolean resumeExistingGame() {
        if(getIntent().hasExtra("gameExitMessage")||!GameSession.hasActiveWindow(this))return false;
        startActivity(new Intent(this,com.termux.x11.MainActivity.class).putExtra("playGame",true)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP));return true;
    }
    private void showInterruptedSessionIfNeeded() {
        try {
            File file=new File(getFilesDir(),"game-session-report.json");if(!file.isFile()||file.length()>2097152)return;
            JSONObject r=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
            if(!r.optBoolean("userStarted")||r.optBoolean("activityClosed")||r.optBoolean("sessionComplete"))return;
            int pid=r.optInt("pid",-1);if(pid>0&&new File("/proc/"+pid).exists())return;
            long epoch=r.optLong("collectedAtEpochMillis");if(epoch<=0||getPreferences(0).getLong("interruptedSessionNotified",0)==epoch)return;
            getPreferences(0).edit().putLong("interruptedSessionNotified",epoch).apply();
            Toast.makeText(this,getString(R.string.session_interrupted),Toast.LENGTH_LONG).show();
        }catch(Exception ignored) {}
    }
    private void showGameExitMessage() {
        String text=getIntent().getStringExtra("gameExitMessage");getIntent().removeExtra("gameExitMessage");
        if(text!=null&&!text.isEmpty()){android.util.Log.i("FactorioLauncher","Game exit: "+text);Toast.makeText(this,text.length()>240?text.substring(0,240):text,Toast.LENGTH_LONG).show();}
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);setIntent(intent);
        if(resumeExistingGame()){finish();return;}showGameExitMessage();
    }
    @Override protected void onResume() {
        super.onResume();WindowPolicy.apply(this);
        if(!isFinishing()&&resumeExistingGame()){finish();return;}
        showGameExitMessage();showInterruptedSessionIfNeeded();
    }
    @Override public void onWindowFocusChanged(boolean focus) {super.onWindowFocusChanged(focus);if(focus)WindowPolicy.apply(this);}
    @Override public void onConfigurationChanged(Configuration config) {super.onConfigurationChanged(config);WindowPolicy.apply(this);}
    @Override public void onMultiWindowModeChanged(boolean mode,Configuration config) {super.onMultiWindowModeChanged(mode,config);WindowPolicy.apply(this);}
    @Override protected void onSaveInstanceState(Bundle state) {state.putInt("page",page);super.onSaveInstanceState(state);}
    @Override protected void onDestroy() {worker.shutdownNow();super.onDestroy();}
}
