package ru.local.voyah.musicsearch;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;

import android.widget.*;



public class VoiceActivity extends Activity {
    private static final int BG=0xff101619, CARD=0xff1c2529, SUB=0xffb3c3bf, MINT=0xff86f1b9, INK=0xff13281f;
    private TextView message;
    private Button toggle;
    private ModelStore baseModels,futoModels;
    private RadioGroup engineOptions;
    private Button downloadAlternate;
    private TextView alternateProgress;
    private Button enrollment;
    private VoiceSettings settings;
    private RadioGroup delayOptions;
    private Button access;
    private Intent selectorSettings;
    private final android.os.Handler ui=new android.os.Handler(android.os.Looper.getMainLooper());
    private int dp(int value) {return Math.round(value*getResources().getDisplayMetrics().density);}
    private android.graphics.drawable.GradientDrawable surface(int color,int radius) {
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg;
    }
    private TextView label(String text,int size,int color,boolean bold) {
        TextView v=new TextView(this);
        v.setText(text); v.setTextColor(color); v.setTextSize(size);
        if(bold) v.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
        return v;
    }
    private LinearLayout column() {
        LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v;
    }
    private void add(LinearLayout parent,android.view.View child,int top) {
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.topMargin=dp(top); parent.addView(child,lp);
    }
    private Button action(String text) {
        Button b=new Button(this); b.setAllCaps(false); b.setText(text); b.setTextSize(18);
        b.setBackground(surface(MINT,16)); b.setTextColor(INK); b.setMinHeight(dp(52)); return b;
    }
    private final Runnable refresh=new Runnable() {
        @Override public void run() {
            ModelStore models=selectedModels(), alternate=alternateModels();
            boolean active=WakeService.active;
            String s=WakeService.status;
            if(!active && (s==null || s.startsWith("Жду") || s.equals("Прослушивание выключено"))) s="Прослушивание выключено";
            if(!models.ready) {
                if(models.busy) s=downloadProgress(models);
                else s=models.message.isEmpty()?"Скачайте модели один раз для работы без облака":models.message;
            }
            enrollment.setEnabled(models.ready);
            toggle.setKeepScreenOn(models.busy);
            if(!android.text.TextUtils.equals(message.getText(),s)) message.setText(s);
            String button=active?"Выключить прослушивание":"Включить прослушивание";
            if(!active && !models.ready) button=downloadLabel(models);
            if(!android.text.TextUtils.equals(toggle.getText(),button)) toggle.setText(button);
            toggle.setEnabled(active || models.ready || !alternate.busy);
            downloadAlternate.setVisibility(alternate.ready?android.view.View.GONE:android.view.View.VISIBLE);
            downloadAlternate.setEnabled(!models.busy);
            button=downloadLabel(alternate);
            if(!android.text.TextUtils.equals(downloadAlternate.getText(),button)) downloadAlternate.setText(button);
            downloadAlternate.setKeepScreenOn(alternate.busy);
            String progress=alternate.busy?downloadProgress(alternate):alternate.message;
            alternateProgress.setVisibility(!alternate.ready && !progress.isEmpty()?android.view.View.VISIBLE:android.view.View.GONE);
            if(!android.text.TextUtils.equals(alternateProgress.getText(),progress)) alternateProgress.setText(progress);
            for(int i=0;i<engineOptions.getChildCount();i++) {
                android.view.View choice=engineOptions.getChildAt(i);
                int engine=(Integer)choice.getTag();
                choice.setEnabled(engine==settings.engine() || (engine==VoiceSettings.FUTO?futoModels:baseModels).ready);
            }
            refreshAccessButton();
            ui.postDelayed(this,600);
        }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        baseModels=ModelStore.get(this,VoiceSettings.BASE);
        futoModels=ModelStore.get(this,VoiceSettings.FUTO);
        settings=new VoiceSettings(this);
        render();

    }
    private ModelStore selectedModels() {return settings.engine()==VoiceSettings.FUTO?futoModels:baseModels;}
    private ModelStore alternateModels() {return settings.engine()==VoiceSettings.FUTO?baseModels:futoModels;}
    private String downloadLabel(ModelStore models) {
        if(models.busy) return "Приостановить загрузку";
        String name=models==futoModels?"FUTO‑74":"Base";
        return (models.message.isEmpty()?"Скачать ":"Продолжить загрузку ")+name;
    }
    private static String downloadProgress(ModelStore models) {
        return models.message+" · "+(models.bytes/1048576)+" / "+((models.total+1048575)/1048576)+" МиБ";
    }
    private static void toggleDownload(ModelStore models) {
        if(models.busy) models.pause(); else models.start();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config); render();
    }
    private void render() {
        boolean wide=getResources().getConfiguration().screenWidthDp>=900;
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout page=column(); int x=dp(wide?48:20), y=dp(wide?18:16);
        page.setPadding(x,y,x,y); scroll.addView(page);
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark=new ImageView(this); mark.setImageResource(R.drawable.voice_icon);
        header.addView(mark,new LinearLayout.LayoutParams(dp(50),dp(50)));
        TextView brand=label(getString(R.string.app_name),32,Color.WHITE,true);
        brand.setPadding(dp(16),0,0,0); header.addView(brand); add(page,header,0);
        LinearLayout panels=new LinearLayout(this);
        panels.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL); add(page,panels,16);
        LinearLayout listen=column(); listen.setBackground(surface(0xff20352c,22)); listen.setPadding(dp(24),dp(24),dp(24),dp(24));
        LinearLayout controls=column(); controls.setBackground(surface(CARD,22)); controls.setPadding(dp(26),dp(20),dp(26),dp(22));
        LinearLayout.LayoutParams left=wide?new LinearLayout.LayoutParams(0,-2,1):new LinearLayout.LayoutParams(-1,-2);
        LinearLayout.LayoutParams right=wide?new LinearLayout.LayoutParams(0,-2,1.65f):new LinearLayout.LayoutParams(-1,-2);
        if(wide) right.leftMargin=dp(20); else right.topMargin=dp(16);
        panels.addView(listen,left); panels.addView(controls,right);
        add(listen,label("Фраза активации",18,SUB,false),0);
        Button phraseButton=action(settings.phrase());
        phraseButton.setContentDescription("Изменить фразу активации: "+settings.phrase());
        phraseButton.setOnClickListener(v->editWakePhrase());
        add(listen,phraseButton,6);
        message=label(WakeService.status,20,MINT,false); add(listen,message,8);
        add(listen,label("После сигнала назовите трек и исполнителя.",17,SUB,false),16);
        toggle=action(WakeService.active?"Выключить прослушивание":"Включить прослушивание");
        toggle.setOnClickListener(v->{
            if(WakeService.active) WakeService.disable(this);
            else if(!selectedModels().ready) toggleDownload(selectedModels());
            else enable();
        });
        add(listen,toggle,28);
        Button enroll=action(WakeService.profileFile(this,settings.phrase()).isFile()?"Перезаписать мой голос":"Настроить по моему голосу");
        enrollment=enroll;enroll.setEnabled(selectedModels().ready);
        enroll.setOnClickListener(v->new android.app.AlertDialog.Builder(this)
                .setTitle("Обучить фразу «"+settings.phrase()+"»")
                .setMessage("После каждого из трёх сигналов скажите фразу один раз. В приложении сохранятся акустические признаки образцов.")
                .setNegativeButton("Отмена",null)
                .setPositiveButton("Начать", (dialog,which)->{
                    WakeService.enrollmentRequested=true;
                    if(!WakeService.active) enable();
                }).show());
        add(listen,enroll,8);
        access=action("Настроить автовыбор Spotify");
        access.setOnClickListener(v->showSelectorAccess());
        refreshAccessButton();
        add(listen,access,10);
        add(controls,label("Настройки поиска",26,Color.WHITE,true),0);
        engineOptions=options(controls,"Движок распознавания",VoiceSettings.ENGINE,VoiceSettings.BASE,
                new String[]{"Whisper Base","FUTO‑74"},new int[]{VoiceSettings.BASE,VoiceSettings.FUTO});
        downloadAlternate=action(downloadLabel(alternateModels()));
        downloadAlternate.setOnClickListener(v->toggleDownload(alternateModels()));
        add(controls,downloadAlternate,8);
        alternateProgress=label("",16,SUB,false);
        add(controls,alternateProgress,5);
        options(controls,"Язык распознавания",VoiceSettings.LANGUAGE,0,
                new String[]{"Русский и английский","Русский","Английский"},new int[]{0,1,2});
        delayOptions=options(controls,"Пауза перед сменой языка",VoiceSettings.DELAY,VoiceSettings.DEFAULT_DELAY,
                new String[]{"2,5 с","5 с","8 с"},new int[]{2500,5000,8000});
        updateDelay();
        options(controls,"Громкость сигнала",VoiceSettings.VOLUME,VoiceSettings.DEFAULT_VOLUME,
                new String[]{"Тихо","Обычно","Громко"},new int[]{35,65,90});
        setContentView(scroll);
    }
    private void editWakePhrase() {
        EditText field=new EditText(this);
        field.setSingleLine(true);field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(64)});
        field.setText(settings.phrase());field.selectAll();
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this)
                .setTitle("Слово или фраза активации").setView(field)
                .setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            String value=field.getText().toString().trim();
            try {
                WakePhrase.pieces(this,value);
                WakeService.enrollmentRequested=false;
                settings.setPhrase(value);dialog.dismiss();render();
            } catch(Exception e) {field.setError(e.getMessage());}
        }));
        dialog.show();
    }
    private void updateDelay() {
        if(delayOptions==null) return;
        boolean enabled=settings.language().isEmpty();
        for(int i=0;i<delayOptions.getChildCount();i++) delayOptions.getChildAt(i).setEnabled(enabled);
        delayOptions.setAlpha(enabled?1f:0.45f);
    }
    private RadioGroup options(LinearLayout parent,String title,String key,int fallback,String[] labels,int[] values) {
        add(parent,label(title,18,SUB,true),12);
        RadioGroup group=new RadioGroup(this); group.setOrientation(RadioGroup.HORIZONTAL);
        group.setContentDescription(title);
        boolean compact=getResources().getConfiguration().screenWidthDp<540;
        if(compact && key.equals(VoiceSettings.LANGUAGE)) group.setOrientation(RadioGroup.VERTICAL);
        int chosen=settings.get(key,fallback);
        for(int i=0;i<values.length;i++) {
            RadioButton choice=new RadioButton(this); choice.setId(android.view.View.generateViewId());
            choice.setTag(values[i]); choice.setText(labels[i]); choice.setTextSize(16);
            if(key.equals(VoiceSettings.ENGINE) && values[i]!=chosen) {
                choice.setEnabled((values[i]==VoiceSettings.FUTO?futoModels:baseModels).ready);
            }
            choice.setButtonDrawable((android.graphics.drawable.Drawable)null); choice.setGravity(Gravity.CENTER);
            choice.setPadding(dp(4),0,dp(4),0);
            int[] checked={android.R.attr.state_checked};
            android.graphics.drawable.StateListDrawable colors=new android.graphics.drawable.StateListDrawable();
            colors.addState(checked,surface(MINT,12)); colors.addState(new int[0],surface(0xff303c40,12));
            choice.setBackground(colors);
            choice.setTextColor(new android.content.res.ColorStateList(new int[][]{checked,new int[0]},new int[]{INK,Color.WHITE}));
            boolean row=group.getOrientation()==RadioGroup.HORIZONTAL;
            float weight=key.equals(VoiceSettings.LANGUAGE) && i==0?1.7f:1f;
            RadioGroup.LayoutParams lp=row?new RadioGroup.LayoutParams(0,dp(48),weight):new RadioGroup.LayoutParams(-1,dp(48));
            if(i>0) {if(row) lp.leftMargin=dp(6);else lp.topMargin=dp(4);}
            group.addView(choice,lp);
            if(values[i]==chosen) group.check(choice.getId());
        }
        group.setOnCheckedChangeListener((g,id)->{
            RadioButton checked=g.findViewById(id);
            if(checked!=null && key.equals(VoiceSettings.ENGINE)) {
                int engine=(Integer)checked.getTag();
                if(!(engine==VoiceSettings.FUTO?futoModels:baseModels).ready) return;
            }
            if(checked!=null) settings.set(key,(Integer)checked.getTag());
            if(key.equals(VoiceSettings.LANGUAGE)) updateDelay();
        });
        add(parent,group,7); return group;
    }

    private void refreshAccessButton() {
        boolean connected=SearchService.instance!=null;
        access.setVisibility(connected?android.view.View.GONE:android.view.View.VISIBLE);
        if(connected) return;
        String text=SelectorAccess.configured(this)?"Состояние автовыбора Spotify":
                selectorSettings==null?"Как включить автовыбор Spotify":"Настроить автовыбор Spotify";
        if(!android.text.TextUtils.equals(access.getText(),text)) access.setText(text);
    }
    private boolean openSelectorSettings() {
        Intent open=selectorSettings;
        if(open==null) return false;
        try {
            startActivity(open);
            return true;
        } catch(ActivityNotFoundException|SecurityException unavailable) {
            selectorSettings=null;
            refreshAccessButton();
            return false;
        }
    }
    private void showSelectorAccess() {
        if(SearchService.instance!=null) return;
        boolean configured=SelectorAccess.configured(this);
        if(!configured && selectorSettings!=null && openSelectorSettings()) return;

        String text=configured
                ?"Служба «Музыка голосом» включена, но Android её ещё не подключил."
                :"Для выбора и запуска трека нужна служба специальных возможностей «Музыка голосом».";
        if(selectorSettings==null) {
            text+="\n\nВ этой прошивке нет доступного экрана специальных возможностей. "
                +"Для настройки нужен системный инструмент с правом включать такие службы.";
        } else {
            text+="\n\nОткройте специальные возможности и выключите, затем снова включите «Музыка голосом».";
        }
        String afterUpdate=SelectorAccess.updateStatus(this);
        if(!afterUpdate.isEmpty()) text+="\n\nСразу после последнего обновления автовыбор был "+afterUpdate+".";
        android.app.AlertDialog.Builder dialog=new android.app.AlertDialog.Builder(this)
                .setTitle("Автовыбор Spotify").setMessage(text).setNegativeButton("Закрыть",null);
        if(selectorSettings!=null) dialog.setPositiveButton("Открыть настройки",(d,which)->{
            if(!openSelectorSettings()) showSelectorAccess();
        });
        dialog.show();
    }
    @Override protected void onResume() {
        super.onResume();
        selectorSettings=SelectorAccess.findSettings(this);
        refresh.run();
    }
    @Override protected void onPause() {ui.removeCallbacks(refresh); super.onPause();}
    @Override protected void onStop() {WakeService.enrollmentRequested=false; super.onStop();}
    private void enable() {
        if(!selectedModels().ready) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        settings.setListeningEnabled(true);
        startForegroundService(new Intent(this,WakeService.class));
    }
    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) enable();
        else WakeService.enrollmentRequested=false;
    }
    static void launchSearch(Context ctx,String text) {
        launchSearch(ctx,text,null);
    }
    static void launchSearch(Context ctx,String text,java.util.function.Consumer<Boolean> completed) {
        launchSearch(ctx,SpokenRequest.parse(text),completed);
    }
    static void launchSearch(Context ctx,SpokenRequest request,java.util.function.Consumer<Boolean> completed) {
        if(request.needsName()) throw new IllegalArgumentException("Запрос требует уточнения");
        if(SearchService.instance!=null) {
            SearchService.instance.search(request,completed);
            return;
        }
        if(completed!=null) throw new IllegalStateException("Служба выбора Spotify отключена");
        Toast.makeText(ctx,"Автовыбор выключен. Выберите трек на экране.",Toast.LENGTH_LONG).show();
        openQuery(ctx,request.query);
    }
    static void openQuery(Context ctx,String q) {
        Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse("spotify:search:"+Uri.encode(q)))
                .setPackage(SearchService.SPOTIFY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }
}
