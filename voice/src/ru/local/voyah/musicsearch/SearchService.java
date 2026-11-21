package ru.local.voyah.musicsearch;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import android.animation.ObjectAnimator;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.CompletableFuture;

// Only inspect the Spotify window during a pending user-initiated search.
public class SearchService extends AccessibilityService {
    static final String SPOTIFY = "com.spotify.music";
    static SearchService instance;
    final Handler handler = new Handler(Looper.getMainLooper());
    String query, kind, selectedTitle;
    long deadline;
    private java.util.function.Consumer<Boolean> finished;
    private TitleCorrection correction;
    private String originalQuery="";
    private boolean broadened;
    private long resultsSince;
    private CompletableFuture<Boolean> manualPick;
    private long watchStarted;
    private AccessibilityNodeInfo ownClick;
    private long ownClickTime;
    void watchUser(CompletableFuture<Boolean> command) {
        if(manualPick==command) return;
        manualPick=command;
        watchStarted=SystemClock.uptimeMillis();
    }
    void stopWatching(CompletableFuture<Boolean> command) {
        if(manualPick==command) {
            manualPick=null;
            forgetOwnClick();
            cancel();
        }
    }
    private void forgetOwnClick() {
        if(ownClick!=null) {ownClick.recycle(); ownClick=null;}
    }
    private boolean clickResult(AccessibilityNodeInfo node) {
        forgetOwnClick();
        ownClickTime=SystemClock.uptimeMillis();
        ownClick=AccessibilityNodeInfo.obtain(node);
        boolean clicked=node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if(!clicked) forgetOwnClick();
        return clicked;
    }
    private java.util.List<String> artistHistory() {
        String list=getSharedPreferences("catalog",MODE_PRIVATE).getString("artists","");
        return list.isEmpty()?new java.util.ArrayList<>():new java.util.ArrayList<>(java.util.Arrays.asList(list.split("\n")));
    }
    private void rememberArtist(String artist) {
        artist=artist.trim();
        if(artist.isEmpty() || artist.length()>90 || artist.contains("\n")) return;
        java.util.List<String> history=artistHistory();
        history.remove(artist); history.add(0,artist);
        if(history.size()>32) history=history.subList(0,32);
        getSharedPreferences("catalog",MODE_PRIVATE).edit().putString("artists",android.text.TextUtils.join("\n",history)).apply();
    }
    private LinearLayout banner;
    private ObjectAnimator pulse;
    private final Runnable removeBanner=()->{
        if(pulse!=null) {pulse.cancel(); pulse=null;}
        if(banner!=null) {
            getSystemService(WindowManager.class).removeView(banner);
            banner=null;
        }
    };
    void prompt(String label,boolean listening,long duration) {
        handler.removeCallbacks(removeBanner);
        removeBanner.run();
        float density=getResources().getDisplayMetrics().density;
        int pad=Math.round(16*density);
        banner=new LinearLayout(this);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(pad,pad/2,pad,pad/2);
        GradientDrawable background=new GradientDrawable();
        background.setColor(Color.rgb(20,35,37));
        background.setCornerRadius(pad*2);
        banner.setBackground(background);
        ImageView mic=new ImageView(this);
        mic.setImageResource(android.R.drawable.ic_btn_speak_now);
        mic.setColorFilter(Color.rgb(93,243,161));
        banner.addView(mic,new LinearLayout.LayoutParams(pad*2,pad*2));
        TextView text=new TextView(this);
        text.setText(label); text.setTextColor(Color.WHITE); text.setTextSize(22); text.setPadding(pad,0,0,0);
        banner.addView(text);
        WindowManager.LayoutParams layout=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        layout.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL; layout.y=pad*2;
        try {getSystemService(WindowManager.class).addView(banner,layout);}
        catch(RuntimeException e) {banner=null; return;}
        if(listening) {
            pulse=ObjectAnimator.ofFloat(mic,View.ALPHA,1f,0.4f);
            pulse.setDuration(560); pulse.setRepeatMode(ObjectAnimator.REVERSE);
            pulse.setRepeatCount(ObjectAnimator.INFINITE); pulse.start();
        }
        handler.postDelayed(removeBanner,duration);
    }
    static String norm(String value) {
        return TitleCorrection.norm(value);
    }
    static String value(AccessibilityNodeInfo node) {
        return node == null || node.getText() == null ? "" : node.getText().toString();
    }
    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onDestroy() { onInterrupt(); handler.removeCallbacks(removeBanner); removeBanner.run(); instance = null; super.onDestroy(); }
    @Override public void onInterrupt() {
        if(manualPick!=null) manualPick.complete(true);
        manualPick=null;
        forgetOwnClick();
        cancel();
    }
    void cancel() { end(false); }
    void end(boolean picked) {
        query=null; selectedTitle=null; correction=null; handler.removeCallbacks(check);
        java.util.function.Consumer<Boolean> cb=finished;
        finished=null;
        if(cb!=null) cb.accept(picked);
    }
    void arm(String text, String type,java.util.function.Consumer<Boolean> callback) {
        cancel();
        finished=callback;
        query = norm(text);
        originalQuery=query; broadened=false; resultsSince=0;
        prompt("Ищу: "+text,false,3000);
        kind = type;
        deadline = SystemClock.elapsedRealtime()+8000;
        handler.postDelayed(check, 500);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(event.getEventType()==AccessibilityEvent.TYPE_VIEW_CLICKED && SPOTIFY.contentEquals(String.valueOf(event.getPackageName()))
                && (manualPick!=null || query!=null) && event.getEventTime()>=watchStarted) {
            AccessibilityNodeInfo source=event.getSource();
            boolean automated=ownClick!=null && ownClick.equals(source) && event.getEventTime()>=ownClickTime
                    && event.getEventTime()-ownClickTime<1000;
            if(source!=null) source.recycle();
            if(automated) {forgetOwnClick(); return;}
            if(manualPick!=null) manualPick.complete(true);
            manualPick=null;
            forgetOwnClick();
            WakeService.event(this,"manual-pick: Spotify");
            handler.removeCallbacks(removeBanner);
            removeBanner.run();
            end(true);
            return;
        }
        if (query != null && !handler.hasCallbacks(check)) handler.postDelayed(check, 200);
    }
    private static String textById(AccessibilityNodeInfo row, String id) {
        for (AccessibilityNodeInfo n : row.findAccessibilityNodeInfosByViewId(SPOTIFY+":id/"+id)) return value(n);
        return "";
    }
    private AccessibilityNodeInfo playerButton() {
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null || !SPOTIFY.equals(String.valueOf(root.getPackageName()))) return null;
        for(AccessibilityNodeInfo b:root.findAccessibilityNodeInfosByViewId(SPOTIFY+":id/play_pause_button"))
            if(b.isVisibleToUser() && b.isClickable()) return b;
        return null;
    }
    String currentTrack() {
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null || !SPOTIFY.equals(String.valueOf(root.getPackageName()))) return "";
        String title=textById(root,"track_info_view_title");
        if(title.isEmpty()) title=textById(root,"track_info_view");
        return title.isEmpty()?"":title+" / "+textById(root,"track_info_view_subtitle");
    }
    boolean paused() {
        AccessibilityNodeInfo b=playerButton();
        return b!=null && norm(String.valueOf(b.getContentDescription())).matches("слушать|воспроизвести|play|play playback");
    }
    String pausePlayingTrack() {
        AccessibilityNodeInfo b=playerButton();
        String id=currentTrack();
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root!=null && SPOTIFY.equals(String.valueOf(root.getPackageName()))) rememberArtist(textById(root,"track_info_view_subtitle"));
        if(b==null || id.isEmpty()) return "";
        String label=norm(String.valueOf(b.getContentDescription()));
        if((label.startsWith("пауза") || label.startsWith("приостановить") || label.startsWith("pause"))
                && b.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return id;
        return "";
    }
    void resumeIfSame(String id) {
        if(id.isEmpty() || !id.equals(currentTrack()) || !paused()) return;
        AccessibilityNodeInfo b=playerButton();
        if(b!=null) b.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    boolean matches(String title, String subtitle) {
        String[] candidates=norm(title+" "+subtitle).split(" ");
        int exact=0, near=0;
        String required=correction==null?originalQuery:query;
        for(String word:required.split(" ")) {
            boolean same=false, close=false;
            for(String candidate:candidates) {
                if(word.equals(candidate)) {same=true; break;}
                if(word.length()>=4 && candidate.length()>=4 && oneEdit(word,candidate)) close=true;
            }
            if(same) exact++;
            else if(close) near++;
            else return false;
        }
        return exact>0 && near<=1;
    }
    private static boolean oneEdit(String left,String right) {
        if(Math.abs(left.length()-right.length())>1) return false;
        int a=0,b=0,errors=0;
        while(a<left.length() && b<right.length()) {
            if(left.charAt(a)==right.charAt(b)) {a++; b++; continue;}
            if(++errors>1) return false;
            if(left.length()>=right.length()) a++;
            if(right.length()>=left.length()) b++;
        }
        return errors+left.length()-a+right.length()-b<=1;
    }
    boolean rightType(String subtitle) {
        String s = norm(subtitle);
        if (kind.equals("album")) return s.startsWith("альбом") || s.startsWith("album");
        if (kind.equals("playlist")) return s.startsWith("плейлист") || s.startsWith("playlist");
        if (kind.equals("artist")) return s.startsWith("исполнитель") || s.startsWith("artist");
        return s.startsWith("трек") || s.startsWith("песня") || s.startsWith("song") || s.startsWith("track");
    }
    private boolean showsQuery(AccessibilityNodeInfo node) {
        if(node==null || (SPOTIFY+":id/row_root").equals(node.getViewIdResourceName())) return false;
        if(node.isVisibleToUser() && norm(value(node)).equals(query)) return true;
        for(int i=0;i<node.getChildCount();i++) if(showsQuery(node.getChild(i))) return true;
        return false;
    }
    private boolean chooseResult(AccessibilityNodeInfo node,String title,String subtitle) {
        if(!clickResult(node)) return false;
        WakeService.event(this,"selected: "+kind);
        int marker=subtitle.indexOf('•');
        if(marker>=0) rememberArtist(subtitle.substring(marker+1));
        prompt(title,false,1600);
        if(kind.equals("track")) end(true);
        else selectedTitle=title;
        return true;
    }
    final Runnable check = new Runnable() {
        @Override public void run() {
            if(query==null) return;
            if(SystemClock.elapsedRealtime()>=deadline) {
                if(finished==null) Toast.makeText(SearchService.this,"Подходящий результат не подтверждён. Выберите его в поиске.",Toast.LENGTH_LONG).show();
                cancel(); return;
            }
            AccessibilityNodeInfo root=getRootInActiveWindow();
            if(root!=null && SPOTIFY.contentEquals(root.getPackageName()==null ? "" : root.getPackageName())) {
                if(selectedTitle==null) {
                    boolean correctQuery=showsQuery(root);
                    java.util.ArrayList<TitleCorrection.Row> candidates=new java.util.ArrayList<>();
                    java.util.ArrayList<TitleCorrection.Row> tracks=new java.util.ArrayList<>();
                    java.util.ArrayList<AccessibilityNodeInfo> trackNodes=new java.util.ArrayList<>();
                    boolean rankTracks=kind.equals("track") && correction==null;
                    if(correctQuery) for(AccessibilityNodeInfo row : root.findAccessibilityNodeInfosByViewId(SPOTIFY+":id/row_root")) {
                        String title=textById(row,"title"), subtitle=textById(row,"subtitle");
                        if(!row.isVisibleToUser() || title.isEmpty()) continue;
                        TitleCorrection.Row candidate=new TitleCorrection.Row(title,subtitle);
                        candidates.add(candidate);
                        if(correction!=null && (!norm(title).equals(norm(correction.title)) ||
                                !(" "+norm(subtitle)+" ").contains(" "+norm(correction.artist)+" "))) continue;
                        if(!row.isClickable() || !rightType(subtitle)) continue;
                        if(rankTracks) {
                            tracks.add(candidate);
                            trackNodes.add(row);
                        } else if(matches(title,subtitle) && chooseResult(row,title,subtitle)) {
                            if(query==null) return;
                            break;
                        }
                    }
                    int best=rankTracks?TitleCorrection.bestTrack(originalQuery,tracks):TitleCorrection.NO_TRACK;
                    if(best>=0) {
                        TitleCorrection.Row track=tracks.get(best);
                        if(chooseResult(trackNodes.get(best),track.title,track.subtitle)) return;
                    }
                    if(correctQuery && rankTracks && best==TitleCorrection.NO_TRACK) {
                        java.util.List<String> artists=artistHistory();
                        for(TitleCorrection.Row row:candidates) if(norm(row.subtitle).equals("исполнитель") || norm(row.subtitle).equals("artist")) artists.add(row.title);
                        correction=TitleCorrection.suggest(originalQuery,candidates,artists);
                        if(correction!=null) {
                            String next=correction.artist+" "+correction.title;
                            query=norm(next);
                            WakeService.event(SearchService.this,"Исправление по выдаче Spotify");
                            VoiceActivity.openQuery(SearchService.this,next);
                        } else if(!candidates.isEmpty()) {
                            long now=SystemClock.elapsedRealtime();
                            if(resultsSince==0) resultsSince=now;
                            if(now-resultsSince>1100 && !broadened) {
                                String next=TitleCorrection.broader(originalQuery,artists);
                                broadened=true;
                                if(!next.isEmpty() && !norm(next).equals(query)) {
                                    query=norm(next); resultsSince=0;
                                    WakeService.event(SearchService.this,"Уточнение по исполнителю");
                                    VoiceActivity.openQuery(SearchService.this,next);
                                }
                            } else if(broadened && now-resultsSince>1900) {end(false); return;}
                        }
                    }
                } else if(root.findAccessibilityNodeInfosByViewId(SPOTIFY+":id/search_content_recyclerview").isEmpty()
                        && !root.findAccessibilityNodeInfosByText(selectedTitle).isEmpty()) {
                    for(String id : new String[]{"play_button","button_play","play_action"}) {
                        for(AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(SPOTIFY+":id/"+id)) {
                            if(n.isVisibleToUser() && n.isClickable() && clickResult(n)) {end(true); return;}
                        }
                    }
                }
            }
            if(query!=null) handler.postDelayed(this,300);
        }
    };
}
