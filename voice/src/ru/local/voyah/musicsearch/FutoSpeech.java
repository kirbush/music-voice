package ru.local.voyah.musicsearch;

import android.content.Context;
import java.nio.charset.StandardCharsets;

final class FutoSpeech {
    static { System.loadLibrary("musicvoice_futo"); }
    // SpeechWorker owns the native context. Its process exits when the last request unbinds.
    private static long context;

    static String transcribe(Context app, float[] samples, String language) {
        if (context == 0) {
            ModelStore files = ModelStore.get(app, VoiceSettings.FUTO);
            if (!files.ready) throw new IllegalStateException("Сначала скачайте FUTO‑74");
            context = load(files.file("futo/multilingual-74.bin").getAbsolutePath());
        }
        int languageId = "ru".equals(language) ? 1 : "en".equals(language) ? 2 : 0;
        return new String(decode(context, samples, languageId), StandardCharsets.UTF_8).trim();
    }

    private static native long load(String path);
    private static native byte[] decode(long context, float[] samples, int language);
}
