package ru.local.voyah.musicsearch;

final class SpotifySearchTimingTest {
    public static void main(String[] args) {
        SpotifySearchTiming warm = new SpotifySearchTiming(100, true);
        warm.queryShown(3000);
        require(!warm.shouldResendInSpotify(3500) && warm.expired(8100), "a warm search keeps its normal deadline");

        SpotifySearchTiming cold = new SpotifySearchTiming(100, false);
        require(!cold.shouldResendInSpotify(10000), "first Spotify frame is too early to resend");
        require(!cold.shouldResendInSpotify(11799), "allow the original intent to reach the search screen");
        require(cold.shouldResendInSpotify(11800), "resend a missed search once");
        require(!cold.shouldResendInSpotify(11900), "do not resend again");
        cold.queryShown(14000);
        cold.queryShown(17000);
        require(!cold.expired(21999) && cold.expired(22000), "start result timeout when the cold query appears");

        SpotifySearchTiming delivered = new SpotifySearchTiming(0, false);
        delivered.queryShown(5000);
        require(!delivered.shouldResendInSpotify(7000) && delivered.expired(13000), "delivery cancels the pending resend");
        SpotifySearchTiming neverOpened = new SpotifySearchTiming(0, false);
        require(neverOpened.expired(15000) && !neverOpened.shouldResendInSpotify(15000), "bounded startup deadline");
        require(14999+SpotifySearchTiming.RESULTS_MS<SpotifySearchTiming.MAX_WAIT_MS, "caller waits through the cold path");
    }

    private static void require(boolean condition,String message) {
        if (!condition) throw new AssertionError(message);
    }
}
