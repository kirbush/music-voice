package ru.local.voyah.musicsearch;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class SpeechWorker extends Service {
    /** Load the engine's model ahead of the recording. The request has no reply. */
    static final int WARM=1;
    private final ExecutorService cpu=Executors.newSingleThreadExecutor();
    private final Messenger inbox=new Messenger(new Handler(Looper.getMainLooper(),msg->{
        byte[] pcm=msg.getData().getByteArray("pcm");
        Messenger reply=msg.replyTo;
        String lang=msg.getData().getString("lang","");
        int engine=msg.getData().getInt("engine",VoiceSettings.BASE);
        if(msg.what==WARM) {
            cpu.execute(()->{
                try {if(engine==VoiceSettings.FUTO) FutoSpeech.warm(this); else LocalSpeech.warm(this);}
                catch(Exception | LinkageError e) {android.util.Log.i("MusicVoice","warm: "+e.getClass().getSimpleName());}
            });
            return true;
        }
        if(pcm==null || reply==null) return true;
        cpu.execute(()->{
            Message answer=Message.obtain();
            Bundle data=new Bundle();
            try {
                float[] f=new float[pcm.length/2];
                for(int i=0;i<f.length;i++) f[i]=(short)((pcm[2*i]&255)|(pcm[2*i+1]<<8))/32768f;
                long start=SystemClock.elapsedRealtime();
                String text=engine==VoiceSettings.FUTO?FutoSpeech.transcribe(this,f,lang):LocalSpeech.transcribe(this,f,lang);
                data.putLong("time",SystemClock.elapsedRealtime()-start);
                data.putLong("pss",Debug.getPss());
                data.putLong("peakRss",peakRssKiB());
                data.putString("text",text);
            } catch(Exception | LinkageError e) {data.putString("error",String.valueOf(e.getMessage()));}
            answer.setData(data);
            try {reply.send(answer);} catch(RemoteException ignored) {}
        });
        return true;
    }));
    @Override public IBinder onBind(Intent i) {return inbox.getBinder();}
    private static long peakRssKiB() {
        try (java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.FileReader("/proc/self/status"))) {
            for(String line;(line=reader.readLine())!=null;) {
                if(line.startsWith("VmHWM:")) return Long.parseLong(line.trim().split("\\s+")[1]);
            }
        } catch(java.io.IOException|NumberFormatException ignored) {}
        return -1;
    }
    @Override public void onDestroy() {
        cpu.shutdownNow();
        super.onDestroy();
        // This service alone occupies :asr. Returning it to the OS releases the native model arenas.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
