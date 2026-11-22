package ru.local.voyah.musicsearch;
import java.util.Arrays;
import java.util.List;
final class TitleCorrectionTest {
    public static void main(String[] args) {
        List<TitleCorrection.Row> results=Arrays.asList(
            new TitleCorrection.Row("Queen","Сингл • KVPV"),
            new TitleCorrection.Row("Queen - Original Mix","Трек • KVPV"),
            new TitleCorrection.Row("Queen","Исполнитель"),
            new TitleCorrection.Row("We Will Rock You - Queen","Плейлист"),
            new TitleCorrection.Row("Killer Queen","Трек • Queen"));
        TitleCorrection hit=TitleCorrection.suggest("Queen we build Rakuya",results);
        if(hit==null || !hit.title.equals("We Will Rock You") || !hit.artist.equals("Queen")) throw new AssertionError("Missed cabin recording");
        if(TitleCorrection.suggest("Queen another song",results)!=null) throw new AssertionError("Unrelated song");
        if(TitleCorrection.suggest("The Rakuya we build",results)!=null) throw new AssertionError("Artist absent");
        List<TitleCorrection.Row> show=Arrays.asList(new TitleCorrection.Row("The Show Must Go On","Трек • Queen"));
        TitleCorrection russian=TitleCorrection.suggest("Куин шоу мастгон",show);
        if(russian==null || !russian.title.equals("The Show Must Go On")) throw new AssertionError("Cyrillic artist and title");
        if(!TitleCorrection.firstTrackMatches("Linking part in the end","In the End","Трек • Linkin Park"))
            throw new AssertionError("Two artist typos with exact title");
        if(!TitleCorrection.firstTrackMatches("Lincoln Park in the end","In the End","Трек • Linkin Park"))
            throw new AssertionError("Phonetically close artist with exact second word");
        if(!TitleCorrection.firstTrackMatches("in the enf linkin park","In the End","Трек • Linkin Park"))
            throw new AssertionError("Title typo with exact artist");
        if(TitleCorrection.firstTrackMatches("Linking part in the end","Waiting for the End","Трек • Linkin Park"))
            throw new AssertionError("Different track");
        if(TitleCorrection.firstTrackMatches("Linking part in the end","In the End","Трек • Tommee Profitt"))
            throw new AssertionError("Same title by another artist");
        if(!TitleCorrection.firstTrackMatches("Кинос покойный ночь","Спокойная ночь","Трек • Кино"))
            throw new AssertionError("Shifted word boundary and inflection in a real cabin request");
        if(TitleCorrection.firstTrackMatches("Кинос покойный ночь","Ночь","Трек • Кино"))
            throw new AssertionError("Artist alone and one title word are insufficient");
        if(TitleCorrection.firstTrackMatches("Кинос покойный ночь","Спокойная ночь","Трек • Другая группа"))
            throw new AssertionError("Similar title by a different artist");
        assertRequest("In the key Prodigy Firestarter","Prodigy Firestarter","track");
        assertRequest("Включи, Prodigy Firestarter.","Prodigy Firestarter","track");
        assertRequest("Поставьте трек Firestarter Prodigy","Firestarter Prodigy","track");
        assertRequest("джарвис музыка, поставь альбом Hybrid Theory","Hybrid Theory","album");
        assertRequest("vklyuchi Prodigy Firestarter","Prodigy Firestarter","track");
        assertRequest("Play In the Key of Life","In the Key of Life","track");
        assertRequest("In the Key of Life","In the Key of Life","track");
        assertRequest("In the Key","In the Key","track");
        assertRequest("Don't Play That Song","Don't Play That Song","track");
        if(!SpokenRequest.parse("In the key Prodigy Firestarter").sameSearchAs(
                SpokenRequest.parse("Включи Prodigy Firestarter"))) throw new AssertionError("Duplicate cleaned retry");

        List<TitleCorrection.Row> firestarter=Arrays.asList(
            new TitleCorrection.Row("Firestarter - Edit","Трек • The Prodigy"),
            new TitleCorrection.Row("Firestarter","Сингл • The Prodigy"),
            new TitleCorrection.Row("Firestarter","Трек • The Prodigy"),
            new TitleCorrection.Row("Firestarter","Трек • Torre Florim"));
        assertTrack("In the key Prodigy Firestarter",firestarter,2);
        assertTrack("Поставь Firestater Prodigy",firestarter,2);
        assertTrack("The Prodigy Firestarter Edit",firestarter,0);
        assertTrack("Firestarter",firestarter,TitleCorrection.AMBIGUOUS_TRACK);
        assertTrack("The Prodigy Breathe",firestarter,TitleCorrection.NO_TRACK);
        assertTrack("In the Key of Life",firestarter,TitleCorrection.NO_TRACK);
        assertTrack("Firestarter Prodigy",Arrays.asList(
            new TitleCorrection.Row("Firestarter - 2011 Remaster","Track • The Prodigy"),
            new TitleCorrection.Row("Firestarter","Track • The Prodigy")),1);
        assertTrack("Firestarter Prodigy",Arrays.asList(firestarter.get(2),firestarter.get(2)),0);
        List<TitleCorrection.Row> kino=Arrays.asList(new TitleCorrection.Row("Группа крови","Трек • Кино"));
        assertTrack("Включи кину, группа крови.",kino,0);
        assertTrack("Включи кино в группу крови.",kino,0);
        assertTrack("Queen Under Pressure",Arrays.asList(
            new TitleCorrection.Row("Under Pressure","Трек • Queen, David Bowie"),
            new TitleCorrection.Row("Under Pressure","Трек • Roadrunner United")),0);
        TitleCorrection.Row depeche=new TitleCorrection.Row("Enjoy the Silence","Трек • Depeche Mode");
        TitleCorrection.Row otherRelease=new TitleCorrection.Row("Enjoy The Silence","Трек • Depeche Mode");
        TitleCorrection.Row cover=new TitleCorrection.Row("Enjoy the Silence","Трек • Trevor Something");
        TitleCorrection.Row similar=new TitleCorrection.Row(
                "Here In My Arms (Enjoy The Silence)","Трек • Armin van Buuren, Silver Panda");
        assertTrack("Enjoy the silence",Arrays.asList(depeche,otherRelease,similar,cover),0);
        assertTrack("Enjoy the Silence",Arrays.asList(depeche,cover),TitleCorrection.AMBIGUOUS_TRACK);
        assertTrack("Enjoy the Silence",Arrays.asList(depeche,cover,otherRelease),TitleCorrection.AMBIGUOUS_TRACK);
        assertTrack("Enjoy the Silence Trevor Something",Arrays.asList(depeche,otherRelease,cover),2);
        assertTrack("With kliuchi trek and joy the silence",Arrays.asList(depeche,otherRelease,similar,cover),0);
        assertTrack("Enjoy the Science",Arrays.asList(depeche,otherRelease,cover),TitleCorrection.NO_TRACK);
        assertTrack("Thank",Arrays.asList(new TitleCorrection.Row("Thank You","Трек • Dido")),TitleCorrection.NO_TRACK);
        assertRequest("Включи группу Depeche Mode","Depeche Mode","artist");
        assertRequest("Включи исполнителя Depeche Mode","Depeche Mode","artist");
        assertRequest("Включи трек Enjoy the Silence","Enjoy the Silence","track");
        assertRequest("Включи песню Enjoy the Silence","Enjoy the Silence","track");
        assertRequest("Поставь песня Enjoy the Silence","Enjoy the Silence","track");
        if(SpokenRequest.parse("Depeche Mode").explicitKind ||
                !SpokenRequest.parse("Включи трек Enjoy the Silence").explicitKind)
            throw new AssertionError("Unqualified names and explicit tracks have different result types");
        assertFollowUp("Включи группу", "Depeche Mode","artist");
        assertFollowUp("Включи исполнителя.", "Depeche Mode","artist");
        assertFollowUp("In the key group", "Depeche Mode","artist");
        assertFollowUp("Включи трек", "Enjoy the Silence","track");
        assertFollowUp("Включи рек", "Enjoy the Silence","track");
        assertFollowUp("With kliuchi trek", "Enjoy the Silence","track");
        assertFollowUp("Включи песню", "Enjoy the Silence","track");
        assertRequest("Включи рек Enjoy the Silence","Enjoy the Silence","track");
        assertRequest("With kliuchi trek and joy the silence","Enjoy the silence","track");
        assertRequest("Включи трек And Joy To You","And Joy To You","track");
        assertRequest("With kliuchi trek And Joy","And Joy","track");
        assertRequest("Рек","Рек","track");
        assertRequest("Song","Song","track");
        TitleCorrection.Row artist=new TitleCorrection.Row("Depeche Mode","Исполнитель");
        List<TitleCorrection.Row> mixed=Arrays.asList(depeche,artist,cover,
                new TitleCorrection.Row("Depeche Mode Tribute","Исполнитель"));
        if(TitleCorrection.bestArtist("Depeche Mode",mixed)!=1 ||
                TitleCorrection.bestArtist("Enjoy the Silence",mixed)>=0 ||
                TitleCorrection.bestArtist("Art",mixed)>=0 ||
                TitleCorrection.bestArtist("Depeche Mode",Arrays.asList(artist,artist))>=0)
            throw new AssertionError("Only an unambiguous matching artist card can be opened");
        System.out.println(hit.artist+" / "+hit.title+" score="+hit.score);
    }
    private static void assertRequest(String spoken,String query,String kind) {
        SpokenRequest parsed=SpokenRequest.parse(spoken);
        if(!query.equals(parsed.query) || !kind.equals(parsed.kind)) throw new AssertionError(spoken+" -> "+parsed.query);
    }
    private static void assertTrack(String spoken,List<TitleCorrection.Row> rows,int expected) {
        int result=TitleCorrection.bestTrack(SpokenRequest.parse(spoken).query,rows);
        if(result!=expected) throw new AssertionError(spoken+" -> row "+result+", expected "+expected);
    }
    private static void assertFollowUp(String first,String name,String kind) {
        SpokenRequest initial=SpokenRequest.parse(first);
        SpokenRequest next=initial.withName(name);
        if(!initial.needsName() || next.needsName() || !next.explicitKind ||
                !name.equals(next.query) || !kind.equals(next.kind))
            throw new AssertionError("Lost request type during follow-up: "+first);
    }
}
