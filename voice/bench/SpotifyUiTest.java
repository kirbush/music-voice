package ru.local.voyah.musicsearch;

final class SpotifyUiTest {
    public static void main(String[] args) {
        require(SpotifyUi.ROW.equals("com.spotify.music:id/row_root"),"row id");
        require(SpotifyUi.TRACK_TITLE[0].equals("com.spotify.music:id/track_info_view_title"),"track title id");

        // Player button labels as Spotify exposes them in Russian and English.
        for(String label:new String[]{"слушать","воспроизвести","play","play playback"})
            require(SpotifyUi.offersPlay(label),"paused player: "+label);
        require(!SpotifyUi.offersPlay("пауза") && !SpotifyUi.offersPlay("playlist"),"playing player read as paused");
        for(String label:new String[]{"пауза","приостановить воспроизведение","pause playback"})
            require(SpotifyUi.offersPause(label),"playing player: "+label);
        require(!SpotifyUi.offersPause("play") && !SpotifyUi.offersPause("слушать"),"paused player read as playing");

        // Result rows are typed by the start of their subtitle.
        require(SpotifyUi.isKind("track",norm("Трек • Кино")) && SpotifyUi.isKind("track",norm("Song • Queen")),"track row");
        require(SpotifyUi.isKind("album",norm("Альбом • Кино")) && SpotifyUi.isKind("album",norm("Album • Queen")),"album row");
        require(SpotifyUi.isKind("playlist",norm("Плейлист")) && SpotifyUi.isKind("playlist",norm("Playlist")),"playlist row");
        require(!SpotifyUi.isKind("album",norm("Трек • Кино")) && !SpotifyUi.isKind("track",norm("Альбом • Кино"))
                && !SpotifyUi.isKind("playlist",norm("Исполнитель")),"row of another kind");
        require(SpotifyUi.isTrackType("трек") && SpotifyUi.isTrackType("song") && !SpotifyUi.isTrackType("трек кино"),"track type");
        require(SpotifyUi.isArtistSubtitle(norm("Исполнитель")) && SpotifyUi.isArtistSubtitle("artist")
                && !SpotifyUi.isArtistSubtitle(norm("Трек • Кино")),"artist card");

        require(SpotifyUi.versionNote(SpotifyUi.VERIFIED_VERSION).isEmpty(),"verified client needs no note");
        require(SpotifyUi.versionNote("9.3.1.100").contains("9.3.1.100")
                && SpotifyUi.versionNote("9.3.1.100").contains(SpotifyUi.VERIFIED_VERSION),"other client version");
        require(!SpotifyUi.versionNote(null).isEmpty(),"missing client");

        // Album and playlist rows must contain every requested word.
        require(TitleCorrection.wordsMatch("кино группа крови","Группа крови","Альбом • Кино"),"exact album");
        require(TitleCorrection.wordsMatch("linkin park meteara","Meteora","Альбом • Linkin Park"),"one near word");
        require(!TitleCorrection.wordsMatch("linkin perk meteara","Meteora","Альбом • Linkin Park"),"two near words");
        require(!TitleCorrection.wordsMatch("queen innuendo live","Innuendo","Альбом • Queen"),"missing word");
        require(!TitleCorrection.wordsMatch("meteara","Meteora","Альбом • Linkin Park"),"no exact word");
        require(!TitleCorrection.wordsMatch("abc","abd","Альбом • Queen"),"short words need an exact match");
        System.out.println("PASS Spotify labels / row kinds / client version note / album word match");
    }
    private static String norm(String text) {return TitleCorrection.norm(text);}
    private static void require(boolean condition,String what) {
        if(!condition) throw new AssertionError("Unexpected Spotify UI rule: "+what);
    }
}
