package ru.local.voyah.musicsearch;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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
// instance, query, manualPick and pausePlayingTrack are also read by reflection from voice/bench (SearchPhase, FirstPassCheck).
public class SearchService extends AccessibilityService {
    static SearchService instance;
    final Handler handler = new Handler(Looper.getMainLooper());
    String query, kind, selectedTitle;
    private ListenerEvents.Monitor startupMonitor;
    private SpotifySearchTiming timing;
    private String initialText;
    private SpokenRequest activeRequest;
    private boolean inferArtist;
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
    @Override protected void onServiceConnected() {
        instance=this;
        if(startupMonitor==null) startupMonitor=new ListenerEvents.Monitor(this,handler);
        ListenerEvents.requestStart(this,false);
    }
    @Override public void onDestroy() {
        if(startupMonitor!=null) startupMonitor.close();
        onInterrupt();
        handler.removeCallbacks(removeBanner);
        removeBanner.run();
        instance=null;
        super.onDestroy();
    }
    @Override public void onInterrupt() {
        if(manualPick!=null) manualPick.complete(true);
        manualPick=null;
        forgetOwnClick();
        cancel();
    }
    void cancel() { end(false); }
    void end(boolean picked) {
        query=null; selectedTitle=null; correction=null; timing=null; initialText=null; activeRequest=null;
        handler.removeCallbacks(check);
        java.util.function.Consumer<Boolean> cb=finished;
        finished=null;
        if(cb!=null) cb.accept(picked);
    }
    void search(SpokenRequest request,java.util.function.Consumer<Boolean> callback) {
        cancel();
        finished=callback;
        String text=request.query;
        activeRequest=request;
        query = norm(text);
        originalQuery=query; broadened=false; resultsSince=0;
        initialText=text;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        boolean spotifyVisible=isSpotify(root) && spotifyUiReady(root);
        if(root!=null) root.recycle();
        timing=new SpotifySearchTiming(SystemClock.elapsedRealtime(),spotifyVisible);
        prompt("Ищу: "+text,false,3000);
        kind=request.kind;
        inferArtist=!request.explicitKind && !request.hasTrackFields();
        try {
            openSpotify(text);
        } catch(RuntimeException failure) {
            cancel();
            throw failure;
        }
        handler.postDelayed(check, 500);
    }
    private void openSpotify(String text) {
        try {VoiceActivity.openQuery(this,text);}
        catch(ActivityNotFoundException noSearchHandler) {
            Intent launcher=getPackageManager().getLaunchIntentForPackage(SpotifyUi.PACKAGE);
            if(launcher==null) throw noSearchHandler;
            startActivity(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
    /** Send another query to Spotify during a search. A failure ends the search instead of the process. */
    private boolean reopen(String text) {
        try {VoiceActivity.openQuery(this,text); return true;}
        catch(ActivityNotFoundException|SecurityException unavailable) {cancel(); return false;}
    }
    private static boolean isSpotify(AccessibilityNodeInfo root) {
        return root!=null && SpotifyUi.PACKAGE.equals(String.valueOf(root.getPackageName()));
    }
    private static boolean spotifyUiReady(AccessibilityNodeInfo root) {
        // The splash window already belongs to Spotify, but has no navigation or search UI.
        return !root.findAccessibilityNodeInfosByViewId(SpotifyUi.NAVIGATION_BAR).isEmpty()
                || !root.findAccessibilityNodeInfosByViewId(SpotifyUi.SEARCH_RESULTS).isEmpty();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        String sourcePackage=String.valueOf(event.getPackageName());
        if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                (SpotifyUi.PACKAGE.equals(sourcePackage) || "com.qinggan.app.music".equals(sourcePackage) ||
                 "com.qinggan.media".equals(sourcePackage) || "com.android.car.media.localmediaplayer".equals(sourcePackage))) {
            ListenerEvents.requestStart(this,true);
        }
        if(!SpotifyUi.PACKAGE.equals(sourcePackage)) return;
        if(event.getEventType()==AccessibilityEvent.TYPE_VIEW_CLICKED
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
        for (AccessibilityNodeInfo n : row.findAccessibilityNodeInfosByViewId(id)) return value(n);
        return "";
    }
    private AccessibilityNodeInfo playerButton() {
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(!isSpotify(root)) return null;
        for(AccessibilityNodeInfo b:root.findAccessibilityNodeInfosByViewId(SpotifyUi.PLAY_PAUSE))
            if(b.isVisibleToUser() && b.isClickable()) return b;
        return null;
    }
    String currentTrack() {
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(!isSpotify(root)) return "";
        String title="";
        for(String id:SpotifyUi.TRACK_TITLE) if(title.isEmpty()) title=textById(root,id);
        return title.isEmpty()?"":title+" / "+textById(root,SpotifyUi.TRACK_ARTIST);
    }
    boolean paused() {
        AccessibilityNodeInfo b=playerButton();
        return b!=null && SpotifyUi.offersPlay(norm(String.valueOf(b.getContentDescription())));
    }
    String pausePlayingTrack() {
        AccessibilityNodeInfo b=playerButton();
        String id=currentTrack();
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(isSpotify(root)) rememberArtist(textById(root,SpotifyUi.TRACK_ARTIST));
        if(b==null || id.isEmpty()) return "";
        if(SpotifyUi.offersPause(norm(String.valueOf(b.getContentDescription())))
                && b.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return id;
        return "";
    }
    void resumeIfSame(String id) {
        if(id.isEmpty() || !id.equals(currentTrack()) || !paused()) return;
        AccessibilityNodeInfo b=playerButton();
        if(b!=null) b.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    private boolean showsQuery(AccessibilityNodeInfo node) {
        if(node==null || SpotifyUi.ROW.equals(node.getViewIdResourceName())) return false;
        if(node.isVisibleToUser() && norm(value(node)).equals(query)) return true;
        for(int i=0;i<node.getChildCount();i++) if(showsQuery(node.getChild(i))) return true;
        return false;
    }
    private boolean chooseResult(AccessibilityNodeInfo node,String title,String subtitle,String type) {
        if(!clickResult(node)) return false;
        WakeService.event(this,"selected: "+type);
        int marker=subtitle.indexOf('•');
        if(type.equals("artist")) rememberArtist(title);
        else if(marker>=0) rememberArtist(subtitle.substring(marker+1));
        prompt(title,false,1600);
        if(type.equals("track") || type.equals("artist")) end(true);
        else selectedTitle=title;
        return true;
    }
    final Runnable check = new Runnable() {
        @Override public void run() {
            if(query==null) return;
            long now=SystemClock.elapsedRealtime();
            if(timing.expired(now)) {
                if(finished==null) Toast.makeText(SearchService.this,"Подходящий результат не подтверждён. Выберите его в поиске.",Toast.LENGTH_LONG).show();
                cancel(); return;
            }
            AccessibilityNodeInfo root=getRootInActiveWindow();
            if(isSpotify(root)) {
                if(selectedTitle==null) {
                    boolean correctQuery=showsQuery(root);
                    if(correctQuery) timing.queryShown(now);
                    else if(spotifyUiReady(root) && timing.shouldResendInSpotify(now) && !reopen(initialText)) return;
                    java.util.ArrayList<TitleCorrection.Row> candidates=new java.util.ArrayList<>();
                    java.util.ArrayList<TitleCorrection.Row> tracks=new java.util.ArrayList<>();
                    java.util.ArrayList<AccessibilityNodeInfo> trackNodes=new java.util.ArrayList<>();
                    java.util.ArrayList<TitleCorrection.Row> artistsFound=new java.util.ArrayList<>();
                    java.util.ArrayList<AccessibilityNodeInfo> artistNodes=new java.util.ArrayList<>();
                    boolean rankTracks=kind.equals("track") && correction==null;
                    boolean artistSearch=kind.equals("artist");
                    if(correctQuery) for(AccessibilityNodeInfo row : root.findAccessibilityNodeInfosByViewId(SpotifyUi.ROW)) {
                        String title=textById(row,SpotifyUi.ROW_TITLE), subtitle=textById(row,SpotifyUi.ROW_SUBTITLE);
                        if(!row.isVisibleToUser() || title.isEmpty()) continue;
                        TitleCorrection.Row candidate=new TitleCorrection.Row(title,subtitle);
                        candidates.add(candidate);
                        if(!row.isClickable()) continue;
                        if((artistSearch || (rankTracks && inferArtist)) && TitleCorrection.isArtist(candidate)) {
                            artistsFound.add(candidate);
                            artistNodes.add(row);
                            continue;
                        }
                        if(artistSearch) continue;
                        if(correction!=null && (!norm(title).equals(norm(correction.title)) ||
                                !(" "+norm(subtitle)+" ").contains(" "+norm(correction.artist)+" "))) continue;
                        if(!SpotifyUi.isKind(kind,norm(subtitle))) continue;
                        if(rankTracks) {
                            tracks.add(candidate);
                            trackNodes.add(row);
                        } else if(TitleCorrection.wordsMatch(correction==null?originalQuery:query,title,subtitle) && chooseResult(row,title,subtitle,kind)) {
                            if(query==null) return;
                            break;
                        }
                    }
                    if(resultsSince==0 && !candidates.isEmpty()) resultsSince=now;
                    int best=rankTracks?TitleCorrection.bestTrack(activeRequest,tracks):TitleCorrection.NO_TRACK;
                    if(best>=0) {
                        TitleCorrection.Row track=tracks.get(best);
                        if(chooseResult(trackNodes.get(best),track.title,track.subtitle,"track")) return;
                    }
                    if(artistSearch || (rankTracks && inferArtist && best==TitleCorrection.NO_TRACK &&
                            resultsSince!=0 && now-resultsSince>=900)) {
                        int artist=TitleCorrection.bestArtist(originalQuery,artistsFound);
                        if(artist>=0) {
                            TitleCorrection.Row card=artistsFound.get(artist);
                            if(chooseResult(artistNodes.get(artist),card.title,card.subtitle,"artist")) return;
                        }
                    }
                    if(correctQuery && rankTracks && best==TitleCorrection.NO_TRACK && !activeRequest.hasTrackFields()) {
                        java.util.List<String> artists=artistHistory();
                        for(TitleCorrection.Row row:candidates) if(TitleCorrection.isArtist(row)) artists.add(row.title);
                        correction=TitleCorrection.suggest(originalQuery,candidates,artists);
                        if(correction!=null) {
                            String next=correction.artist+" "+correction.title;
                            query=norm(next);
                            WakeService.event(SearchService.this,"Исправление по выдаче Spotify");
                            if(!reopen(next)) return;
                        } else if(!candidates.isEmpty()) {
                            if(now-resultsSince>1100 && !broadened) {
                                String next=TitleCorrection.broader(originalQuery,artists);
                                broadened=true;
                                if(!next.isEmpty() && !norm(next).equals(query)) {
                                    query=norm(next); resultsSince=0;
                                    WakeService.event(SearchService.this,"Уточнение по исполнителю");
                                    if(!reopen(next)) return;
                                }
                            } else if(broadened && now-resultsSince>1900) {end(false); return;}
                        }
                    }
                } else if(root.findAccessibilityNodeInfosByViewId(SpotifyUi.SEARCH_RESULTS).isEmpty()
                        && !root.findAccessibilityNodeInfosByText(selectedTitle).isEmpty()) {
                    for(String id : SpotifyUi.PAGE_PLAY) {
                        for(AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(id)) {
                            if(n.isVisibleToUser() && n.isClickable() && clickResult(n)) {end(true); return;}
                        }
                    }
                }
            }
            if(query!=null) handler.postDelayed(this,300);
        }
    };
}
