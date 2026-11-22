package ru.local.voyah.musicsearch;

import android.content.Context;
import android.content.SharedPreferences;

final class VoiceSettings {
    static final String LANGUAGE="language", DELAY="manual_delay_ms", VOLUME="tone_volume";
    static final String ENGINE="asr_engine";
    static final int BASE=0, FUTO=1;
    static final int DEFAULT_DELAY=2500, DEFAULT_VOLUME=65;
    private final SharedPreferences values;

    VoiceSettings(Context context) { values=context.getSharedPreferences("voice_settings",Context.MODE_PRIVATE); }
    String phrase() {return values.getString("wake_phrase","Алиса");}
    void setPhrase(String phrase) {values.edit().putString("wake_phrase",phrase).apply();}
    int get(String key,int fallback) { return values.getInt(key,fallback); }
    void set(String key,int value) { values.edit().putInt(key,value).apply(); }
    int engine() { return get(ENGINE,BASE)==FUTO?FUTO:BASE; }
    boolean listeningEnabled() {return values.getBoolean("listening_enabled",true);}
    void setListeningEnabled(boolean enabled) {values.edit().putBoolean("listening_enabled",enabled).apply();}
    String language() {
        switch(get(LANGUAGE,0)) {
            case 1: return "ru";
            case 2: return "en";
            default: return "";
        }
    }
}
