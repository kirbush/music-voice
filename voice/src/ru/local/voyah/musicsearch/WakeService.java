package ru.local.voyah.musicsearch;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.util.concurrent.*;

public class WakeService extends Service {
    static final String AUTOMATIC="ru.local.voyah.musicsearch.START_LISTENER";
    static volatile String status="Прослушивание выключено";
    static volatile boolean active;
    static volatile boolean enrollmentRequested;
    volatile boolean stopping;
    private final ExecutorService searches=Executors.newSingleThreadExecutor();
    // Command ownership and audio focus are transferred on the main looper.
    private volatile Command current;
    private static final class Command {
        final CompletableFuture<Boolean> cancelled=new CompletableFuture<>();
        final String language;
        final int retryDelay,engine,volume;
        volatile SpokenRequest nameRequest;
        String pausedTrack="";
        AudioFocusRequest focus;
        boolean granted;
        Command(VoiceSettings settings) {
            language=settings.language();
            retryDelay=settings.get(VoiceSettings.DELAY,VoiceSettings.DEFAULT_DELAY);
            engine=settings.engine();
            volume=settings.get(VoiceSettings.VOLUME,VoiceSettings.DEFAULT_VOLUME);
        }
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SpeechLink speech=new SpeechLink(this);
    private static final String CHANNEL="music_wake";
    private static final int START_REMINDER=32;
    volatile String inputStats="Нет замера";
    private volatile String lastCommand="";
    volatile String echoStatus="";
    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("Слушатель активен: "+active);
        writer.println(inputStats);
        writer.println(echoStatus);
        writer.println(lastCommand);
        String afterUpdate=SelectorAccess.updateStatus(this);
        if(!afterUpdate.isEmpty()) writer.println("Автовыбор после обновления: "+afterUpdate);
        writer.println("Spotify: "+SpotifyUi.installedVersion(this)+", проверено на "+SpotifyUi.VERIFIED_VERSION);
    }
    static void event(Context c,String line) {
        android.util.Log.i("MusicVoice",line);
    }
    void state(String text) {
        if(stopping) return;
        status=text;

        getSystemService(NotificationManager.class).notify(31,notice(text));
    }
    private static Notification.Builder notice(Context context,String text) {
        Intent off=new Intent(context,ListenerEvents.class).setAction(ListenerEvents.STOP);
        return new Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(context.getString(R.string.app_name)).setContentText(text)
            .setContentIntent(PendingIntent.getActivity(context,0,new Intent(context,VoiceActivity.class),PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_delete,"Выключить",PendingIntent.getBroadcast(context,0,off,PendingIntent.FLAG_IMMUTABLE));
    }
    private Notification notice(String text) {return notice(this,text).setOngoing(true).build();}
    private static NotificationManager notifications(Context context) {
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Голосовой поиск",NotificationManager.IMPORTANCE_LOW));
        return manager;
    }
    static void showStartReminder(Context context) {
        if(!new VoiceSettings(context).listeningEnabled()) return;
        status="Откройте приложение и включите прослушивание";
        notifications(context).notify(START_REMINDER,notice(context,status).build());
    }
    static void disable(Context context) {
        new VoiceSettings(context).setListeningEnabled(false);
        status="Прослушивание выключено";
        context.getSystemService(NotificationManager.class).cancel(START_REMINDER);
        context.stopService(new Intent(context,WakeService.class));
    }
    @Override public int onStartCommand(Intent i,int flags,int id) {
        if(!active) {
            VoiceSettings settings=new VoiceSettings(this);
            NotificationManager manager=notifications(this);
            boolean automatic=i==null || AUTOMATIC.equals(i.getAction());
            status="Подготовка микрофона";
            try {
                startForeground(31,notice(status),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } catch(SecurityException|IllegalStateException blocked) {
                if(automatic) showStartReminder(this);
                else status="Микрофон недоступен";
                stopSelf(); return START_NOT_STICKY;
            }
            if(!settings.listeningEnabled() || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    !=android.content.pm.PackageManager.PERMISSION_GRANTED) {
                status="Прослушивание выключено";stopSelf();return START_NOT_STICKY;
            }
            if(!ModelStore.get(this,settings.engine()).ready) {status="Сначала скачайте модели";stopSelf();return START_NOT_STICKY;}
            manager.cancel(START_REMINDER);
            active=true;
            new Thread(()->listen(automatic),"Spotify keyword").start();
        }
        return START_STICKY;
    }
    @Override public void onDestroy() {
        stopping=true; active=false; enrollmentRequested=false;
        if(current!=null) finishCommand(current,false,null);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) {return null;}

    static KeywordSpotter spotter(Context context) throws IOException {return WakePhrase.create(context,new VoiceSettings(context).phrase());}
    String idleText() {return "Жду фразу «"+new VoiceSettings(this).phrase()+"»";}
    static File profileFile(Context c,String phrase) {
        // Normalized input contains letters and spaces only.
        return new File(c.getFilesDir(),"voice-profile-"+WakePhrase.normalize(phrase)+".bin");
    }
    private void listen(boolean automatic) {
        try {new KeywordLoop(this).run();}
        catch(Exception | LinkageError e) {
            if(!stopping) {
                status=String.valueOf(e.getMessage());
                event(this,"Ошибка слушателя: "+e.getClass().getSimpleName());
                if (automatic && (e instanceof KeywordLoop.MicrophoneUnavailable || e instanceof SecurityException)) {
                    main.post(()->{if(!stopping) showStartReminder(this);});
                }
            }
        } finally {
            searches.shutdown();
            main.post(this::stopSelf);
        }
    }

    // Called by KeywordLoop on the listener thread.
    boolean commandActive() {return current!=null;}
    /** Enrollment takes the microphone, so the command in progress ends. */
    void cancelCommand() throws Exception {
        onMain(()->{if(current!=null) finishCommand(current,false,null);return true;});
    }
    /** Record the clarification the current command is waiting for. Returns false when there is nothing to record. */
    boolean captureClarification(AudioRecord r) {
        Command pending=current;
        SpokenRequest name=pending==null?null:pending.nameRequest;
        if(name==null) return false;
        pending.nameRequest=null;
        // A tap in Spotify while the clarification was pending ends the command.
        if(pending.cancelled.isDone()) {main.post(()->finishCommand(pending,false,null)); return false;}
        captureRequest(r,pending,name);
        return true;
    }
    // beginCommand(AudioManager), finishCommand, select, searchWithFallback, spotter, status and Command.cancelled
    // are also reached by reflection from voice/bench (BargeInCheck, ManualSelectionCheck, SearchPhase).
    private Command beginCommand(AudioManager audio) {
        Command next=new Command(new VoiceSettings(this));
        beginCommand(next,audio);
        return next;
    }
    private void beginCommand(Command next,AudioManager audio) {
        if(stopping) throw new CancellationException();
        Command previous=current;
        SearchService search=SearchService.instance;
        if(previous!=null) {
            boolean ownedPause=!previous.cancelled.isDone();
            previous.cancelled.complete(true);
            if(search!=null) search.stopWatching(previous.cancelled);
            next.focus=previous.focus; next.granted=previous.granted;
            if(ownedPause) next.pausedTrack=previous.pausedTrack;
            event(this,"restart: previous search cancelled");
        }
        current=next;
        // The model loads while the driver is still speaking. A replaced command passes its hold on.
        speech.open(next.engine);
        if(previous!=null) speech.close();
        if(search!=null) {
            String paused=search.pausePlayingTrack();
            if(!paused.isEmpty()) next.pausedTrack=paused;
        }
        if(!next.granted) {
            next.focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).build())
                .setOnAudioFocusChangeListener(x->{}).build();
            next.granted=audio.requestAudioFocus(next.focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }
    private void finishCommand(Command command,boolean picked,String error) {
        if(current!=command) return;
        current=null;
        speech.close();
        boolean manual=command.cancelled.isDone();
        command.cancelled.complete(true);
        SearchService search=SearchService.instance;
        if(search!=null) search.stopWatching(command.cancelled);
        if(command.granted) getSystemService(AudioManager.class).abandonAudioFocusRequest(command.focus);
        if(search!=null && !picked && !manual) {
            search.resumeIfSame(command.pausedTrack);
            if(error!=null && !stopping) search.prompt("Не удалось распознать команду",false,2500);
        }
        if(error!=null && !manual) event(this,"Ошибка команды");
        if(!stopping) state(idleText());
    }
    void handleCommand(AudioRecord r,AudioManager am) {
        if(stopping) return;
        Command command=new Command(new VoiceSettings(this));
        try {
            // A busy main thread costs this command, not the listener.
            onMain(()->{beginCommand(command,am);return true;});
            boolean confirmed=false;
            long until=SystemClock.elapsedRealtime()+800;
            do {
                confirmed=onMain(()->SearchService.instance!=null && SearchService.instance.paused());
                if(confirmed || !am.isMusicActive()) break;
                SystemClock.sleep(45);
            } while(SystemClock.elapsedRealtime()<until);
            lastCommand="Перед записью: pauseConfirmed="+confirmed+" explicit="+!command.pausedTrack.isEmpty()+" focus="+command.granted;
            event(this,lastCommand);
            captureRequest(r,command,null);
        } catch(Exception | LinkageError e) {
            main.post(()->finishCommand(command,false,String.valueOf(e.getMessage())));
        }
    }
    private void captureRequest(AudioRecord r,Command command,SpokenRequest nameRequest) {
        String prompt=nameRequest==null?"Слушаю":nameRequest.namePrompt();
        try {
            boolean listening=onMain(()->{
                if(stopping || current!=command || command.cancelled.isDone()) return false;
                if(SearchService.instance!=null) SearchService.instance.prompt(prompt,true,10000);
                return true;
            });
            if(!listening) {main.post(()->finishCommand(command,false,null)); return;}
            ToneGenerator tone=new ToneGenerator(AudioManager.STREAM_MUSIC,command.volume);
            try {tone.startTone(ToneGenerator.TONE_PROP_ACK,300);SystemClock.sleep(340);} finally {tone.release();}
            CabinMic.drain(r);
            state(nameRequest==null?"Говорите название и исполнителя":prompt);
            float[] samples=CabinMic.read(r,()->{},()->stopping || command.cancelled.isDone());
            onMain(()->{
                if(current==command) {
                    state("Распознаю название…");
                    if(SearchService.instance!=null) SearchService.instance.prompt("Распознаю…",false,18000);
                }
                return true;
            });
            searches.execute(()->recognizeAndSelect(command,samples,nameRequest));
        } catch(Exception | LinkageError e) {
            main.post(()->finishCommand(command,false,String.valueOf(e.getMessage())));
        }
    }
    private void recognizeAndSelect(Command command,float[] samples,SpokenRequest nameRequest) {
        boolean picked=false;
        String error=null;
        try {
            String text=decode(samples,command.language,command.cancelled,command.engine);
            SpokenRequest request=nameRequest==null?SpokenRequest.parse(text):nameRequest.withName(text);
            if(!request.needsName()) picked=searchWithFallback(request,samples,command.cancelled,
                    command.language,command.retryDelay,command.engine);
            if(nameRequest==null && !picked && !stopping && !command.cancelled.isDone()) {
                // The microphone thread captures the next utterance for this command.
                command.nameRequest=request;
                return;
            }
            if(!picked) error="Подходящий результат не найден";
        } catch(CancellationException ignored) {
            // A new wake word or a Spotify tap owns the interaction.
        } catch(Exception | LinkageError failure) {error=String.valueOf(failure.getMessage());}
        final boolean result=picked;
        final String message=error;
        main.post(()->finishCommand(command,result,message));
    }

    private boolean searchWithFallback(SpokenRequest request,float[] samples,CompletableFuture<Boolean> manualPick,
                                       String preferredLanguage,int extraWait,int engine) throws Exception {
        if(select(request,manualPick)) return true;
        if(stopping || !preferredLanguage.isEmpty()) return false;
        event(this,"manual-wait-ms="+extraWait);
        try {
            return manualPick.get(extraWait,TimeUnit.MILLISECONDS);
        } catch(TimeoutException elapsed) {
            if(manualPick.isDone()) return true;
        }
        String language=request.query.matches("(?s).*\\p{IsCyrillic}.*")?"en":"ru";
        onMain(()->{if(!manualPick.isDone()) state("Проверяю название ещё раз…");return true;});
        SpokenRequest alternate=request.withName(decode(samples,language,manualPick,engine));
        return manualPick.isDone() || (!request.sameSearchAs(alternate) &&
                TitleCorrection.corroborates(request,alternate) && select(alternate,manualPick));
    }
    private <T> T onMain(Callable<T> operation) throws Exception {
        FutureTask<T> task=new FutureTask<>(operation);
        main.post(task);
        try {return task.get(1200,TimeUnit.MILLISECONDS);}
        finally {task.cancel(false);}
    }
    private boolean select(SpokenRequest request,CompletableFuture<Boolean> manualPick) throws Exception {
        if(request.needsName()) return false;
        CompletableFuture<Boolean> result=new CompletableFuture<>();
        onMain(()->{
            if(manualPick.isDone()) {result.complete(true); return true;}
            SearchService search=SearchService.instance;
            if(search==null) throw new IllegalStateException("Служба выбора Spotify отключена");
            search.watchUser(manualPick);
            VoiceActivity.launchSearch(this,request,result::complete);
            return true;
        });
        try {return Boolean.TRUE.equals(CompletableFuture.anyOf(result,manualPick)
                .get(SpotifySearchTiming.MAX_WAIT_MS,TimeUnit.MILLISECONDS));}
        catch(TimeoutException elapsed) {return false;}
    }
    private String decode(float[] samples,String language,CompletableFuture<Boolean> manualPick,int engine) throws Exception {
        if(manualPick.isDone()) throw new CancellationException();
        byte[] pcm=new byte[2*samples.length];
        for(int i=0;i<samples.length;i++) {
            int v=(int)(samples[i]*32768f);
            pcm[2*i]=(byte)v; pcm[2*i+1]=(byte)(v>>8);
        }
        Bundle b;
        speech.open(engine);
        try {b=speech.transcribe(pcm,language,engine,manualPick);}
        finally {speech.close();}
        if(!b.containsKey("text")) throw new IOException(b.getString("error","Распознаватель закрыт"));
        onMain(()->{
            if(!manualPick.isDone()) {
                lastCommand+=" asrMs="+b.getLong("time")+" engine="+(engine==VoiceSettings.FUTO?"futo":"base")+" lang="+(language.isEmpty()?"ru/en":language);
                event(this,lastCommand);
            }
            return true;
        });
        return b.getString("text");
    }
}
