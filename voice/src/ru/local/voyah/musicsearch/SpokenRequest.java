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
        "(?iu)^(in\\s+the\\s+key|vkl(?:y|j)?uch[iy]|postav)[\\s,:—–-]+");
    private static final Pattern TYPE=Pattern.compile(
        "(?iu)^(альбом|album|плейлист|playlist|исполнител[ья]|групп[ау]|artist|band|group|песн[яю]|трек|song|track)"
        +"(?:[\\s,:—–-]+|$)");

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
        if(action.find() && (!action.group().equalsIgnoreCase("play") || action.end()<name.length())) {
            name=name.substring(action.end());
            actionRemoved=true;
        } else {
            Matcher alias=MISHEARD_ACTION.matcher(name);
            if(alias.find()) {
                String rest=name.substring(alias.end());
                String normalized=TitleCorrection.norm(rest);
                if(!TitleCorrection.norm(alias.group(1)).equals("in the key")
                        || TYPE.matcher(normalized).matches()
                        || (normalized.contains(" ") && !normalized.matches("^(?:of|by|to|with)\\b.*"))) {
                    name=rest;
                    actionRemoved=true;
                }
            }
        }
        String kind="track";
        Matcher type=TYPE.matcher(name);
        boolean explicitKind=type.find() && (type.end()<name.length() || actionRemoved);
        if(explicitKind) {
            switch(type.group(1).toLowerCase(Locale.ROOT)) {
                case "альбом": case "album": kind="album"; break;
                case "плейлист": case "playlist": kind="playlist"; break;
                case "исполнитель": case "исполнителя": case "группа": case "группу":
                case "artist": case "band": case "group": kind="artist"; break;
                default: break;
            }
            name=name.substring(type.end());
        }
        name=name.replaceAll("(?iu)\\s+(?:от группы|от исполнителя|группы|исполнителя)\\s+"," ")
                .replaceAll("[,«»„“”\"]"," ").replaceAll("\\s+"," ").trim();
        return new SpokenRequest(name,kind,explicitKind);
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
