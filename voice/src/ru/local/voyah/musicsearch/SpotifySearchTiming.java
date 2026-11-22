package ru.local.voyah.musicsearch;

/** Separate Spotify startup time from the time allowed to inspect its search results. */
final class SpotifySearchTiming {
    static final long RESULTS_MS = 8000;
    static final long STARTUP_MS = 15000;
    static final long MAX_WAIT_MS = STARTUP_MS + RESULTS_MS + 1000;
    private static final long RETRY_DELAY_MS = 1800;

    private long deadline;
    private long firstSpotifyWindow = -1;
    private boolean waitingForColdSearch;
    private boolean resent;

    SpotifySearchTiming(long now, boolean spotifyVisible) {
        waitingForColdSearch = !spotifyVisible;
        deadline = now + (spotifyVisible ? RESULTS_MS : STARTUP_MS);
    }

    boolean expired(long now) {return now >= deadline;}

    void queryShown(long now) {
        if (!waitingForColdSearch) return;
        waitingForColdSearch = false;
        deadline = now + RESULTS_MS;
    }

    boolean shouldResendInSpotify(long now) {
        if (!waitingForColdSearch || resent || expired(now)) return false;
        if (firstSpotifyWindow < 0) firstSpotifyWindow = now;
        if (now - firstSpotifyWindow < RETRY_DELAY_MS) return false;
        resent = true;
        return true;
    }
}
