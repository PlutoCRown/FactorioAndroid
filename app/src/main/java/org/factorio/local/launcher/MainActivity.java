package org.factorio.local.launcher;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.*;

/** Local game library, launch preferences and device information. */
public final class MainActivity extends Activity {
    private static final int IMPORT=10, IMPORT_SAVE=11, HOME=0, SETTINGS=1, DEVICE=2;
    private static final int BG=0xff242324, PANEL=0xff313031, LINE=0xff201815;
    private static final int TEXT=0xffffe6c0, MUTED=0xffb6b1ad, ACCENT=0xffe39827;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final RailIcon[] tabs=new RailIcon[3];
    private final java.util.ArrayList<View> actions=new java.util.ArrayList<>();
    private LinearLayout shell;
    private FrameLayout content,pageHost,artwork;
    private TextView status;
    private LinearLayout progressRow;
    private ProgressBar progress;
    private JSONObject gameReport,deviceReport;
    private int page=HOME;
    private boolean busy,wide;
    private String message="";
    private int dp(int value) {return Math.round(value*getResources().getDisplayMetrics().density);}
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
        shell=new LinearLayout(this);shell.setOrientation(LinearLayout.HORIZONTAL);shell.setBackgroundColor(BG);
        shell.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout rail=new LinearLayout(this);rail.setOrientation(LinearLayout.VERTICAL);rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(dp(10),dp(24),dp(10),dp(18));rail.setBackgroundColor(0xff201815);
        ScrollView railScroll=new ScrollView(this);railScroll.setFillViewport(true);railScroll.setVerticalScrollBarEnabled(false);
        railScroll.addView(rail);shell.addView(railScroll,new LinearLayout.LayoutParams(dp(76),-1));
        ImageView brand=new ImageView(this);brand.setImageResource(R.drawable.factorio_icon);brand.setContentDescription("Factorio");
        rail.addView(brand,new LinearLayout.LayoutParams(dp(40),dp(40)));
        Space gap=new Space(this);rail.addView(gap,new LinearLayout.LayoutParams(1,dp(40)));
        String[] names={getString(R.string.home),getString(R.string.launch_settings),getString(R.string.device_info)};
        for(int i=0;i<3;i++) {
            final int destination=i;RailIcon icon=new RailIcon(this,i);tabs[i]=icon;
            icon.setContentDescription(names[i]);icon.setTooltipText(names[i]);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(52),dp(52));lp.bottomMargin=dp(12);rail.addView(icon,lp);
            icon.setOnClickListener(v->navigate(destination));
        }
        content=new FrameLayout(this);shell.addView(content,new LinearLayout.LayoutParams(0,-1,1));
        createContent();
        setContentView(shell);
        shell.setOnApplyWindowInsetsListener((v,insets)-> {
            int left=0,top=0,right=0,bottom=0;
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                left=safe.left;top=safe.top;right=safe.right;bottom=safe.bottom;
            } else {
                left=insets.getSystemWindowInsetLeft();top=insets.getSystemWindowInsetTop();
                right=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left,top,right,bottom);return insets;
        });
        content.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)-> {
            boolean next=(r-l)/getResources().getDisplayMetrics().density>=760;
            if(next!=wide){wide=next;artwork.setVisibility(wide?View.VISIBLE:View.GONE);renderPage();}
        });
        renderPage();
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
    private void renderPage() {
        if(content==null)return;
        actions.clear();pageHost.removeAllViews();
        for(int i=0;i<tabs.length;i++) {
            tabs[i].setSelected(i==page);tabs[i].setEnabled(i!=SETTINGS||gameReport!=null);
            tabs[i].setBackground(i==page?metalButton(true):ripple(Color.TRANSPARENT,0,2));
        }
        if(page==HOME)renderHome();else if(page==SETTINGS)renderSettings();else renderDevice();
        applyBusy();
    }
    private void renderHome() {
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        pageHost.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout body=column();body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(wide?40:24),dp(28),dp(wide?32:24),dp(24));scroll.addView(body,new ScrollView.LayoutParams(-1,-1));
        body.addView(label("F A C T O R I O",12,ACCENT,true));
        TextView heading=label(gameReport==null?getString(R.string.home_welcome):getString(R.string.home_continue),wide?36:30,TEXT,true);
        heading.setLineSpacing(dp(5),1);space(body,18);body.addView(heading);
        body.addView(label(gameReport==null?getString(R.string.home_import_hint):getString(R.string.home_ready),14,MUTED,false));space(body,28);
        LinearLayout game=box(body);
        LinearLayout title=new LinearLayout(this);title.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon=new ImageView(this);icon.setImageResource(R.drawable.factorio_icon);
        title.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout info=column();LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(0,-2,1);ip.leftMargin=dp(14);title.addView(info,ip);
        info.addView(label(gameReport==null?getString(R.string.add_game):"Factorio",18,TEXT,true));
        String detail=gameReport==null?getString(R.string.game_package_hint):gameReport.optString("version")+"  ·  "+(gameReport.optBoolean("spaceAge")?"Space Age":getString(R.string.base_game));
        info.addView(label(detail,12,MUTED,false));game.addView(title);space(game,18);
        Switch touchMouse=new Switch(this);touchMouse.setText(getString(R.string.touch_mouse));touchMouse.setTextSize(14);touchMouse.setTextColor(TEXT);
        touchMouse.setSwitchPadding(dp(16));touchMouse.setMinHeight(dp(44));
        touchMouse.setThumbTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ACCENT,MUTED}));
        touchMouse.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xff745026,0xff625c55}));
        touchMouse.setChecked(getPreferences(0).getBoolean("touchMouseSimulation",true));
        touchMouse.setOnCheckedChangeListener((button,enabled)->getPreferences(0).edit().putBoolean("touchMouseSimulation",enabled).apply());
        game.addView(touchMouse,new LinearLayout.LayoutParams(-1,-2));actions.add(touchMouse);
        game.addView(label(getString(R.string.touch_mouse_hint),11,MUTED,false));space(game,16);
        action(game,gameReport==null?getString(R.string.choose_game):getString(R.string.launch_game),true,()->{if(gameReport==null)chooseGame();else startGame();});
        if(gameReport==null) {
            TextView help=link(getString(R.string.download_help_link));game.addView(help);help.setOnClickListener(v->downloadHelp());
            actions.add(help);
        } else {
            action(game,getString(R.string.launch_settings),false,()->navigate(SETTINGS));
            divider(game);
            LinearLayout saves=new LinearLayout(this);saves.setOrientation(LinearLayout.HORIZONTAL);
            game.addView(saves,new LinearLayout.LayoutParams(-1,-2));
            Button open=action(saves,getString(R.string.open_saves),false,()->openGameFolder("saves"));
            Button add=action(saves,getString(R.string.import_save),false,this::chooseSave);
            LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(54),1);left.topMargin=dp(8);left.rightMargin=dp(8);open.setLayoutParams(left);
            LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,dp(54),1);right.topMargin=dp(8);add.setLayoutParams(right);
            action(game,getString(R.string.open_mods),false,()->openGameFolder("mods"));
            TextView replace=link(getString(R.string.replace_game));game.addView(replace);replace.setOnClickListener(v->chooseGame());actions.add(replace);
        }
        space(body,18);statusRow(body);
        space(body,12);body.addView(label(getString(R.string.own_game_footer),11,MUTED,false));
    }
    /** The artwork lives outside pageHost, so tab changes never replace it. */
    private void createContent() {
        LinearLayout split=new LinearLayout(this);split.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(split,new FrameLayout.LayoutParams(-1,-1));
        pageHost=new FrameLayout(this);split.addView(pageHost,new LinearLayout.LayoutParams(0,-1,1));
        artwork=new FrameLayout(this);split.addView(artwork,new LinearLayout.LayoutParams(0,-1,1));
        artwork.setVisibility(View.GONE);
        ImageView image=new ImageView(this);image.setImageResource(R.drawable.factory_background);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);artwork.addView(image,new FrameLayout.LayoutParams(-1,-1));
        View shade=new View(this);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{BG,0x00242324}));
        artwork.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout caption=column();caption.setPadding(dp(36),dp(36),dp(36),dp(40));
        caption.addView(label("THE FACTORY MUST GROW",11,TEXT,true));
        caption.addView(label(getString(R.string.artwork_caption),24,TEXT,true));
        artwork.addView(caption,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
    }
    private LinearLayout pageBody(String eyebrow,String title,String description) {
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);pageHost.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout body=new LinearLayout(this) {
            @Override protected void onMeasure(int widthSpec,int heightSpec) {
                int width=Math.min(MeasureSpec.getSize(widthSpec),dp(860));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.getMode(widthSpec)),heightSpec);
            }
        };
        body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(wide?40:24),dp(32),dp(wide?40:24),dp(24));
        FrameLayout frame=new FrameLayout(this);scroll.addView(frame);
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        frame.addView(body,bp);body.addView(label(eyebrow,11,ACCENT,true));space(body,8);body.addView(label(title,30,TEXT,true));
        body.addView(label(description,14,MUTED,false));space(body,24);return body;
    }
    private void renderSettings() {
        LinearLayout body=pageBody("PREFERENCES",getString(R.string.launch_settings),getString(R.string.settings_hint));
        LinearLayout form=box(body);
        field(form,getString(R.string.render_resolution),getString(R.string.render_resolution_hint));
        int scale=getPreferences(0).getInt("renderScalePercent",100);
        choice(form,new String[]{getString(R.string.scale_native),getString(R.string.scale_balanced),getString(R.string.scale_smooth)},scale==50?2:scale==75?1:0,
            i->getPreferences(0).edit().putInt("renderScalePercent",new int[]{100,75,50}[i]).apply());
        divider(form);
        field(form,getString(R.string.graphics_preset),getString(R.string.graphics_preset_hint));
        String preset=getPreferences(0).getString("graphicsPreset","compat");
        choice(form,new String[]{getString(R.string.preset_compat),getString(R.string.preset_balanced),getString(R.string.preset_quality),getString(R.string.preset_game)},preset.equals("balanced")?1:preset.equals("quality")?2:preset.equals("game")?3:0,
            i->getPreferences(0).edit().putString("graphicsPreset",new String[]{"compat","balanced","quality","game"}[i]).apply());
        divider(form);
        field(form,getString(R.string.graphics_renderer),"Zink · Android Surface");
        form.addView(label(getString(R.string.renderer_hint),12,MUTED,false));
        space(body,20);action(body,getString(R.string.back_home),true,()->navigate(HOME));
        statusRow(body);
    }
    private interface Selection {void select(int index);}
    private void choice(LinearLayout parent,String[] values,int selected,Selection onSelected) {
        final CharSequence fieldName=((TextView)parent.getChildAt(parent.getChildCount()-2)).getText();
        Spinner spinner=new Spinner(this,Spinner.MODE_DROPDOWN);spinner.setContentDescription(fieldName+"："+values[selected]);
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,values) {
            @Override public View getView(int p,View old,android.view.ViewGroup group) {return item(p,false);}
            @Override public View getDropDownView(int p,View old,android.view.ViewGroup group) {return item(p,true);}
            private View item(int p,boolean dropdown) {
                TextView text=label(getItem(p),15,TEXT,false);text.setPadding(dp(16),dp(16),dp(16),dp(16));
                text.setMinHeight(dp(52));text.setGravity(Gravity.CENTER_VERTICAL);
                if(dropdown){text.setBackgroundColor(PANEL);return text;}
                text.setSingleLine(true);text.setEllipsize(android.text.TextUtils.TruncateAt.END);text.setClickable(false);text.setLongClickable(false);text.setFocusable(false);
                LinearLayout row=new LinearLayout(MainActivity.this);row.setGravity(Gravity.CENTER_VERTICAL);
                // Forward the whole selected row. Text/tooltip children must
                // never consume taps intended to open the Spinner.
                row.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                row.setOnClickListener(v->spinner.performClick());
                row.addView(text,new LinearLayout.LayoutParams(0,-1,1));
                ImageView arrow=new ImageView(MainActivity.this);arrow.setImageResource(R.drawable.ic_expand_more);arrow.setColorFilter(MUTED);arrow.setScaleType(ImageView.ScaleType.CENTER_INSIDE);arrow.setPadding(dp(10),dp(10),dp(10),dp(10));arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);arrow.setClickable(false);arrow.setFocusable(false);
                row.addView(arrow,new LinearLayout.LayoutParams(dp(48),-1));return row;
            }
        };
        spinner.setAdapter(adapter);spinner.setSelection(selected,false);spinner.setPadding(dp(2),dp(2),dp(2),dp(2));spinner.setBackground(new FactorioSurface(BG,0xff151211,0xff646261,dp(2)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));lp.topMargin=dp(10);parent.addView(spinner,lp);actions.add(spinner);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> p) {}
            public void onItemSelected(AdapterView<?> p,View view,int position,long id) {spinner.setContentDescription(fieldName+"："+values[position]);onSelected.select(position);}
        });
    }
    private void renderDevice() {
        LinearLayout body=pageBody("THIS DEVICE",getString(R.string.device_info),getString(R.string.device_hint));
        LinearLayout details=box(body);
        String model=android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL;
        datum(details,getString(R.string.device_label),model);datum(details,getString(R.string.system_label),"Android "+Build.VERSION.RELEASE);
        android.app.ActivityManager.MemoryInfo memory=new android.app.ActivityManager.MemoryInfo();
        ((android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
        datum(details,getString(R.string.memory_label),String.format(Locale.ROOT,"%.1f GB",memory.totalMem/1000000000.0));
        JSONObject gl=deviceReport==null?null:deviceReport.optJSONObject("graphics");
        datum(details,getString(R.string.gpu_label),gl==null?getString(R.string.waiting_for_check):gl.optString("glRenderer",getString(R.string.unavailable)));
        String api=gl==null?getString(R.string.waiting_for_check):getString(R.string.unavailable);
        if(gl!=null) {
            java.util.regex.Matcher version=java.util.regex.Pattern.compile("OpenGL ES\\s+\\d+\\.\\d+").matcher(gl.optString("glVersion",""));
            if(version.find())api=version.group();
        }
        datum(details,getString(R.string.graphics_api),api);
        space(body,16);action(body,deviceReport==null?getString(R.string.check_device):getString(R.string.recheck_device),true,this::probe);
        statusRow(body);
        TextView result=label(gl==null?getString(R.string.device_check_local):gl.optBoolean("clearReadbackPassed")?
            getString(R.string.device_check_pass):getString(R.string.device_check_fail),12,MUTED,false);body.addView(result);
    }
    private void datum(LinearLayout parent,String title,String value) {
        parent.addView(label(title,12,MUTED,false));TextView v=label(value,16,TEXT,false);v.setTextIsSelectable(true);parent.addView(v);space(parent,14);
    }
    private void field(LinearLayout p,String title,String detail) {p.addView(label(title,17,TEXT,true));p.addView(label(detail,12,MUTED,false));}
    private void divider(LinearLayout p) {space(p,22);View line=new View(this);line.setBackgroundColor(LINE);p.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));space(p,22);}
    private LinearLayout column() {LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout box(LinearLayout parent) {
        LinearLayout box=column();box.setPadding(dp(22),dp(20),dp(22),dp(20));box.setBackground(new FactorioSurface(PANEL,0xff8f8c8b,0xff0f0d0c,dp(3)));
        parent.addView(box,new LinearLayout.LayoutParams(-1,-2));return box;
    }
    private TextView label(String text,int size,int color,boolean bold) {
        TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(color);view.setPadding(0,dp(4),0,dp(4));
        view.setFontFeatureSettings("kern");if(bold)view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return view;
    }
    private TextView link(String title) {TextView v=label(title,13,0xff7dcaed,false);v.setGravity(Gravity.CENTER);v.setMinHeight(dp(48));v.setFocusable(true);v.setBackground(ripple(Color.TRANSPARENT,0,10));return v;}
    private Button action(LinearLayout parent,String text,boolean primary,Runnable task) {
        Button button=new Button(this);button.setText(text);button.setAllCaps(false);button.setTextSize(15);button.setTextColor(0xff1d1c1d);
        button.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));button.setBackground(metalButton(primary));
        button.setStateListAnimator(null);button.setMinHeight(0);button.setMinimumHeight(0);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(54));lp.topMargin=dp(8);parent.addView(button,lp);
        button.setOnClickListener(v->task.run());actions.add(button);return button;
    }
    private Drawable metalButton(boolean primary) {
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},new FactorioSurface(0xfff1be64,0xff563a10,0xffd0ae79,dp(2)));
        states.addState(new int[]{android.R.attr.state_focused},new FactorioSurface(ACCENT,0xfff9b44b,0xff432400,dp(2)));
        states.addState(new int[]{android.R.attr.state_hovered},new FactorioSurface(ACCENT,0xfff9b44b,0xff432400,dp(2)));
        states.addState(new int[]{},new FactorioSurface(primary?ACCENT:0xff8e8e8e,primary?0xfff9b44b:0xffe3e3e3,0xff151211,dp(2)));
        return states;
    }
    private GradientDrawable shape(int color,int border,int radius) {GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(border!=0)d.setStroke(dp(1),border);return d;}
    private RippleDrawable ripple(int color,int border,int radius) {return new RippleDrawable(android.content.res.ColorStateList.valueOf(0x30ffffff),shape(color,border,radius),shape(Color.WHITE,0,radius));}
    private void space(LinearLayout p,int height) {p.addView(new Space(this),new LinearLayout.LayoutParams(1,dp(height)));}
    private void statusRow(LinearLayout parent) {
        progressRow=new LinearLayout(this);progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progress=new ProgressBar(this);progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(dp(20),dp(20));pp.rightMargin=dp(12);progressRow.addView(progress,pp);
        status=label(message,13,ACCENT,false);progressRow.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        parent.addView(progressRow,new LinearLayout.LayoutParams(-1,-2));
    }
    private void applyBusy() {
        if(progressRow!=null){progressRow.setVisibility(busy||!message.isEmpty()?View.VISIBLE:View.GONE);progress.setVisibility(busy?View.VISIBLE:View.GONE);}
        for(View v:actions){v.setEnabled(!busy);v.setAlpha(busy?0.45f:1f);}
        if(status!=null){status.setText(message);status.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);}
    }
    private void setBusy(boolean value,String text) {
        busy=value;message=text;applyBusy();
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
                    new AlertDialog.Builder(this).setTitle(getString(R.string.save_import_failed)).setMessage(getString(e instanceof GameFiles.BusyException?R.string.edit_game_busy:R.string.save_import_retry))
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
                if(!isDestroyed()){message=importProgress(progress);applyBusy();}
            }));
            getContentResolver().notifyChange(android.provider.DocumentsContract.buildRootsUri(GameDocumentsProvider.AUTHORITY),null);
            runOnUiThread(()-> {if(isDestroyed())return;gameReport=report;page=HOME;setBusy(false,getString(R.string.game_added));renderPage();});
        }catch(Exception e){fail(getString(R.string.game_import_failed),e);}});
    }
    private String importProgress(String progress) {
        // File names, package hashes and extraction paths stay in internal reports.
        if(progress.contains("校验"))return getString(R.string.game_verifying);
        if(progress.contains("解包")||progress.contains("解压"))return getString(R.string.game_extracting);
        return getString(R.string.game_import_wait);
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
    @Override public void onBackPressed() {
        if(page!=HOME){navigate(HOME);return;}
        if(busy){new AlertDialog.Builder(this).setMessage(getString(R.string.work_in_progress)).setPositiveButton(getString(R.string.keep_waiting),null).show();return;}super.onBackPressed();
    }
    @Override protected void onDestroy() {worker.shutdownNow();super.onDestroy();}
}
