package ru.local.voyah.musicsearch;

import java.util.regex.Pattern;

/**
 * Everything the app assumes about the Spotify client: its package, the view ids it reads
 * and the Russian and English labels it matches. A Spotify update or another interface language
 * that changes any of these stops auto-selection, so they live in one place.
 * Labels are compared after TitleCorrection.norm.
 */
final class SpotifyUi {
    static final String PACKAGE="com.spotify.music";
    /** The client version on which the ids and labels below were confirmed on a head unit. */
    static final String VERIFIED_VERSION="9.2.0.2053";

    // Search screen.
    static final String NAVIGATION_BAR=id("navigation_bar"), SEARCH_RESULTS=id("search_content_recyclerview");
    static final String ROW=id("row_root"), ROW_TITLE=id("title"), ROW_SUBTITLE=id("subtitle");
    // Player bar.
    static final String PLAY_PAUSE=id("play_pause_button"), TRACK_ARTIST=id("track_info_view_subtitle");
    static final String[] TRACK_TITLE={id("track_info_view_title"),id("track_info_view")};
    // Album and playlist pages.
    static final String[] PAGE_PLAY={id("play_button"),id("button_play"),id("play_action")};

    private static final Pattern PLAY_LABEL=Pattern.compile("слушать|воспроизвести|play|play playback");
    private static final Pattern TRACK_TYPE=Pattern.compile("трек|песня|song|track");

    private static String id(String name) {return PACKAGE+":id/"+name;}

    /** The player button offers to start playback, so the player is paused. */
    static boolean offersPlay(String label) {return PLAY_LABEL.matcher(label).matches();}
    /** The player button offers to pause, so a track is playing. */
    static boolean offersPause(String label) {
        return label.startsWith("пауза") || label.startsWith("приостановить") || label.startsWith("pause");
    }
    /** The result row's subtitle starts with the requested kind: track, album or playlist. */
    static boolean isKind(String kind,String subtitle) {
        if(kind.equals("album")) return subtitle.startsWith("альбом") || subtitle.startsWith("album");
        if(kind.equals("playlist")) return subtitle.startsWith("плейлист") || subtitle.startsWith("playlist");
        return subtitle.startsWith("трек") || subtitle.startsWith("песня") || subtitle.startsWith("song") || subtitle.startsWith("track");
    }
    /** The part of a subtitle before the bullet names a track, as in "Трек • Кино". */
    static boolean isTrackType(String type) {return TRACK_TYPE.matcher(type).matches();}
    /** A whole subtitle of a track row followed by its credit. */
    static boolean startsTrackCredit(String subtitle) {
        return subtitle.startsWith("трек ") || subtitle.startsWith("track ") || subtitle.startsWith("song ");
    }
    static boolean isArtistSubtitle(String subtitle) {
        return subtitle.equals("исполнитель") || subtitle.equals("artist");
    }

    /** The installed client's version name, or null when Spotify is absent. */
    static String installedVersion(android.content.Context context) {
        try {
            String name=context.getPackageManager().getPackageInfo(PACKAGE,0).versionName;
            return name==null?"без номера версии":name;
        } catch(android.content.pm.PackageManager.NameNotFoundException absent) {return null;}
    }
    /** A note for the settings screen. Empty when the installed client is the verified one. */
    static String versionNote(String installed) {
        if(installed==null) return "Spotify не найден на этом устройстве.";
        if(installed.equals(VERIFIED_VERSION)) return "";
        return "Автовыбор проверен на Spotify "+VERIFIED_VERSION+", установлен "+installed
                +". Если выбор трека перестал срабатывать, причиной может быть обновление Spotify.";
    }
}
