package ru.local.voyah.musicsearch;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import com.k2fsa.sherpa.onnx.*;

final class LocalSpeech {
    private static OfflineRecognizer recognizer;

    static synchronized String transcribe(Context context, float[] samples) {
        return transcribe(context,samples,"");
    }
    static synchronized String transcribe(Context context, float[] samples,String language) {
        if(recognizer==null) {
            ModelStore files=ModelStore.get(context,VoiceSettings.BASE);
            if(!files.ready) throw new IllegalStateException("Сначала скачайте модели");
            long start=SystemClock.elapsedRealtime();
            OfflineRecognizerConfig cfg=new OfflineRecognizerConfig();
            cfg.getFeatConfig().setSampleRate(16000);
            cfg.getFeatConfig().setFeatureDim(80);
            OfflineModelConfig model=cfg.getModelConfig();
            model.setTokens(files.file("asr/base-tokens.txt").getAbsolutePath());
            model.setModelType("whisper");
            model.setNumThreads(3);
            OfflineWhisperModelConfig whisper=model.getWhisper();
            whisper.setEncoder(files.file("asr/base-encoder.int8.onnx").getAbsolutePath());
            whisper.setDecoder(files.file("asr/base-decoder.int8.onnx").getAbsolutePath());
            whisper.setLanguage("");
            whisper.setTask("transcribe");
            recognizer=new OfflineRecognizer(null,cfg);
            Log.i("MusicVoice","modelLoadMs="+(SystemClock.elapsedRealtime()-start));
        }
        long start=SystemClock.elapsedRealtime();
        OfflineStream stream=recognizer.createStream();
        try {
            if(language.isEmpty()) stream.setOption("language_candidates","ru,en");
            else stream.setOption("language",language);
            stream.acceptWaveform(samples,16000);
            recognizer.decode(stream);
            String text=recognizer.getResult(stream).getText().trim();
            Log.i("MusicVoice","decodeMs="+(SystemClock.elapsedRealtime()-start)+" seconds="+samples.length/16000.0+" pssKiB="+android.os.Debug.getPss());
            return text;
        } finally {stream.release();}
    }
}
