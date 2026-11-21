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
        "(?iu)^(альбом|album|плейлист|playlist|исполнителя|группу|artist|песню|трек|song|track)[\\s,:—–-]+");

    final String query, kind;
    private SpokenRequest(String query,String kind) {this.query=query;this.kind=kind;}

    static SpokenRequest parse(String text) {
        String name=text.replaceAll("^[\\s«„“\"]+|[\\s.!?,»”\"]+$","").trim();
        Matcher action=ACTION.matcher(name);
        if(action.find() && (!action.group().equalsIgnoreCase("play") || action.end()<name.length())) {
            name=name.substring(action.end());
        } else {
            Matcher alias=MISHEARD_ACTION.matcher(name);
            if(alias.find()) {
                String rest=name.substring(alias.end());
                String normalized=TitleCorrection.norm(rest);
                if(!TitleCorrection.norm(alias.group(1)).equals("in the key")
                        || (normalized.contains(" ") && !normalized.matches("^(?:of|by|to|with)\\b.*"))) {
                    name=rest;
                }
            }
        }
        String kind="track";
        Matcher type=TYPE.matcher(name);
        if(type.find()) {
            switch(type.group(1).toLowerCase(Locale.ROOT)) {
                case "альбом": case "album": kind="album"; break;
                case "плейлист": case "playlist": kind="playlist"; break;
                case "исполнителя": case "группу": case "artist": kind="artist"; break;
                default: break;
            }
            name=name.substring(type.end());
        }
        name=name.replaceAll("(?iu)\\s+(?:от группы|от исполнителя|группы|исполнителя)\\s+"," ")
                .replaceAll("[,«»„“”\"]"," ").replaceAll("\\s+"," ").trim();
        return new SpokenRequest(name,kind);
    }

    boolean sameSearchAs(SpokenRequest other) {
        return kind.equals(other.kind) && TitleCorrection.norm(query).equals(TitleCorrection.norm(other.query));
    }
}
