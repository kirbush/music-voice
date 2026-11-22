package ru.local.voyah.musicsearch;

import android.content.Context;
import android.media.*;
import android.os.SystemClock;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

final class CabinMic {
    private static final int RATE=16000;
    /** Discard what the input buffered before a prompt tone ended. */
    static void drain(AudioRecord r) {
        short[] discard=new short[2048];
        for(int i=0;i<8 && r.read(discard,0,discard.length,AudioRecord.READ_NON_BLOCKING)>0;i++) {}
    }
    static float[] read(AudioRecord r, Runnable ready, BooleanSupplier cancelled) {
        float[] all=new float[RATE*14];
        short[] buf=new short[640];
        int count=0, first=-1, last=0, loudFrames=0;
        boolean signalled=false;
        long start=SystemClock.elapsedRealtime();
        while(count+buf.length<=all.length && SystemClock.elapsedRealtime()-start<15000 && !cancelled.getAsBoolean()) {
            int n=r.read(buf,0,buf.length,AudioRecord.READ_NON_BLOCKING);
            if(n<0) throw new IllegalStateException("Ошибка записи "+n);
            if(n==0) {
                if(count==0 && SystemClock.elapsedRealtime()-start>2400) throw new IllegalStateException("Микрофон не отдаёт звук. Попробуйте ещё раз.");
                SystemClock.sleep(10);
                continue;
            }
            double energy=0;
            for(int j=0;j<n;j++) {
                float s=buf[j]/32768f;
                all[count+j]=s;
                energy+=s*s;
            }
            if(!signalled && energy>0) {signalled=true; ready.run();}
            if(energy/n>0.0035*0.0035) {
                if(first<0) first=count;
                last=count+n;
                loudFrames+=n;
            }
            count+=n;
            if(first>=0 && count-last>RATE*12/10 && loudFrames>RATE/7) break;
            if(first<0 && count>RATE*8) break;
        }
        if(cancelled.getAsBoolean()) throw new IllegalStateException("Запись отменена");
        if(first<0 || loudFrames<RATE/7) throw new IllegalStateException("Не услышал название.");
        return Arrays.copyOfRange(all,Math.max(0,first-RATE/3),Math.min(count,last+RATE/3));
    }
}
