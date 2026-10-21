/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.termux.x11;

import android.app.Activity;
import org.factorio.local.launcher.R;
import android.content.res.Configuration;
import org.factorio.local.launcher.WindowPolicy;
import android.os.*;
import android.system.Os;
import android.view.*;
import android.widget.*;
import org.factorio.local.launcher.WindowRuntime;
import org.factorio.local.launcher.RuntimeProbe;
import org.factorio.local.launcher.GameSession;
import org.factorio.local.launcher.ControllerRelay;
import org.json.JSONObject;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Debug-only, separate-process embedded X-server/Surface diagnostic. */
public final class MainActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private JSONObject report;
    private LorieView view;
    private CmdEntryPoint server;
    private TextView status;
    private boolean automaticStatusHidden;
    private boolean attached,surfaceValid,syncEchoed,closed,graphicsStarted,resumed,directSurfaceReady,returningToLauncher;
    private File serverTemp;
    private boolean manualSession;
    private GameSession gameSession;
    private ControllerRelay controllerRelay;
    private org.factorio.local.launcher.KeyboardBridge keyboardBridge;
    private Thread sessionThread;
    private int renderScalePercent=100;
    public static native void nativeSetLaunchWindowingModeFreeform(Object options);
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        manualSession=getIntent().getBooleanExtra("playGame",false);
        int requestedScale=getIntent().getIntExtra("renderScalePercent",100);
        if(requestedScale==50||requestedScale==75||requestedScale==100)renderScalePercent=requestedScale;
        if(!manualSession&&((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0 || !getIntent().getBooleanExtra("windowProbe",false))) {finish();return;}
        // Only this debug Activity: display our diagnostic above the keyguard,
        // without dismissing it or accessing any other application's screen.
        if(!manualSession&&Build.VERSION.SDK_INT>=27) {setShowWhenLocked(true);setTurnScreenOn(true);}
        WindowPolicy.apply(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        FrameLayout layout=new FrameLayout(this);layout.setBackgroundColor(android.graphics.Color.BLACK);
        status=new TextView(this);status.setText(manualSession?getString(R.string.window_preparing):getIntent().getBooleanExtra("windowGraphics",false)?getString(R.string.window_probe_start):getString(R.string.window_probe_only));
        status.setPadding(dp(12),dp(8),dp(12),dp(8));status.setTextSize(12);status.setMaxLines(4);
        status.setTextColor(android.graphics.Color.WHITE);status.setBackgroundColor(0xc0202630);
        FrameLayout.LayoutParams statusPosition=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);
        statusPosition.setMargins(dp(12),dp(12),dp(76),0);status.setMaxWidth(dp(560));layout.addView(status,statusPosition);
        Button menu=new Button(this);menu.setText(getString(R.string.session));menu.setAllCaps(false);menu.setContentDescription(getString(R.string.session_menu));
        menu.setMinWidth(0);menu.setMinimumWidth(0);menu.setTextSize(12);menu.setAlpha(0.72f);
        FrameLayout.LayoutParams menuPosition=new FrameLayout.LayoutParams(dp(64),dp(48),Gravity.BOTTOM|Gravity.END);
        menuPosition.setMargins(0,0,dp(8),dp(8));layout.addView(menu,menuPosition);
        menu.setOnClickListener(anchor-> {
            PopupMenu popup=new PopupMenu(this,anchor);
            if(!manualSession)popup.getMenu().add(getString(R.string.show_runtime_info)).setOnMenuItemClickListener(item->{try {
                new android.app.AlertDialog.Builder(this).setTitle(getString(R.string.runtime_info))
                    .setMessage(org.factorio.local.launcher.RuntimeGraphicsInfo.describe(this)).setPositiveButton(getString(R.string.close),null).show();
            }catch(Exception e){fail(e);}return true;});
            if(view!=null&&view.keyboardRequested())popup.getMenu().add(getString(R.string.show_keyboard))
                .setOnMenuItemClickListener(item->{view.showGameKeyboard();return true;});
            popup.getMenu().add(status.getVisibility()==View.VISIBLE?getString(R.string.hide_startup_status):getString(R.string.show_startup_status)).setOnMenuItemClickListener(item->{status.setVisibility(status.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);return true;});
            popup.getMenu().add(getString(R.string.end_session_home)).setOnMenuItemClickListener(item->{if(manualSession)askStop();else finish();return true;});
            popup.show();
        });
        layout.setOnApplyWindowInsetsListener((v,insets)-> {
            if(Build.VERSION.SDK_INT>=28&&insets.getDisplayCutout()!=null) {
                DisplayCutout cutout=insets.getDisplayCutout();
                statusPosition.leftMargin=dp(12)+cutout.getSafeInsetLeft();statusPosition.topMargin=dp(12)+cutout.getSafeInsetTop();status.setLayoutParams(statusPosition);

            }
            if(view!=null&&Build.VERSION.SDK_INT>=30)view.imeVisibilityChanged(insets.isVisible(WindowInsets.Type.ime()));
            int safeRight=0,safeBottom=0;
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                safeRight=safe.right;safeBottom=safe.bottom;
            } else {
                safeRight=insets.getSystemWindowInsetRight();safeBottom=insets.getSystemWindowInsetBottom();
                if(Build.VERSION.SDK_INT>=28&&insets.getDisplayCutout()!=null) {
                    safeRight=Math.max(safeRight,insets.getDisplayCutout().getSafeInsetRight());
                    safeBottom=Math.max(safeBottom,insets.getDisplayCutout().getSafeInsetBottom());
                }
            }
            menuPosition.rightMargin=dp(8)+safeRight;menuPosition.bottomMargin=dp(8)+safeBottom;menu.setLayoutParams(menuPosition);
            return insets;
        });
        setContentView(layout);applyImmersiveMode();
        if(manualSession){gameSession=new GameSession(getIntent().getStringExtra("graphicsPreset"),getIntent().getIntExtra("spriteLoadingLimit",1));updateBackgroundPause();}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            report=new JSONObject().put("schema",1).put("uid",android.os.Process.myUid()).put("pid",android.os.Process.myPid())
                .put("factorioLaunched",false).put("linuxX11ClientConnected",false).put("hardwareOpenGLPassed",false)
                .put("collectedAtEpochMillis",System.currentTimeMillis()).put("userStarted",manualSession).put("unlimitedSession",manualSession)
                .put("spriteLoadingLimit",getIntent().getIntExtra("spriteLoadingLimit",1))
                .put("graphicsPreset",manualSession?(getIntent().getStringExtra("graphicsPreset")==null?"compat":getIntent().getStringExtra("graphicsPreset")):"diagnostic");
            if(manualSession) {
                controllerRelay=new ControllerRelay(this,false);
                handler.postDelayed(this::sampleController,10000);
            }
            record("preparing");
        } catch(Exception e) {fail(e);return;}
        new Thread(()-> {
            try {
                JSONObject prepared=WindowRuntime.prepare(getApplicationContext());
                runOnUiThread(()-> {
                    try {
                        report.put("resources",prepared);record("resourcesVerified");
                        File tmp=new File(getCacheDir(),"x11");serverTemp=tmp;if(!tmp.isDirectory()&&!tmp.mkdirs())throw new Exception("Cannot create X11 private temporary directory");
                        Os.setenv("TMPDIR",tmp.getAbsolutePath(),true);Os.setenv("XKB_CONFIG_ROOT",prepared.getString("xkbDirectory"),true);
                        Os.setenv("HOME",getFilesDir().getAbsolutePath(),true);
                        System.loadLibrary("Xlorie");report.put("nativeLibraryLoaded",true);record("nativeLibraryLoaded");
                        server=new CmdEntryPoint(this);
                        boolean started=server.start(new String[]{":93","-nolisten","tcp","-noreset","-ac","-dpi","96"});
                        report.put("serverStartAccepted",started);record("serverStartRequested");
                        if(!started)throw new Exception("Embedded X server declined start");
                        view=new LorieView(this,renderScalePercent,getIntent().getBooleanExtra("touchMouseSimulation",true));layout.addView(view,0,new FrameLayout.LayoutParams(-1,-1));
                        view.setImeResumed(resumed);
                        if(manualSession) {
                            keyboardBridge=new org.factorio.local.launcher.KeyboardBridge(this,request->handler.post(()-> {
                                if(!closed&&view!=null){view.setGameKeyboard(request);try{record(request.show?"keyboardRequested":"keyboardClosed");}catch(Exception ignored){}}
                            }));
                            gameSession.setKeyboardEnvironment(keyboardBridge.environment());
                            SurfaceView directSurface=new SurfaceView(this);
                            directSurface.setZOrderMediaOverlay(true);
                            directSurface.setClickable(false);directSurface.setFocusable(false);
                            directSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
                                public void surfaceCreated(SurfaceHolder h) {org.factorio.local.launcher.DirectPresentActivity.startGameSurface(getApplicationContext(),h.getSurface());directSurfaceReady=true;updateBackgroundPause();}
                                public void surfaceChanged(SurfaceHolder h,int format,int width,int height) {}
                                public void surfaceDestroyed(SurfaceHolder h) {view.cancelTouch();directSurfaceReady=false;updateBackgroundPause();org.factorio.local.launcher.DirectPresentActivity.detach();}
                            });
                            layout.addView(directSurface,1,new FrameLayout.LayoutParams(-1,-1));
                        }
                        handler.postDelayed(this::attach,1500);
                        handler.postDelayed(this::sample,6000);
                        handler.postDelayed(this::sample,12000);
                        if(!manualSession)handler.postDelayed(()-> {try {sample();record("finished");} catch(Exception e) {fail(e);} finish();},getIntent().getBooleanExtra("windowGraphics",false)?150000:20000);
                    } catch(Throwable e) {fail(e);}
                });
            } catch(Throwable e) {runOnUiThread(()->fail(e));}
        },"window-resource-prepare").start();
    }
    private void attach() {
        if(closed||attached||server==null||view==null)return;
        // A split/freeform transition can briefly remove the Surface during
        // startup. Attach once it is usable instead of aborting the session.
        if(!surfaceValid||view.renderWidth()<2||view.renderHeight()<2){handler.postDelayed(this::attach,200);return;}
        try {
            ParcelFileDescriptor connection=server.getXConnection();
            if(connection==null)throw new Exception("No window protocol descriptor");
            view.attach(connection.detachFd());attached=true;record("windowProtocolAttached");
        } catch(Throwable e) {fail(e);}
    }
    public void windowSizeReady(int width,int height,int surfaceWidth,int surfaceHeight,int percent) {
        if(closed||serverTemp==null)return;
        try {
            JSONObject geometry=new JSONObject().put("width",width).put("height",height)
                .put("surfaceWidth",surfaceWidth).put("surfaceHeight",surfaceHeight).put("scalePercent",percent);
            Path pending=new File(serverTemp,"render-size.json.pending").toPath();
            Files.write(pending,geometry.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(pending,new File(serverTemp,"render-size.json").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            if(report!=null)report.put("renderSize",geometry);
        }catch(Exception e){fail(e);}
    }
    public void serverCallback() {handler.post(()-> {if(!closed)try {report.put("serverCallbackReceived",true);record("serverCallback");} catch(Exception e) {fail(e);}});}
    public void surfaceReady() {surfaceValid=true;startGraphics();}
    public void surfaceLost() {surfaceValid=false;if(controllerRelay!=null)controllerRelay.focusChanged(false);}
    public void syncReply(int serial) {handler.post(()-> {if(serial==93&&!closed) {syncEchoed=true;sample();startGraphics();}});}
    private void startGraphics() {
        if(closed||graphicsStarted||!surfaceValid||!syncEchoed||!manualSession)return;
        graphicsStarted=true;
        try {report.put("factorioStartupAttempted",true).put("headlessGpuInitOnly",false);record("engineWindowProbeStarted");}catch(Exception e) {fail(e);return;}
        sessionThread=new Thread(()-> {
            try {
                RuntimeProbe.Progress progress=(phase,detail)-> {
                    String snapshot=detail.toString();runOnUiThread(()-> {if(!closed)try {
                        JSONObject progressDetail=new JSONObject(snapshot);
                        if("gamepadRelayReady".equals(phase)&&controllerRelay!=null)controllerRelay.connect(progressDetail.getString("gamepadSocket"));
                        report.put("engineProbeProgress",progressDetail);record("engine:"+phase);
                        if(!automaticStatusHidden&&(phase.equals("engineProcessStarted")||phase.equals("audioStreaming"))) {automaticStatusHidden=true;status.setVisibility(View.GONE);}
                        if(manualSession)status.setText((phase.equals("engineProcessStarted")||phase.equals("audioStreaming"))?getString(R.string.game_running):phase.equals("queryGlCapabilities")?getString(R.string.graphics_preparing):getString(R.string.runtime_preparing));
                    }catch(Exception e) {fail(e);}});
                };
                JSONObject result=gameSession.run(getApplicationContext(),serverTemp,progress);
                runOnUiThread(()-> {if(!closed)try {
                    String log=result.optString("engineLog");
                    boolean enumerated=log.contains("Video driver: x11")&&log.contains("Available displays: 1")&&log.contains(view.renderWidth()+"x"+view.renderHeight());
                    report.put("engineWindowProbe",result).put("linuxX11ClientConnected",enumerated).put("sdlDisplayEnumerationPassed",enumerated)
                        .put("engineProbeComplete",true).put("desktopOpenGLInitialized",log.contains("Initialised OpenGL:"))
                        .put("softwareRendererObserved",log.contains("llvmpipe")).put("engineInitializationLogged",log.contains("Factorio initialised"));
                    record("engineWindowProbeFinished");
                    if(manualSession) {
                        report.put("sessionComplete",true);
                        if(result.has("error"))report.put("error",result.optString("error"));
                        record("sessionEnded");
                        status.setText(result.has("error")?getString(R.string.game_failed_settings):getString(R.string.session_ended_data_kept));
                        JSONObject execution=result.optJSONObject("graphicsInitialization");
                        int code=execution==null?-1:execution.optInt("exitCode",-1);
                        returnToLauncher(result.has("error")?(code>=0?getString(R.string.game_exit_code,code):getString(R.string.game_start_failed)):getString(R.string.game_exited));
                        return;
                    }
                    captureSurface("final");
                    status.setText(getString(R.string.window_probe_saved));
                    handler.postDelayed(this::finish,3000);
                }catch(Exception e) {fail(e);}});
            }catch(Throwable e) {runOnUiThread(()->fail(e));}
        },manualSession?"factorio-game-session":"factorio-x11-probe");sessionThread.start();
    }
    private void captureSurface(String label) {
        if(closed||!surfaceValid||view==null)return;
        final int sourceWidth=view.getWidth(),sourceHeight=view.getHeight();
        if(sourceWidth<2||sourceHeight<2)return;
        final int captureWidth=Math.min(sourceWidth,1704);
        final int captureHeight=Math.max(2,(int)Math.round((double)sourceHeight*captureWidth/sourceWidth));
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(captureWidth,captureHeight,android.graphics.Bitmap.Config.ARGB_8888);
        PixelCopy.request(view,bitmap,code-> {
            try {
                JSONObject capture=new JSONObject().put("label",label).put("pixelCopyResult",code).put("ownSurfaceOnly",true)
                    .put("sourceSurfaceWidth",sourceWidth).put("sourceSurfaceHeight",sourceHeight).put("atEpochMillis",System.currentTimeMillis());
                if(code==PixelCopy.SUCCESS) {
                    ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);byte[] data=out.toByteArray();
                    if(data.length>4*1024*1024)throw new Exception("Diagnostic image exceeds limit");
                    String filename="window-capture-"+label+".png";Files.write(new File(getFilesDir(),filename).toPath(),data);
                    byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(data);StringBuilder sha=new StringBuilder();
                    for(byte b:digest)sha.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                    java.util.Set<Integer> colors=new java.util.HashSet<>();
                    for(int y=0;y<captureHeight;y+=8)for(int x=0;x<captureWidth;x+=8)colors.add(bitmap.getPixel(x,y));
                    capture.put("file",filename).put("bytes",data.length).put("sha256",sha.toString()).put("width",captureWidth).put("height",captureHeight).put("sampledColors",colors.size());
                }
                org.json.JSONArray captures=report.optJSONArray("captures");if(captures==null) {captures=new org.json.JSONArray();report.put("captures",captures);}captures.put(capture);
                record("surfaceCaptured");
            }catch(Exception e) {try {report.put("captureError",e.toString());record("captureFailed");}catch(Exception ignored) {}}
            finally {bitmap.recycle();}
        },handler);
    }
    public void clientConnectedStateChanged() {handler.post(this::sample);}
    private void sample() {
        if(closed||report==null)return;
        try {
            boolean connected=view!=null&&view.isConnected();
            report.put("surfaceValid",surfaceValid).put("windowProtocolConnected",connected).put("syncEchoed",syncEchoed)
                .put("windowTransportPassed",connected&&surfaceValid&&syncEchoed);
            report.put("keyguardLocked",((android.app.KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked())
                .put("interactive",((PowerManager)getSystemService(POWER_SERVICE)).isInteractive()).put("windowFocused",hasWindowFocus());
            if(!manualSession)status.setText(graphicsStarted?getString(R.string.graphics_probe_progress):connected&&syncEchoed?getString(R.string.window_connection_ready):getString(R.string.window_connection_progress));
            record("sampled");
        } catch(Throwable e) {fail(e);}
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(controllerRelay!=null&&controllerRelay.key(event))return true;
        if(view!=null&&view.forwardKeyboardEvent(event))return true;
        return super.dispatchKeyEvent(event);
    }
    boolean hasDirectImeText(){return keyboardBridge!=null&&keyboardBridge.hasDirectText();}
    boolean sendImeEdit(int before,int deletes,String text,int after){return keyboardBridge!=null&&keyboardBridge.edit(before,deletes,text,after);}
    boolean sendImeKey(int code){return keyboardBridge!=null&&keyboardBridge.key(code);}
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if(controllerRelay!=null&&controllerRelay.motion(event))return true;
        return super.dispatchGenericMotionEvent(event);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if(focused)applyImmersiveMode();
        if(controllerRelay!=null)controllerRelay.focusChanged(focused);
    }
    private int dp(int value) {return Math.round(value*getResources().getDisplayMetrics().density);}
    private void applyImmersiveMode() {WindowPolicy.apply(this);}
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);applyImmersiveMode();
        // Keep the existing X11 server and engine. SurfaceHolder delivers the
        // actual buffer size, and LorieView sends its scaled RandR geometry.
        if(view!=null)view.requestLayout();
        if(report!=null)try {record("windowConfigurationChanged");}catch(Exception e){fail(e);}
    }
    @Override public void onMultiWindowModeChanged(boolean mode,Configuration config) {
        super.onMultiWindowModeChanged(mode,config);applyImmersiveMode();
        if(view!=null)view.requestLayout();
    }
    private void updateBackgroundPause() {
        if(gameSession==null)return;
        try {gameSession.setBackgroundPaused(!resumed||!directSurfaceReady);if(report!=null)report.put("lifecycle",gameSession.lifecycleState());}
        catch(Exception error){try{if(report!=null)report.put("backgroundPauseError",error.toString());}catch(Exception ignored){}}
    }
    @Override protected void onResume() {super.onResume();resumed=true;applyImmersiveMode();if(view!=null)view.setImeResumed(true);updateBackgroundPause();}
    @Override protected void onPause() {if(view!=null){view.cancelTouch();view.setImeResumed(false);}resumed=false;updateBackgroundPause();super.onPause();}

    private void sampleController() {
        if(closed||controllerRelay==null||report==null)return;
        try {record("controllerSampled");}catch(Exception e) {fail(e);return;}
        handler.postDelayed(this::sampleController,10000);
    }
    private void record(String phase) throws Exception {
        JSONObject controller=controllerRelay==null?null:controllerRelay.report();
        if(controller!=null)report.put("controller",controller);
        if(keyboardBridge!=null)report.put("keyboardBridge",keyboardBridge.report());
        if(view!=null) {
            JSONObject input=view.inputState();
            if(controller!=null)input.put("nativeGamepadBridgeReady",controller.optBoolean("nativeGamepadRecognized"))
                .put("nativeGamepadEverRecognized",controller.optBoolean("nativeGamepadEverRecognized"));
            report.put("input",input);
            JSONObject presentation=new JSONObject().put("landscapeRequested",!isInMultiWindowMode()).put("multiWindow",isInMultiWindowMode())
                .put("orientation",getResources().getConfiguration().orientation)
                .put("statusOverlayVisible",status.getVisibility()==View.VISIBLE)
                .put("statusOverlayConsumesLayoutSpace",false)
                .put("surfaceWidth",view.getWidth()).put("surfaceHeight",view.getHeight());
            if(Build.VERSION.SDK_INT>=30) {
                WindowInsets insets=getWindow().getDecorView().getRootWindowInsets();
                if(insets!=null)presentation.put("systemBarsVisible",insets.isVisible(WindowInsets.Type.systemBars()));
            }
            report.put("windowPresentation",presentation);
        }
        report.put("phase",phase).put("updatedAtEpochMillis",System.currentTimeMillis());
        String name=manualSession?"game-session-report.json":"window-report.json";
        Path temp=new File(getFilesDir(),name+".tmp").toPath();
        Files.write(temp,report.toString(2).getBytes(StandardCharsets.UTF_8));
        Files.move(temp,new File(getFilesDir(),name).toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    private void fail(Throwable error) {
        try {if(report!=null) {report.put("error",error.toString());record("failed");}} catch(Exception ignored) {}
        if(status!=null)status.setText(manualSession?getString(R.string.game_failed_retry):getString(R.string.window_probe_failed));
        if(manualSession)returnToLauncher(getString(R.string.game_start_failed));
        else handler.postDelayed(this::finish,3000);
    }
    private void returnToLauncher(String reason) {
        if(returningToLauncher||isFinishing())return;returningToLauncher=true;
        try {if(report!=null){report.put("sessionComplete",true).put("activityClosed",true).put("exitMessage",reason);record("returningToLauncher");}}catch(Exception ignored){}
        android.content.Intent launcher=new android.content.Intent(this,org.factorio.local.launcher.MainActivity.class)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("gameExitMessage",reason);
        startActivity(launcher);finish();
    }
    private void askStop() {
        new android.app.AlertDialog.Builder(this).setTitle(getString(R.string.end_session_question))
            .setMessage(getString(R.string.end_session_warning))
            .setPositiveButton(getString(R.string.end_and_return),(d,w)-> {if(gameSession!=null)gameSession.close();returnToLauncher(getString(R.string.session_ended));})
            .setNegativeButton(getString(R.string.continue_game),null).show();
    }
    @Override public void onBackPressed() {
        if(view!=null&&view.hideGameKeyboard())return;
        if(graphicsStarted&&view!=null&&view.forwardEscape()) {
            try {record("androidBackForwardedAsEscape");}catch(Exception e){fail(e);}
            return;
        }
        if(manualSession)askStop();else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if(manualSession&&report!=null)try {report.put("activityClosed",true);record("closing");}catch(Exception ignored) {}
        closed=true;handler.removeCallbacksAndMessages(null);
        if(keyboardBridge!=null)keyboardBridge.close();
        if(controllerRelay!=null)controllerRelay.close();
        if(gameSession!=null)gameSession.close();
        if(view!=null)view.dispose();
        super.onDestroy();
        // This Activity is declared in :window; terminate only its diagnostic process.
        if(manualSession&&sessionThread!=null&&sessionThread.isAlive())new Thread(()-> {
            try {sessionThread.join();}catch(InterruptedException ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());
        },"factorio-session-cleanup").start();
        else android.os.Process.killProcess(android.os.Process.myPid());
    }
}
