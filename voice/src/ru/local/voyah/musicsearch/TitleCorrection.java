package ru.local.voyah.musicsearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Use only names visible in Spotify's own results. The corrected query still needs a matching track row. */
final class TitleCorrection {
    static final int NO_TRACK=-1, AMBIGUOUS_TRACK=-2;
    static final class Row {
        final String title, subtitle;
        Row(String title,String subtitle) {this.title=title; this.subtitle=subtitle;}
    }
    final String title, artist;
    final double score;
    private TitleCorrection(String title,String artist,double score) {
        this.title=title; this.artist=artist; this.score=score;
    }
    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("[^\\p{L}\\p{Nd}]+"," ").trim();
    }
    private static String consonants(String s) {
        return s.replace("ph","f").replace("ck","k").replace('q','k').replace('c','k')
                .replaceAll("[aeiouаеиоуыэюяьъ ]","").replaceAll("(.)\\1+","$1");
    }
    // Transliterate the ASR spelling before comparing an artist or title across scripts.
    private static String sound(String s) {
        String ru="абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
        String[] latin={"a","b","v","g","d","e","e","zh","z","i","y","k","l","m","n","o","p","r","s","t","u","f","h","ts","ch","sh","sh","","i","","e","yu","ya"};
        StringBuilder out=new StringBuilder();
        for(char ch:s.toCharArray()) {
            int i=ru.indexOf(ch);
            if(i<0) out.append(ch); else out.append(latin[i]);
        }
        return out.toString().replace("qu","ku").replace("ee","i").replace("ow","ou");
    }
    static String artistTail(String q,String artist) {
        String a=norm(artist), full=norm(q);
        if(a.isEmpty()) return "";
        if(full.startsWith(a+" ")) return full.substring(a.length()+1);
        if(full.endsWith(" "+a)) return full.substring(0,full.length()-a.length()-1);
        String[] words=full.split(" ",2);
        if(words.length==2 && a.indexOf(' ')<0 && a.length()>=4 &&
                similarity(sound(words[0]),sound(a))>=0.85) return words[1];
        return "";
    }
    static String broader(String spoken,List<String> artists) {
        for(String artist:artists) {
            String tail=artistTail(spoken,artist);
            if(tail.isEmpty()) continue;
            String[] words=tail.split(" ");
            if(words.length<2) continue;
            // Retain the artist and a reliable title word to let Spotify retrieve candidates.
            String word=words[0];
            if((word.equals("the") || word.equals("a")) && words.length>=3) word=words[1];
            word=sound(word);
            if(word.length()>=5) word=word.substring(0,3);
            return artist+" "+word;
        }
        return "";
    }
    private static double similarity(String a,String b) {
        if(a.isEmpty() || b.isEmpty()) return 0;
        int[] last=new int[b.length()+1];
        for(int j=0;j<last.length;j++) last[j]=j;
        for(int i=1;i<=a.length();i++) {
            int diagonal=last[0]; last[0]=i;
            for(int j=1;j<=b.length();j++) {
                int above=last[j];
                last[j]=Math.min(Math.min(above+1,last[j-1]+1),diagonal+(a.charAt(i-1)==b.charAt(j-1)?0:1));
                diagonal=above;
            }
        }
        return 1.0-last[b.length()]/(double)Math.max(a.length(),b.length());
    }
    private static boolean nearName(String a,String b) {
        if(a.equals(b)) return true;
        int length=Math.max(a.length(),b.length());
        if(Math.min(a.length(),b.length())<4 || Math.abs(a.length()-b.length())>2) return false;
        double sim=similarity(a,b);
        long edits=Math.round((1-sim)*length);
        return edits==1 || (edits==2 && sim>=0.78);
    }
    private static boolean nearArtist(String heard,String named) {
        if(nearName(heard.replace(" ",""),named.replace(" ",""))) return true;
        String[] a=heard.split(" "),b=named.split(" ");
        if(a.length!=b.length || a.length<2) return false;
        int same=0,changed=0;
        for(int i=0;i<a.length;i++) {
            if(a[i].equals(b[i])) {
                if(a[i].length()>=3 && !a[i].equals("the") && !a[i].equals("and")) same++;
                continue;
            }
            if(++changed>1 || Math.min(a[i].length(),b[i].length())<4) return false;
            String x=sound(a[i]), y=sound(b[i]);
            String cx=consonants(x),cy=consonants(y);
            if(Math.min(cx.length(),cy.length())<3 || similarity(x,y)<0.55 ||
                    (1-similarity(cx,cy))*Math.max(cx.length(),cy.length())>1.01) return false;
        }
        return same>0 && changed==1;
    }
    static boolean firstTrackMatches(String spoken,String title,String subtitle) {
        return trackScore(norm(spoken),new Row(title,subtitle))>0;
    }
    private static String withoutArticle(String name) {
        return name.startsWith("the ")?name.substring(4):name;
    }
    private static String compact(String name) {return name.replace(" ","");}
    private static boolean sameName(String a,String b) {
        return compact(withoutArticle(a)).equals(compact(withoutArticle(b)));
    }
    private static boolean nearTitle(String heard,String title) {
        String a=compact(withoutArticle(heard)), b=compact(withoutArticle(title));
        return nearName(a,b) || nearName(sound(a),sound(b));
    }
    private static int pairScore(String heardArtist,String heardTitle,String artist,String title) {
        if(heardArtist.equals(artist) && heardTitle.equals(title)) return 100;
        boolean exactArtist=sameName(heardArtist,artist), exactTitle=sameName(heardTitle,title);
        if(exactArtist && exactTitle) return 98;
        if(exactArtist && nearTitle(heardTitle,title)) return 89;
        if(exactTitle && (nearArtist(withoutArticle(heardArtist),withoutArticle(artist)) ||
                nearName(compact(sound(withoutArticle(heardArtist))),compact(sound(withoutArticle(artist)))))) return 89;
        return 0;
    }
    private static String trackCredit(String subtitle) {
        int separator=subtitle.indexOf('•');
        if(separator<0 || !norm(subtitle.substring(0,separator)).matches("трек|песня|song|track")) return "";
        return subtitle.substring(separator+1).trim();
    }
    private static int trackScore(String query,Row row) {
        String credit=trackCredit(row.subtitle), title=norm(row.title);
        if(query.isEmpty() || query.length()>160 || title.isEmpty() || credit.isEmpty()) return 0;
        int exact=creditScore(query,credit,title);
        String remaster=title.replaceFirst(" (?:(?:19|20)\\d{2} )?remaster(?:ed)?(?: (?:19|20)\\d{2})?$","");
        return remaster.equals(title)?exact:Math.max(exact,creditScore(query,credit,remaster)-5);
    }
    private static int creditScore(String query,String credit,String title) {
        int score=trackNameScore(query,norm(credit),title);
        if(score<100 && credit.contains(",")) {
            for(String name:credit.split(",\\s+")) {
                String artist=norm(name);
                if(!artist.isEmpty()) score=Math.max(score,trackNameScore(query,artist,title));
            }
        }
        return score;
    }
    private static int trackNameScore(String query,String artist,String title) {
        if(query.equals(title)) return 96;
        if(sameName(query,title)) return 94;
        int score=0;
        for(int cut=0;cut<query.length();cut++) {
            if(query.charAt(cut)!=' ') continue;
            String left=query.substring(0,cut),right=query.substring(cut+1);
            score=Math.max(score,Math.max(pairScore(left,right,artist,title),pairScore(right,left,artist,title)));
            if(score==100) return score;
        }
        // A misspaced consonant can straddle the artist and title in the transcript.
        String joined=compact(query);
        for(String band:new String[]{compact(artist),compact(withoutArticle(artist))}) {
            if(joined.length()<=band.length()) continue;
            if((joined.startsWith(band) && nearName(joined.substring(band.length()),compact(title))) ||
                    (joined.endsWith(band) && nearName(joined.substring(0,joined.length()-band.length()),compact(title)))) {
                score=Math.max(score,86);
            }
        }
        return score>0?score:query.contains(" ") && closePhrase(joined,compact(title))?70:0;
    }
    static int bestTrack(String spoken,List<Row> tracks) {
        String query=norm(spoken);
        int best=NO_TRACK, top=0, rival=0;
        for(int i=0;i<tracks.size();i++) {
            Row row=tracks.get(i);
            int score=trackScore(query,row);
            if(score==0) continue;
            boolean same=best>=0 && norm(row.title).equals(norm(tracks.get(best).title)) &&
                    sameName(norm(trackCredit(row.subtitle)),norm(trackCredit(tracks.get(best).subtitle)));
            if(score>top) {
                if(!same) rival=Math.max(rival,top);
                best=i; top=score;
            } else if(!same) rival=Math.max(rival,score);
        }
        if(best<0 || top-rival>=4) return best;
        return best==0 && hasConfirmedTitleLeader(query,tracks)?best:AMBIGUOUS_TRACK;
    }
    private static boolean hasConfirmedTitleLeader(String query,List<Row> tracks) {
        Row first=tracks.get(0);
        if(!query.equals(norm(first.title))) return false;
        String lead=norm(trackCredit(first.subtitle));
        for(int i=1;i<tracks.size();i++) {
            Row row=tracks.get(i);
            if(!query.equals(norm(row.title))) continue;
            String credit=norm(trackCredit(row.subtitle));
            if(!credit.isEmpty()) return sameName(lead,credit);
        }
        return false;
    }
    static boolean isArtist(Row row) {
        String subtitle=norm(row.subtitle);
        return subtitle.equals("исполнитель") || subtitle.equals("artist");
    }
    static int bestArtist(String spoken,List<Row> rows) {
        String query=norm(spoken);
        if(query.isEmpty()) return -1;
        int best=-1, top=0;
        for(int i=0;i<rows.size();i++) {
            Row row=rows.get(i);
            if(!isArtist(row)) continue;
            String name=norm(row.title);
            int score=query.equals(name)?2:sameName(query,name)?1:0;
            if(score>top) {
                top=score; best=i;
            } else if(score>0 && score==top) best=-1;
        }
        return best;
    }
    private static boolean closePhrase(String heard,String expected) {
        int shorter=Math.min(heard.length(),expected.length());
        if(shorter<8 || Math.abs(heard.length()-expected.length())>4 || expected.length()>180) return false;
        double score=similarity(heard,expected);
        long errors=Math.round((1-score)*Math.max(heard.length(),expected.length()));
        return score>=0.80 && errors<=Math.min(4,shorter/5);
    }
    static TitleCorrection suggest(String query,List<Row> rows) {
        return suggest(query,rows,java.util.Collections.emptyList());
    }
    static TitleCorrection suggest(String query,List<Row> rows,List<String> knownArtists) {
        String q=norm(query);
        if(q.length()>120) return null;
        List<String> artists=new ArrayList<>(knownArtists);
        for(Row row:rows) {
            String type=norm(row.subtitle);
            if(type.equals("artist") || type.equals("исполнитель")) artists.add(row.title);
            else if(row.subtitle.contains("•")) artists.add(row.subtitle.substring(row.subtitle.indexOf('•')+1).trim());
        }
        TitleCorrection best=null;
        double second=0;
        for(String artist:artists) {
            String a=norm(artist);
            if(a.isEmpty() || a.length()>70) continue;
            String rest=artistTail(q,artist);
            if(rest.length()<5) continue;
            for(Row row:rows) {
                String title=row.title.trim();
                String t=norm(title), sub=norm(row.subtitle);
                boolean sameArtistTrack=(sub.startsWith("трек ") || sub.startsWith("track ") || sub.startsWith("song ")) && sub.endsWith(" "+a);
                if(!sameArtistTrack) {
                    String[] parts=title.split("\\s+[-–—]\\s+",2);
                    if(parts.length!=2) continue;
                    if(norm(parts[1]).equals(a)) title=parts[0];
                    else if(norm(parts[0]).equals(a)) title=parts[1];
                    else continue;
                    t=norm(title);
                }
                if(t.length()<4 || t.length()>120 || t.equals(a)) continue;
                String spoken=sound(rest).replaceFirst("^(?:the|a) ","");
                String target=sound(t).replaceFirst("^(?:the|a) ","");
                String r=spoken.replace(" ",""), c=target.replace(" ","");
                double literal=similarity(r,c);
                String rc=consonants(spoken), tc=consonants(target);
                double phonetic=rc.length()>=3 && tc.length()>=3 ? similarity(rc,tc):0;
                boolean sharedWord=false;
                for(String word:spoken.split(" ")) if(word.length()>=2 && (" "+target+" ").contains(" "+word+" ")) sharedWord=true;
                if(!sharedWord || literal<0.34 || phonetic<0.57) continue;
                double score=(phonetic+literal)/2;
                if(best!=null && norm(best.title).equals(t) && norm(best.artist).equals(a)) continue;
                if(best==null || score>best.score) {
                    if(best!=null) second=Math.max(second,best.score);
                    best=new TitleCorrection(title,artist,score);
                } else second=Math.max(second,score);
            }
        }
        return best!=null && best.score>=0.53 && best.score-second>=0.10 ? best:null;
    }
}
