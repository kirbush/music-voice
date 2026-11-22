package ru.local.voyah.musicsearch;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SpokenRequest {
    private static final Pattern ACTION=Pattern.compile(
        "(?iu)^(?:джарвис(?:\\s+музыка)?[\\s,]+)?(?:пожалуйста[\\s,]+|please[\\s,]+)?"
        +"(?:включи(?:ть|те)?|постав(?:ь|ить|ьте)|найди(?:те)?|сыграй(?:те)?|играй(?:те)?|"
        +"воспроизведи(?:те)?|play|put\\s+on|find)(?=$|[\\s,:;—–-])");
    private static final Pattern WORD=Pattern.compile("(?iu)^([\\p{L}]+)(?=$|[\\s,:;—–-])");
    private static final Pattern ATTRIBUTION=Pattern.compile(
        "(?iu)^(.+?)\\s+(?:от\\s+)?(?:группы|исполнителя)(?=$|[\\s,:;—–-])(.*)$");
    private static final Pattern POSSIBLE_ATTRIBUTION=Pattern.compile("(?iu)^(.+)\\s+(?:от|by)\\s+(.+)$");
    private static final Pattern TRACK_AFTER_ARTIST=Pattern.compile(
        "(?iu)^(.+?)\\s+(?:трек|песн[яю]|track|song)\\s+(.+)$");
    private static final String[] RUSSIAN_ACTIONS={"включи","поставь","найди","сыграй","играй","воспроизведи"};
    private static final String[][] TYPES={
        {"track","трек","песня","песню","track","song"},
        {"artist","исполнитель","исполнителя","группа","группу","artist","band","group"},
        {"album","альбом","album"},
        {"playlist","плейлист","playlist"}
    };

    final String query, kind, title, artist;
    final boolean explicitKind, literalTitleAllowed;
    private final boolean missingArtist;

    private SpokenRequest(String query,String kind,boolean explicitKind,String title,String artist,
                          boolean literalTitleAllowed,boolean missingArtist) {
        this.query=query;
        this.kind=kind;
        this.explicitKind=explicitKind;
        this.title=title;
        this.artist=artist;
        this.literalTitleAllowed=literalTitleAllowed;
        this.missingArtist=missingArtist;
    }

    static SpokenRequest parse(String text) {
        String name=text.trim().replaceFirst("[\\s.!?,]+$","");
        Matcher action=ACTION.matcher(name);
        String afterAction=quoted(name)?name:stripAction(name,action);
        boolean actionRemoved=!name.equals(afterAction);
        name=afterAction;
        String kind="track";
        boolean explicit=false;
        Matcher type=WORD.matcher(name);
        if(!quoted(name) && type.find()) {
            String rest=leadingSeparators(name.substring(type.end()));
            String token=TitleCorrection.norm(type.group(1));
            String detected=mediaKind(token,actionRemoved);
            if(detected!=null && isTypePrefix(token,rest,actionRemoved)) {
                name=rest;
                kind=detected;
                explicit=true;
            }
        }

        String query=clean(name);
        if(!kind.equals("track") || quoted(name))
            return new SpokenRequest(query,kind,explicit,"","",false,false);
        Matcher attribution=ATTRIBUTION.matcher(name);
        if(attribution.find()) {
            String title=clean(attribution.group(1));
            String artist=clean(leadingSeparators(attribution.group(2)));
            if(!title.isEmpty())
                return new SpokenRequest(join(title,artist),kind,explicit,title,artist,false,artist.isEmpty());
        }
        Matcher possible=POSSIBLE_ATTRIBUTION.matcher(name);
        if(possible.find())
            return new SpokenRequest(query,kind,explicit,clean(possible.group(1)),clean(possible.group(2)),true,false);
        Matcher reversed=TRACK_AFTER_ARTIST.matcher(name);
        if(actionRemoved && reversed.find())
            return new SpokenRequest(query,kind,explicit,clean(reversed.group(2)),clean(reversed.group(1)),true,false);
        return new SpokenRequest(query,kind,explicit,"","",false,false);
    }

    private static String stripAction(String name,Matcher exact) {
        if(exact.find() && (exact.end()<name.length() || !name.equalsIgnoreCase("play")))
            return leadingSeparators(name.substring(exact.end()));

        // A transliterated Russian verb is reliable on its own. An edit or an extra
        // filler word requires a following media-type token before we consume it.
        String candidate=name.replaceFirst("(?iu)^(?:with|please|пожалуйста)[\\s,]+","");
        Matcher word=WORD.matcher(candidate);
        if(!word.find()) return name;
        String key=controlKey(word.group(1));
        String rest=leadingSeparators(candidate.substring(word.end()));
        Matcher next=WORD.matcher(rest);
        boolean typeFollows=next.find() && mediaKind(next.group(1),true)!=null;
        for(String verb:RUSSIAN_ACTIONS) {
            String expected=controlKey(verb);
            if(key.equals(expected) && (candidate.equals(name) || typeFollows)) return rest;
            if(typeFollows && key.length()>=4 && TitleCorrection.editDistance(key,expected)==1) return rest;
        }
        return name;
    }

    private static String mediaKind(String word,boolean allowRepair) {
        for(String[] aliases:TYPES) {
            for(int i=1;i<aliases.length;i++) {
                if(aliases[i].equalsIgnoreCase(word)) return aliases[0];
            }
        }
        if(!allowRepair || word.length()<3 || word.length()>20) return null;
        String key=controlKey(word), found=null;
        int best=2;
        for(String[] aliases:TYPES) {
            int edits=2;
            for(int i=1;i<aliases.length;i++) {
                String alias=aliases[i];
                if(alias.charAt(0)>127)
                    edits=Math.min(edits,TitleCorrection.editDistance(key,controlKey(alias)));
            }
            if(edits<best) {
                best=edits;
                found=aliases[0];
            } else if(edits==best && best<2) found=null;
        }
        return found;
    }

    private static boolean isTypePrefix(String token,String rest,boolean afterAction) {
        if(rest.isEmpty()) return afterAction;
        // The nominative noun with more words can be the start of a literal name.
        if(token.equals("группа") || token.equals("песня") || token.equals("исполнитель")) return false;
        if(!afterAction && token.charAt(0)<128) return false;
        return !(token.equals("song") || token.equals("track")) || !rest.matches("^\\d+(?:\\s.*)?");
    }

    private static String controlKey(String text) {
        return TitleCorrection.sound(TitleCorrection.norm(text)).replace("yu","u").replace("iu","u").replace("ju","u");
    }
    private static String leadingSeparators(String text) {return text.replaceFirst("^[\\s,:;—–-]+","");}
    private static boolean quoted(String text) {
        if(text.length()<=2) return false;
        char start=text.charAt(0);
        char end=start=='«'?'»':start=='“'?'”':start=='"'?'"':'\0';
        return end!='\0' && text.indexOf(end,1)==text.length()-1;
    }
    private static String clean(String text) {
        String name=text.replaceAll("[,«»„“”\"]"," ").replaceAll("\\s+"," ").trim();
        return TitleCorrection.norm(name).isEmpty()?"":name;
    }
    private static String join(String title,String artist) {
        return artist.isEmpty()?title:title+" "+artist;
    }

    boolean hasTrackFields() {return !title.isEmpty() && !artist.isEmpty();}
    boolean needsName() {return query.isEmpty() || missingArtist;}

    String namePrompt() {
        if(missingArtist) return "Назовите исполнителя";
        switch(kind) {
            case "artist": return "Назовите исполнителя или группу";
            case "album": return "Назовите альбом";
            case "playlist": return "Назовите плейлист";
            default:
                if(query.isEmpty()) return "Назовите трек";
                return explicitKind?"Повторите название трека":"Уточните трек или исполнителя";
        }
    }

    SpokenRequest withName(String heard) {
        SpokenRequest name=parse(heard);
        if(missingArtist) {
            String performer=name.artist.isEmpty()?name.query:name.artist;
            return new SpokenRequest(join(title,performer),kind,explicitKind,title,performer,false,performer.isEmpty());
        }
        return explicitKind?new SpokenRequest(name.query,kind,true,name.title,name.artist,name.literalTitleAllowed,name.missingArtist):name;
    }

    boolean sameSearchAs(SpokenRequest other) {
        return kind.equals(other.kind) && explicitKind==other.explicitKind &&
                TitleCorrection.norm(query).equals(TitleCorrection.norm(other.query)) &&
                TitleCorrection.norm(artist).equals(TitleCorrection.norm(other.artist));
    }
}
