package ru.local.voyah.musicsearch;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.List;

/** Route startup through the already enabled, system-bound accessibility service. */
public final class ListenerEvents extends BroadcastReceiver {
    static final String STOP = "ru.local.voyah.musicsearch.STOP_LISTENER";
    private static final long START_DEBOUNCE_MS = 2500;
    private static long nextStart;

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (STOP.equals(action)) {
            WakeService.disable(context);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                   Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            requestStart(context, false);
        }
    }

    static void requestStart(Context context, boolean mediaStarted) {
        if (SearchService.instance == null || WakeService.active) return;
        VoiceSettings settings = new VoiceSettings(context);
        if (!settings.listeningEnabled() ||
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                !ModelStore.get(context, settings.engine()).ready) return;
        if (!mediaStarted && !context.getSystemService(PowerManager.class).isInteractive()) return;
        long now = SystemClock.elapsedRealtime();
        if (now < nextStart) return;
        nextStart = now + START_DEBOUNCE_MS;
        try {
            context.startForegroundService(new Intent(context, WakeService.class).setAction(WakeService.AUTOMATIC));
        } catch (RuntimeException blocked) {
            WakeService.showStartReminder(context);
        }
    }

    static final class Monitor implements AutoCloseable {
        private final Context context;
        private boolean mediaPlaying;
        private boolean playbackRegistered;
        private final BroadcastReceiver screen = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                requestStart(context, false);
            }
        };
        private final AudioManager.AudioPlaybackCallback playback = new AudioManager.AudioPlaybackCallback() {
            @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configurations) {
                boolean playing = false;
                for (AudioPlaybackConfiguration config : configurations) {
                    // Android gives ordinary apps only active configurations in this callback.
                    if (config.getAudioAttributes().getUsage() == AudioAttributes.USAGE_MEDIA) {
                        playing = true;
                        break;
                    }
                }
                boolean started = playing && !mediaPlaying;
                mediaPlaying = playing;
                if (started) requestStart(context, true);
            }
        };

        Monitor(Context context, Handler main) {
            this.context = context;
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            context.registerReceiver(screen, filter);
            try {
                context.getSystemService(AudioManager.class).registerAudioPlaybackCallback(playback, main);
                playbackRegistered=true;
            } catch(SecurityException unavailable) {
                WakeService.event(context,"Наблюдение за воспроизведением недоступно");
            }
        }

        @Override public void close() {
            if(playbackRegistered) context.getSystemService(AudioManager.class).unregisterAudioPlaybackCallback(playback);
            context.unregisterReceiver(screen);
        }
    }
}
