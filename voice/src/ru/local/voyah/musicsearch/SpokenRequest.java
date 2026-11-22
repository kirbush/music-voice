package ru.local.voyah.musicsearch;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SpokenRequest {
    private static final Pattern ACTION=Pattern.compile(
        "(?iu)^(?:джарвис(?:\\s+музыка)?[\\s,]+)?"
        +"(?:включи(?:ть|те)?|постав(?:ь|ить|ьте)|найди(?:те)?|сыграй(?:те)?|играй(?:те)?|play)"
        +"\\b[\\s,:—–-]*");
    private static final Pattern MISHEARD_ACTION=Pattern.compile(
        "(?iu)^(in\\s+the\\s+key|with\\s+kl(?:iu|yu|ju|u)ch[iy]|vkl(?:i|y|j)?uch[iy]|postav)[\\s,:—–-]+");
    private static final Pattern TYPE=Pattern.compile(
        "(?iu)^(альбом|album|плейлист|playlist|исполнител[ья]|групп[ау]|artist|band|group|песн[яю]|трек|song|track)"
        +"(?:[\\s,:—–-]+|$)");
    private static final Pattern MISHEARD_TRACK=Pattern.compile("(?iu)^(рек|trek)(?:[\\s,:—–-]+|$)");
    private static final Pattern SPLIT_ENJOY=Pattern.compile("(?iu)^and\\s+joy(?=\\s+\\p{L})");

    final String query, kind;
    final boolean explicitKind;
    private SpokenRequest(String query,String kind,boolean explicitKind) {
        this.query=query;
        this.kind=kind;
        this.explicitKind=explicitKind;
    }

    static SpokenRequest parse(String text) {
        String name=text.replaceAll("^[\\s«„“\"]+|[\\s.!?,»”\"]+$","").trim();
        Matcher action=ACTION.matcher(name);
        boolean actionRemoved=false;
        boolean misheardAction=false;
        if(action.find() && (!action.group().equalsIgnoreCase("play") || action.end()<name.length())) {
            name=name.substring(action.end());
            actionRemoved=true;
        } else {
            Matcher alias=MISHEARD_ACTION.matcher(name);
            if(alias.find() && acceptsAlias(alias.group(1),name.substring(alias.end()))) {
                name=name.substring(alias.end());
                actionRemoved=true;
                misheardAction=true;
            }
        }
        String kind="track";
        Matcher type=TYPE.matcher(name);
        boolean explicitKind=type.find() && (type.end()<name.length() || actionRemoved);
        boolean misheardTrack=false;
        if(explicitKind) {
            switch(type.group(1).toLowerCase(Locale.ROOT)) {
                case "альбом": case "album": kind="album"; break;
                case "плейлист": case "playlist": kind="playlist"; break;
                case "исполнитель": case "исполнителя": case "группа": case "группу":
                case "artist": case "band": case "group": kind="artist"; break;
                default: break;
            }
            name=name.substring(type.end());
        } else if(actionRemoved) {
            Matcher track=MISHEARD_TRACK.matcher(name);
            if(track.find()) {
                explicitKind=true;
                misheardTrack=true;
                name=name.substring(track.end());
            }
        }
        name=name.replaceAll("(?iu)\\s+(?:от группы|от исполнителя|группы|исполнителя)\\s+"," ")
                .replaceAll("[,«»„“”\"]"," ").replaceAll("\\s+"," ").trim();
        if(explicitKind && kind.equals("track") && (misheardAction || misheardTrack))
            name=SPLIT_ENJOY.matcher(name).replaceFirst("Enjoy");
        return new SpokenRequest(name,kind,explicitKind);
    }

    private static boolean acceptsAlias(String action,String rest) {
        String prefix=TitleCorrection.norm(action);
        String normalized=TitleCorrection.norm(rest);
        if(prefix.startsWith("with "))
            return TYPE.matcher(normalized).lookingAt() || MISHEARD_TRACK.matcher(normalized).lookingAt();
        if(!prefix.equals("in the key")) return true;
        return TYPE.matcher(normalized).matches() || MISHEARD_TRACK.matcher(normalized).matches() ||
                (normalized.contains(" ") && !normalized.matches("^(?:of|by|to|with)\\b.*"));
    }

    boolean needsName() {return query.isEmpty() && explicitKind;}

    String namePrompt() {
        switch(kind) {
            case "artist": return "Назовите исполнителя или группу";
            case "album": return "Назовите альбом";
            case "playlist": return "Назовите плейлист";
            default: return "Назовите трек";
        }
    }

    SpokenRequest withName(String heard) {
        SpokenRequest name=parse(heard);
        return explicitKind?new SpokenRequest(name.query,kind,true):name;
    }

    boolean sameSearchAs(SpokenRequest other) {
        return kind.equals(other.kind) && explicitKind==other.explicitKind &&
                TitleCorrection.norm(query).equals(TitleCorrection.norm(other.query));
    }
}
