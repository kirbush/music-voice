package ru.local.voyah.musicsearch;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.SystemClock;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.OnlineStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CancellationException;

/**
 * The microphone side of the listener. It opens the cabin input, watches for the wake phrase,
 * records voice samples for enrollment and hands each detected command to WakeService.
 * Everything here runs on the listener thread.
 */
final class KeywordLoop {
    static final class MicrophoneUnavailable extends IllegalStateException {
        MicrophoneUnavailable(String message) {super(message);}
        MicrophoneUnavailable(String message,Throwable cause) {super(message,cause);}
    }
    private static final int RATE=16000;
    // The input that delivers speech on the tested head unit: warm up through the call path, record as CAMCORDER.
    private static final String ROUTE="primeback";
    // Every attempt briefly switches the audio mode again, so recovery is bounded.
    private static final long[] RECOVERY_DELAYS_MS={1000,4000,15000,45000};
    private static final long STABLE_SESSION_MS=60000;
    private final WakeService host;

    KeywordLoop(WakeService host) {this.host=host;}

    /** Listen until the service stops. Throws when the microphone cannot be recovered or the detector fails. */
    void run() throws Exception {
        for(int attempt=0;;) {
            long started=SystemClock.elapsedRealtime();
            try {session(ROUTE); return;}
            catch(MicrophoneUnavailable lost) {
                if(host.stopping) return;
                if(SystemClock.elapsedRealtime()-started>=STABLE_SESSION_MS) attempt=0;
                if(attempt>=RECOVERY_DELAYS_MS.length) throw lost;
                WakeService.event(host,"mic recovery attempt="+(attempt+1));
                host.state("Восстанавливаю вход микрофона");
                long resume=SystemClock.elapsedRealtime()+RECOVERY_DELAYS_MS[attempt++];
                while(!host.stopping && SystemClock.elapsedRealtime()<resume) SystemClock.sleep(200);
                if(host.stopping) return;
            }
        }
    }

    private AudioRecord recorder(int source) {
        AudioFormat fmt=new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
        AudioRecord r=new AudioRecord.Builder().setAudioSource(source).setAudioFormat(fmt)
                .setPrivacySensitive(false).setBufferSizeInBytes(Math.max(8192,AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)*2)).build();
        for(AudioDeviceInfo d:host.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS)) {
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

    private void session(String route) throws Exception {
        AudioRecord r=null;
        AcousticEchoCanceler echo=null;
        KeywordSpotter kws=null;
        OnlineStream stream=null;
        AudioManager am=host.getSystemService(AudioManager.class);
        boolean priming=false;
        try {
            VoiceSettings settings=new VoiceSettings(host);
            String phrase=settings.phrase();
            kws=WakePhrase.create(host,phrase);
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
                host.echoStatus="AEC: "+(echo!=null && echo.getEnabled()?"включён":"недоступен для этого входа");
            } catch(RuntimeException e) { host.echoStatus="AEC: "+e.getClass().getSimpleName(); }
            WakeService.event(host,host.echoStatus);
            host.state(host.idleText());
            short[] buf=new short[640];
            long since=SystemClock.elapsedRealtime(), audit=since, voiced=since;
            int frames=0,peak=0;
            while(!host.stopping) {
                String selected=settings.phrase();
                if(!selected.equals(phrase)) {
                    stream.release();stream=null;kws.release();kws=null;
                    kws=WakePhrase.create(host,selected);
                    stream=kws.createStream("");
                    phrase=selected;profile=loadProfile(phrase);
                    if(!host.commandActive()) host.state(host.idleText());
                }
                if(WakeService.enrollmentRequested) {
                    try {
                        host.cancelCommand();
                        profile=enroll(r,phrase);
                        host.state("Голосовой образец сохранён. "+host.idleText());
                    } catch(Exception e) {host.state("Обучение: "+e.getMessage());}
                    finally {WakeService.enrollmentRequested=false;}
                    stream.release();stream=kws.createStream("");voiced=SystemClock.elapsedRealtime();
                    continue;
                }
                if(host.captureClarification(r)) {
                    stream.release(); stream=kws.createStream("");
                    if(profile!=null) profile.reset();
                    voiced=SystemClock.elapsedRealtime(); audit=voiced; frames=0; peak=0;
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
                        WakeService.event(host,"wake via="+(hit?"text":"voice"));
                        host.handleCommand(r,am);
                        stream.release();
                        stream=kws.createStream("");
                        if(profile!=null) profile.reset();
                        voiced=SystemClock.elapsedRealtime(); audit=voiced; frames=0; peak=0;
                    }
                } else SystemClock.sleep(12);
                if(now-audit>5000) {
                    host.inputStats="input="+route+" frames="+frames+" peak="+peak;
                    WakeService.event(host,host.inputStats);
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
        File file=WakeService.profileFile(host,phrase);
        if(!file.isFile()) return null;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            return VoiceProfile.read(in);
        } catch(IOException e) {WakeService.event(host,"Образец недоступен");return null;}
    }

    private VoiceProfile enroll(AudioRecord r,String phrase) throws Exception {
        float[][][] examples=new float[3][][];
        for(int i=0;i<examples.length;i++) {
            if(host.stopping || !WakeService.enrollmentRequested) throw new CancellationException("отменено");
            host.state("После сигнала скажите «"+phrase+"» · "+(i+1)+" из 3");
            ToneGenerator tone=new ToneGenerator(AudioManager.STREAM_MUSIC,new VoiceSettings(host).get(VoiceSettings.VOLUME,65));
            try {tone.startTone(ToneGenerator.TONE_PROP_ACK,180);SystemClock.sleep(230);} finally {tone.release();}
            CabinMic.drain(r);
            float[] audio=CabinMic.read(r,()->{},()->host.stopping || !WakeService.enrollmentRequested);
            examples[i]=VoiceProfile.features(audio);
        }
        VoiceProfile result=VoiceProfile.train(examples);
        android.util.AtomicFile dest=new android.util.AtomicFile(WakeService.profileFile(host,phrase));
        FileOutputStream raw=dest.startWrite();
        try {
            DataOutputStream out=new DataOutputStream(new BufferedOutputStream(raw));
            result.write(out);out.flush();dest.finishWrite(raw);
        } catch(IOException e) {dest.failWrite(raw);throw e;}
        return result;
    }
}
