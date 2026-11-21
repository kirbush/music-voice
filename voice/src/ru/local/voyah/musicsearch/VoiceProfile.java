package ru.local.voyah.musicsearch;

import java.io.*;
import java.util.Arrays;

/** Speaker-dependent acoustic templates. Features stay private; enrollment audio is discarded. */
final class VoiceProfile {
    private static final int RATE=16000, WINDOW=400, HOP=160, FFT=512, BANDS=24, DIMS=12;
    private static final double[] HANN=new double[WINDOW];
    private static final int[] EDGES=new int[BANDS+2];
    private static final double[][] DCT=new double[DIMS][BANDS];
    static {
        for(int i=0;i<WINDOW;i++) HANN[i]=0.5-0.5*Math.cos(2*Math.PI*i/(WINDOW-1));
        double lo=Math.log1p(100.0/700), hi=Math.log1p(7600.0/700);
        for(int b=0;b<EDGES.length;b++) EDGES[b]=(int)Math.round(FFT*700*Math.expm1(lo+(hi-lo)*b/(BANDS+1))/RATE);
        for(int j=0;j<DIMS;j++) for(int b=0;b<BANDS;b++) DCT[j][b]=Math.cos(Math.PI*(b+.5)*(j+1)/BANDS);
    }
    private final float[][][] refs;
    private final double threshold;
    private final float[] segment=new float[5*RATE];
    private int size,quiet;
    private boolean overflow;
    private VoiceProfile(float[][][] refs,double threshold) {this.refs=refs;this.threshold=threshold;}

    static VoiceProfile train(float[][][] examples) {
        double[] pairs={distance(examples[0],examples[1]),distance(examples[1],examples[2]),distance(examples[0],examples[2])};
        Arrays.sort(pairs);
        if(pairs[1]>.42) throw new IllegalArgumentException("Образцы сильно различаются. Повторите одну и ту же фразу три раза.");
        return new VoiceProfile(examples,Math.min(.34,Math.max(.14,pairs[1]*1.38+.025)));
    }
    void write(DataOutput out) throws IOException {
        out.writeInt(0x4d565031);out.writeDouble(threshold);
        for(float[][] ref:refs) {out.writeInt(ref.length);for(float[] frame:ref) for(float v:frame) out.writeFloat(v);}
    }
    static VoiceProfile read(DataInput in) throws IOException {
        if(in.readInt()!=0x4d565031) throw new IOException("Версия голосового образца");
        double threshold=in.readDouble();
        if(!(threshold>0 && threshold<=.34)) throw new IOException("Порог голосового образца");
        float[][][] refs=new float[3][][];
        for(int j=0;j<3;j++) {
            int n=in.readInt(); if(n<12 || n>500) throw new IOException("Длина голосового образца");
            refs[j]=new float[n][DIMS];
            for(float[] frame:refs[j]) for(int i=0;i<DIMS;i++) frame[i]=in.readFloat();
        }
        return new VoiceProfile(refs,threshold);
    }
    boolean matches(float[][] query) {
        int matched=0;
        for(float[][] ref:refs) if(distance(ref,query)<=threshold && ++matched==2) return true;
        return false;
    }
    void reset() {size=0;quiet=0;overflow=false;}
    boolean accept(float[] audio) {
        if(audio.length==0) return false;
        double energy=0;
        for(float v:audio) energy+=v*v;
        boolean loud=energy/audio.length>0.0035*0.0035;
        if(!loud && size==0 && !overflow) return false;
        quiet=loud?0:quiet+audio.length;
        if(size+audio.length>segment.length) overflow=true;
        if(!overflow) {System.arraycopy(audio,0,segment,size,audio.length);size+=audio.length;}
        if(quiet<RATE/3) return false;
        int span=size-quiet;
        boolean valid=!overflow && span>=RATE/6 && span<=RATE*4;
        reset();
        if(!valid) return false;
        try {return matches(features(Arrays.copyOf(segment,span)));}
        catch(IllegalArgumentException noise) {return false;}
    }

    static float[][] features(float[] samples) {
        int blocks=samples.length/HOP;
        double[] energies=new double[blocks];double peak=0;
        for(int i=0;i<blocks;i++) {
            for(int j=0;j<HOP;j++) {float x=samples[i*HOP+j];energies[i]+=x*x/HOP;}
            peak=Math.max(peak,energies[i]);
        }
        double floor=Math.max(.000005,peak*.012);
        int start=0,end=blocks;
        while(start<end && energies[start]<floor)start++;
        while(end>start && energies[end-1]<floor)end--;
        int frames=1+((end-start)*HOP-WINDOW)/HOP;
        if(frames<12 || frames>400) throw new IllegalArgumentException("Нужна чёткая фраза длительностью от 0,15 до 4 секунд.");
        float[][] out=new float[frames][DIMS];
        double[] mean=new double[DIMS],re=new double[FFT],im=new double[FFT],bands=new double[BANDS];
        for(int f=0;f<frames;f++) {
            Arrays.fill(re,0);Arrays.fill(im,0);
            int offset=(start+f)*HOP;
            for(int i=0;i<WINDOW;i++){int k=offset+i;re[i]=HANN[i]*(samples[k]-(k>0?.97*samples[k-1]:0));}
            fft(re,im);
            for(int i=0;i<=FFT/2;i++) re[i]=re[i]*re[i]+im[i]*im[i];
            for(int b=0;b<BANDS;b++){
                int l=EDGES[b],m=EDGES[b+1],r=EDGES[b+2];double sum=0;
                for(int i=l;i<m;i++)sum+=re[i]*(i-l)/(m-l);
                for(int i=m;i<r;i++)sum+=re[i]*(r-i)/(r-m);
                bands[b]=Math.log(Math.max(1e-11,sum));
            }
            for(int j=0;j<DIMS;j++){double v=0;for(int b=0;b<BANDS;b++)v+=bands[b]*DCT[j][b];out[f][j]=(float)v;mean[j]+=v;}
        }
        for(float[] f:out){
            double norm=1e-9;for(int j=0;j<DIMS;j++){f[j]-=.90*mean[j]/frames;norm+=f[j]*f[j];}
            norm=Math.sqrt(norm);for(int j=0;j<DIMS;j++) f[j]/=norm;
        }
        return out;
    }
    private static void fft(double[] re,double[] im) {
        for(int i=1,j=0;i<FFT;i++){int bit=FFT>>1;while((j&bit)!=0){j^=bit;bit>>=1;}j^=bit;
            if(i<j){double t=re[i];re[i]=re[j];re[j]=t;}}
        for(int step=2;step<=FFT;step*=2){
            double theta=-2*Math.PI/step,cs=Math.cos(theta),sn=Math.sin(theta);
            for(int base=0;base<FFT;base+=step){
                double wr=1,wi=0;
                for(int k=0;k<step/2;k++){
                    int a=base+k,b=a+step/2;
                    double x=re[b]*wr-im[b]*wi,y=re[b]*wi+im[b]*wr;
                    re[b]=re[a]-x;im[b]=im[a]-y;re[a]+=x;im[a]+=y;
                    double nw=wr*cs-wi*sn;wi=wr*sn+wi*cs;wr=nw;
                }
            }
        }
    }
    private static double distance(float[][] a,float[][] b) {
        int n=a.length,m=b.length;
        if(Math.min(n,m)<Math.max(n,m)*.57)return 1;
        double[] prev=new double[m+1],next=new double[m+1];Arrays.fill(prev,Double.POSITIVE_INFINITY);prev[0]=0;
        for(int i=1;i<=n;i++){
            Arrays.fill(next,Double.POSITIVE_INFINITY);
            int center=(int)Math.round(i*(double)m/n),band=Math.max(7,(int)(.22*m));
            for(int j=Math.max(1,center-band);j<=Math.min(m,center+band);j++){
                double dot=0;for(int k=0;k<DIMS;k++)dot+=a[i-1][k]*b[j-1][k];
                double cost=Math.max(0,1-dot);
                next[j]=Math.min(prev[j-1]+2*cost,Math.min(prev[j],next[j-1])+cost);
            }
            double[] t=prev;prev=next;next=t;
        }
        return prev[m]/(n+m);
    }
}
