package ru.local.voyah.musicsearch;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.*;
import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.*;

public class WakeService extends Service {
    static final String AUTOMATIC="ru.local.voyah.musicsearch.START_LISTENER";
    static volatile String status="Прослушивание выключено";
    static volatile boolean active;
    static volatile boolean enrollmentRequested;
    private volatile boolean stopping;
    private final ExecutorService searches=Executors.newSingleThreadExecutor();
    // Command ownership and audio focus are transferred on the main looper.
    private volatile Command current;
    private static final class Command {
        final CompletableFuture<Boolean> cancelled=new CompletableFuture<>();
        String pausedTrack="";
        AudioFocusRequest focus;
        boolean granted;
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final String CHANNEL="music_wake";
    private static final int RATE=16000;
    private static final int START_REMINDER=32;
    private volatile String inputStats="Нет замера";
    private volatile String lastCommand="";
    private volatile String echoStatus="";
    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("Слушатель активен: "+active);
        writer.println(inputStats);
        writer.println(echoStatus);
        writer.println(lastCommand);
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
        if(i!=null && "stop".equals(i.getAction())) {disable(this); return START_NOT_STICKY;}
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
            String route=i==null?null:i.getStringExtra("route");
            active=true;
            new Thread(()->listen(route==null?"primeback":route,automatic),"Spotify keyword").start();
        }
        return START_STICKY;
    }
    @Override public void onDestroy() {
        stopping=true; active=false; enrollmentRequested=false;
        if(current!=null) finishCommand(current,false,null);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) {return null;}

    private AudioRecord recorder(int source) {
        AudioFormat fmt=new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
        AudioRecord r=new AudioRecord.Builder().setAudioSource(source).setAudioFormat(fmt)
                .setPrivacySensitive(false).setBufferSizeInBytes(Math.max(8192,AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)*2)).build();
        for(AudioDeviceInfo d:getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if(d.getType()==AudioDeviceInfo.TYPE_USB_DEVICE || d.getType()==AudioDeviceInfo.TYPE_USB_HEADSET) {
                r.setPreferredDevice(d); break;
            }
        }
        try {
            r.startRecording();
            if(r.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING) throw new MicrophoneUnavailable("Микрофон недоступен");
        } catch(RuntimeException failure) {
            r.release();
            if(failure instanceof IllegalStateException && !(failure instanceof MicrophoneUnavailable)) {
                throw new MicrophoneUnavailable("Микрофон недоступен",failure);
            }
            throw failure;
        }
        return r;
    }
    private static final class MicrophoneUnavailable extends IllegalStateException {
        MicrophoneUnavailable(String message) {super(message);}
        MicrophoneUnavailable(String message,Throwable cause) {super(message,cause);}
    }
    static KeywordSpotter spotter(Context context) throws IOException {return WakePhrase.create(context,new VoiceSettings(context).phrase());}
    private String idleText() {return "Жду фразу «"+new VoiceSettings(this).phrase()+"»";}
    private void listen(String route,boolean automatic) {
        try {
            try {listenSession(route);}
            catch(MicrophoneUnavailable first) {
                if(stopping) return;
                state("Восстанавливаю вход микрофона");
                SystemClock.sleep(1000);
                if(!stopping) listenSession(route);
            }
        } catch(Exception | LinkageError e) {
            if(!stopping) {
                status=String.valueOf(e.getMessage());
                event(this,"Ошибка слушателя: "+e.getClass().getSimpleName());
                if (automatic && (e instanceof MicrophoneUnavailable || e instanceof SecurityException)) {
                    main.post(()->{if(!stopping) showStartReminder(this);});
                }
            }
        } finally {
            searches.shutdown();
            main.post(this::stopSelf);
        }
    }
    private void listenSession(String route) throws Exception {
        AudioRecord r=null;
        AcousticEchoCanceler echo=null;
        KeywordSpotter kws=null;
        OnlineStream stream=null;
        AudioManager am=getSystemService(AudioManager.class);
        boolean priming=false;
        try {
            VoiceSettings settings=new VoiceSettings(this);
            String phrase=settings.phrase();
            kws=WakePhrase.create(this,phrase);
            VoiceProfile profile=loadProfile(phrase);
            stream=kws.createStream("");
            if(route.startsWith("prime") && am.getMode()==AudioManager.MODE_NORMAL) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION); priming=true;
                AudioRecord warm=recorder(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
                try {SystemClock.sleep(700);} finally {warm.release();}
            }
            r=recorder(route.endsWith("back")?MediaRecorder.AudioSource.CAMCORDER:MediaRecorder.AudioSource.MIC);
            if(priming) {SystemClock.sleep(400); am.setMode(AudioManager.MODE_NORMAL); priming=false;}
            try {
                if(AcousticEchoCanceler.isAvailable()) echo=AcousticEchoCanceler.create(r.getAudioSessionId());
                if(echo!=null && echo.hasControl()) echo.setEnabled(true);
                echoStatus="AEC: "+(echo!=null && echo.getEnabled()?"включён":"недоступен для этого входа");
            } catch(RuntimeException e) { echoStatus="AEC: "+e.getClass().getSimpleName(); }
            event(this,echoStatus);
            state(idleText());
            short[] buf=new short[640];
            long since=SystemClock.elapsedRealtime(), audit=since, voiced=since;
            int frames=0,peak=0;
            while(!stopping) {
                String selected=settings.phrase();
                if(!selected.equals(phrase)) {
                    stream.release();stream=null;kws.release();kws=null;
                    kws=WakePhrase.create(this,selected);
                    stream=kws.createStream("");
                    phrase=selected;profile=loadProfile(phrase);
                    if(current==null) state(idleText());
                }
                if(enrollmentRequested) {
                    try {
                        onMain(()->{if(current!=null) finishCommand(current,false,null);return true;});
                        profile=enroll(r,phrase);
                        state("Голосовой образец сохранён. "+idleText());
                    } catch(Exception e) {state("Обучение: "+e.getMessage());}
                    finally {enrollmentRequested=false;}
                    stream.release();stream=kws.createStream("");voiced=SystemClock.elapsedRealtime();
                    continue;
                }
                int n=r.read(buf,0,buf.length,AudioRecord.READ_NON_BLOCKING);
                if(n<0) throw new MicrophoneUnavailable("Ошибка микрофона "+n);
                long now=SystemClock.elapsedRealtime();
                if(n>0) {
                    float[] input=new float[n];
                    for(int j=0;j<n;j++) {input[j]=buf[j]/32768f; peak=Math.max(peak,Math.abs((int)buf[j])); if(buf[j]!=0) voiced=now;}
                    frames+=n;
                    stream.acceptWaveform(input,RATE);
                    boolean hit=false;
                    while(kws.isReady(stream)) {
                        kws.decode(stream);
                        if(!kws.getResult(stream).getKeyword().isEmpty()) {hit=true; break;}
                    }
                    boolean voiceHit=profile!=null && profile.accept(input);
                    if(hit || voiceHit) {
                        event(this,"wake via="+(hit?"text":"voice"));
                        handleCommand(r,am);
                        stream.release();
                        stream=kws.createStream("");
                        if(profile!=null) profile.reset();
                        voiced=SystemClock.elapsedRealtime(); audit=voiced; frames=0; peak=0;
                    }
                } else SystemClock.sleep(12);
                if(now-audit>5000) {
                    inputStats="input="+route+" frames="+frames+" peak="+peak;
                    event(this,inputStats);
                    audit=now; frames=0; peak=0;
                }
                if(now-voiced>12000) throw new MicrophoneUnavailable("Микрофон не выдаёт звук в режиме ожидания");
            }
        } finally {
            if(echo!=null) echo.release();
            if(r!=null) r.release(); if(priming) am.setMode(AudioManager.MODE_NORMAL);
            if(stream!=null) stream.release(); if(kws!=null) kws.release();
        }
    }
    private VoiceProfile loadProfile(String phrase) {
        File file=profileFile(this,phrase);
        if(!file.isFile()) return null;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            return VoiceProfile.read(in);
        } catch(IOException e) {event(this,"Образец недоступен");return null;}
    }
    static File profileFile(Context c,String phrase) {
        // Normalized input contains letters and spaces only.
        return new File(c.getFilesDir(),"voice-profile-"+WakePhrase.normalize(phrase)+".bin");
    }
    private VoiceProfile enroll(AudioRecord r,String phrase) throws Exception {
        float[][][] examples=new float[3][][];
        for(int i=0;i<examples.length;i++) {
            if(stopping || !enrollmentRequested) throw new CancellationException("отменено");
            state("После сигнала скажите «"+phrase+"» · "+(i+1)+" из 3");
            ToneGenerator tone=new ToneGenerator(AudioManager.STREAM_MUSIC,new VoiceSettings(this).get(VoiceSettings.VOLUME,65));
            try {tone.startTone(ToneGenerator.TONE_PROP_ACK,180);SystemClock.sleep(230);} finally {tone.release();}
            drain(r);
            float[] audio=CabinMic.read(r,()->{},()->stopping || !enrollmentRequested);
            examples[i]=VoiceProfile.features(audio);
        }
        VoiceProfile result=VoiceProfile.train(examples);
        android.util.AtomicFile dest=new android.util.AtomicFile(profileFile(this,phrase));
        FileOutputStream raw=dest.startWrite();
        try {
            DataOutputStream out=new DataOutputStream(new BufferedOutputStream(raw));
            result.write(out);out.flush();dest.finishWrite(raw);
        } catch(IOException e) {dest.failWrite(raw);throw e;}
        return result;
    }
    private void drain(AudioRecord r) {
        short[] discard=new short[2048];
        for(int i=0;i<8 && r.read(discard,0,discard.length,AudioRecord.READ_NON_BLOCKING)>0;i++) {}
    }
    private Command beginCommand(AudioManager audio) {
        if(stopping) throw new CancellationException();
        Command next=new Command(), previous=current;
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
        return next;
    }
    private void finishCommand(Command command,boolean picked,String error) {
        if(current!=command) return;
        current=null;
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
    private void handleCommand(AudioRecord r,AudioManager am) throws Exception {
        if(stopping) return;
        Command command=onMain(()->beginCommand(am));
        VoiceSettings settings=new VoiceSettings(this);
        try {
            boolean confirmed=false;
            long until=SystemClock.elapsedRealtime()+800;
            do {
                confirmed=onMain(()->SearchService.instance!=null && SearchService.instance.paused());
                if(confirmed || !am.isMusicActive()) break;
                SystemClock.sleep(45);
            } while(SystemClock.elapsedRealtime()<until);
            lastCommand="Перед записью: pauseConfirmed="+confirmed+" explicit="+!command.pausedTrack.isEmpty()+" focus="+command.granted;
            event(this,lastCommand);
            onMain(()->{if(current==command && SearchService.instance!=null) SearchService.instance.prompt("Слушаю",true,10000);return true;});
            ToneGenerator tone=new ToneGenerator(AudioManager.STREAM_MUSIC,settings.get(VoiceSettings.VOLUME,VoiceSettings.DEFAULT_VOLUME));
            try {tone.startTone(ToneGenerator.TONE_PROP_ACK,300);SystemClock.sleep(340);} finally {tone.release();}
            drain(r);
            state("Говорите название и исполнителя");
            float[] samples=CabinMic.read(r,()->{},()->stopping);

            final String lang=settings.language();
            final int wait=settings.get(VoiceSettings.DELAY,VoiceSettings.DEFAULT_DELAY);
            final int engine=settings.engine();
            onMain(()->{
                if(current==command) {
                    state("Распознаю название…");
                    if(SearchService.instance!=null) SearchService.instance.prompt("Распознаю…",false,18000);
                }
                return true;
            });
            searches.execute(()->recognizeAndSelect(command,samples,lang,wait,engine));
        } catch(Exception | LinkageError e) {
            main.post(()->finishCommand(command,false,String.valueOf(e.getMessage())));
        }
    }
    private void recognizeAndSelect(Command command,float[] samples,String lang,int wait,int engine) {
        boolean picked=false;
        String error=null;
        try {
            String text=decode(samples,lang,command.cancelled,engine);
            picked=searchWithFallback(text,samples,command.cancelled,lang,wait,engine);
            if(!picked) error="Подходящий трек не найден";
        } catch(CancellationException ignored) {
            // A new wake word or a Spotify tap owns the interaction.
        } catch(Exception | LinkageError failure) {error=String.valueOf(failure.getMessage());}
        final boolean result=picked;
        final String message=error;
        main.post(()->finishCommand(command,result,message));
    }

    private boolean searchWithFallback(String text,float[] samples,CompletableFuture<Boolean> manualPick,
                                       String preferredLanguage,int extraWait,int engine) throws Exception {
        SpokenRequest request=SpokenRequest.parse(text);
        if(select(request,manualPick)) return true;
        if(stopping || !preferredLanguage.isEmpty()) return false;
        event(this,"manual-wait-ms="+extraWait);
        try {
            return manualPick.get(extraWait,TimeUnit.MILLISECONDS);
        } catch(TimeoutException elapsed) {
            if(manualPick.isDone()) return true;
        }
        String language=text.matches("(?s).*\\p{IsCyrillic}.*")?"en":"ru";
        onMain(()->{if(!manualPick.isDone()) state("Проверяю название ещё раз…");return true;});
        SpokenRequest alternate=SpokenRequest.parse(decode(samples,language,manualPick,engine));
        return manualPick.isDone() || (!request.sameSearchAs(alternate) && select(alternate,manualPick));
    }
    private <T> T onMain(Callable<T> operation) throws Exception {
        FutureTask<T> task=new FutureTask<>(operation);
        main.post(task);
        try {return task.get(1200,TimeUnit.MILLISECONDS);}
        finally {task.cancel(false);}
    }
    private boolean select(SpokenRequest request,CompletableFuture<Boolean> manualPick) throws Exception {
        if(request.query.isEmpty()) return false;
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
        CompletableFuture<Bundle> response=new CompletableFuture<>();
        Messenger reply=new Messenger(new Handler(Looper.getMainLooper(),m->{response.complete(m.getData());return true;}));
        ServiceConnection conn=new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name,IBinder binder) {
                if(manualPick.isDone()) return;
                Message m=Message.obtain(); Bundle data=new Bundle(); data.putByteArray("pcm",pcm);
                data.putString("lang",language); data.putInt("engine",engine); m.setData(data); m.replyTo=reply;
                try {new Messenger(binder).send(m);} catch(RemoteException e) {response.complete(new Bundle());}
            }
            @Override public void onServiceDisconnected(ComponentName n) {response.complete(new Bundle());}
        };
        if(!bindService(new Intent(this,SpeechWorker.class),conn,BIND_AUTO_CREATE)) throw new IOException("Распознаватель недоступен");
        try {
            try {CompletableFuture.anyOf(response,manualPick).get(24,TimeUnit.SECONDS);}
            catch(TimeoutException elapsed) {throw new IOException("Истекло время распознавания",elapsed);}
            if(manualPick.isDone()) throw new CancellationException();
            Bundle b=response.get();
            if(!b.containsKey("text")) throw new IOException(b.getString("error","Распознаватель закрыт"));
            onMain(()->{
                if(!manualPick.isDone()) {
                    lastCommand+=" asrMs="+b.getLong("time")+" engine="+(engine==VoiceSettings.FUTO?"futo":"base")+" lang="+(language.isEmpty()?"ru/en":language);
                    event(this,lastCommand);
                }
                return true;
            });
            return b.getString("text");
        } finally {unbindService(conn);}
    }
}
