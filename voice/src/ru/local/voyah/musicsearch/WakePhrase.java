package ru.local.voyah.musicsearch;

import android.content.Context;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import java.io.*;
import java.util.*;

final class WakePhrase {
    static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace('ё','е')
                .replaceAll("[\\s,!.?«»’'\\-]+"," ").trim();
    }
    static String pieces(Context context,String phrase) throws IOException {
        String text=normalize(phrase);
        boolean ru=text.matches("[а-я ]+");
        if(text.isEmpty() || text.length()>64 || (!ru && !text.matches("[a-z ]+")))
            throw new IllegalArgumentException("Введите слово или фразу до 64 букв на русском или английском.");
        String input="▁"+(ru?text:text.toUpperCase(Locale.ROOT)).replace(' ','▁');
        Map<String,Double> scores=new HashMap<>();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(context.getAssets().open((ru?"ru":"en")+"-pieces.tsv"),"UTF-8"))) {
            String line;
            while((line=reader.readLine())!=null) {
                int tab=line.indexOf('\t');
                scores.put(line.substring(0,tab),Double.parseDouble(line.substring(tab+1)));
            }
        }
        return tokenize(input,scores);
    }
    // Highest log-probability segmentation for the model's SentencePiece unigram vocabulary.
    static String tokenize(String input,Map<String,Double> scores) {
        double[] best=new double[input.length()+1];
        int[] prev=new int[best.length];
        Arrays.fill(best,Double.NEGATIVE_INFINITY);
        best[0]=0;
        for(int end=1;end<best.length;end++) for(int begin=0;begin<end;begin++) {
            Double score=scores.get(input.substring(begin,end));
            if(score!=null && best[begin]+score>best[end]) {
                best[end]=best[begin]+score; prev[end]=begin;
            }
        }
        if(!Double.isFinite(best[input.length()])) throw new IllegalArgumentException("Произнесите числа буквами и проверьте написание фразы.");
        LinkedList<String> tokens=new LinkedList<>();
        for(int end=input.length();end>0;end=prev[end]) tokens.addFirst(input.substring(prev[end],end));
        return String.join(" ",tokens);
    }
    static KeywordSpotter create(Context ctx,String phrase) throws IOException {
        boolean legacy=normalize(phrase).equals("алиса");
        boolean ru=!legacy && normalize(phrase).matches("[а-я ]+");
        String root=ru?"wake-ru/":"wake/";
        KeywordSpotterConfig config=new KeywordSpotterConfig();
        OnlineModelConfig m=config.getModelConfig();
        m.setModelType("zipformer2"); m.setNumThreads(1);
        String suffix=ru?"":"-epoch-12-avg-2-chunk-16-left-64";
        String encoder="encoder"+suffix+".int8.onnx", decoder="decoder"+suffix+".onnx", joiner="joiner"+suffix+".int8.onnx";
        ModelStore files=ModelStore.get(ctx,new VoiceSettings(ctx).engine());
        if(!files.ready) throw new IOException("Сначала скачайте модели");
        m.setTokens(files.file(root+"tokens.txt").getAbsolutePath());
        m.getTransducer().setEncoder(files.file(root+encoder).getAbsolutePath());
        m.getTransducer().setDecoder(files.file(root+decoder).getAbsolutePath());
        m.getTransducer().setJoiner(files.file(root+joiner).getAbsolutePath());
        File words=File.createTempFile("wake-", ".txt",ctx.getCacheDir());
        try {
            if(legacy) {
                try(InputStream in=ctx.getAssets().open("wake/keywords.txt")) {
                    java.nio.file.Files.copy(in,words.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                StringBuilder graph=new StringBuilder(pieces(ctx,phrase)).append(" @WAKE\n");
                if(normalize(phrase).equals("эй спотифай")) {
                    for(String spoken:new String[]{"эй спатифэй","эй спотифой","эй спатифой"})
                        graph.append(pieces(ctx,spoken)).append(" @WAKE\n");
                }
                java.nio.file.Files.write(words.toPath(),graph.toString().getBytes("UTF-8"));
            }
            config.setKeywordsFile(words.getAbsolutePath());
            boolean heySpotify=normalize(phrase).equals("эй спотифай");
            config.setKeywordsScore(legacy?2.1f:heySpotify?2.2f:1.5f);
            config.setKeywordsThreshold(legacy?0.15f:heySpotify?0.14f:0.2f);
            config.setNumTrailingBlanks(1);
            return new KeywordSpotter(null,config);
        } finally {words.delete();}
    }
}
