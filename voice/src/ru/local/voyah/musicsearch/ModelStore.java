package ru.local.voyah.musicsearch;

import android.content.Context;
import java.io.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.*;
import java.util.*;
import java.util.regex.*;

final class ModelStore {
    static final class Asset {
        final String path, hash;
        final long size;
        final URL url;
        Asset(String line) throws IOException {
            String[] f=line.split("\\t",-1);
            if(f.length!=4 || !f[0].matches("(asr|futo|wake|wake-ru)/[a-zA-Z0-9._-]+") || !f[2].matches("[0-9a-f]{64}"))
                throw new IOException("Некорректный каталог моделей");
            path=f[0]; size=Long.parseLong(f[1]); hash=f[2]; url=new URL(f[3]);
            if(size<1 || !url.getProtocol().equals("https") || !url.getHost().equals("huggingface.co")) throw new IOException("Некорректный источник");
        }
    }
    private static ModelStore base,futo;
    static synchronized ModelStore get(Context c,int engine) {
        boolean useFuto=engine==VoiceSettings.FUTO;
        ModelStore store=useFuto?futo:base;
        if(store==null) {
            try(InputStream in=c.getAssets().open("model-files.tsv")) {
                store=new ModelStore(new File(c.getNoBackupFilesDir(),"models-v1"),in,useFuto);
                if(useFuto) futo=store; else base=store;
            } catch(IOException e) {throw new IllegalStateException("Каталог моделей недоступен",e);}
        }
        return store;
    }
    final File root;
    private final List<Asset> assets=new ArrayList<>();
    private final File marker;
    final long total;
    volatile boolean ready, busy;
    private volatile boolean cancelled;
    volatile long bytes;
    volatile String message="";
    ModelStore(File root,InputStream catalog) throws IOException {
        this(root,catalog,false);
    }
    ModelStore(File root,InputStream catalog,boolean useFuto) throws IOException {
        this.root=root; marker=new File(root,useFuto?"complete-futo-74":"complete");
        long sum=0;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(catalog,StandardCharsets.UTF_8))) {
            String line; while((line=reader.readLine())!=null) {
                Asset item=new Asset(line);
                if(item.path.startsWith(useFuto?"asr/":"futo/")) continue;
                assets.add(item);sum+=item.size;
            }
        }
        if(assets.isEmpty()) throw new IOException("Пустой каталог");
        total=sum;
        ready=marker.isFile() && assets.stream().allMatch(a->file(a.path).length()==a.size);
        if(ready) bytes=total;
    }
    File file(String path) {return new File(root,path);}
    void start() {
        synchronized(ModelStore.class) {
            if(busy || ready || (base!=null && base.busy) || (futo!=null && futo.busy)) return;
            cancelled=false; busy=true; message="Подготовка…";
        }
        new Thread(()->{
            try {install();message="Модели готовы";}
            catch(IOException|RuntimeException e) {message=cancelled?"Загрузка приостановлена":("Загрузка прервана. "+friendly(e));}
            finally {busy=false;}
        },"Download speech models").start();
    }
    private static String friendly(Exception e) {
        if(e instanceof UnknownHostException || e instanceof SocketTimeoutException || e instanceof javax.net.ssl.SSLException) return "Проверьте интернет и повторите.";
        return e instanceof DownloadError?e.getMessage():"Проверьте сеть и свободное место.";
    }
    static final class DownloadError extends IOException { DownloadError(String m){super(m);} }
    void pause() {cancelled=true;message="Приостанавливаю загрузку…";}
    private void check() throws InterruptedIOException {if(cancelled)throw new InterruptedIOException();}
    void install() throws IOException {
        if(!root.isDirectory() && !root.mkdirs()) throw new DownloadError("Нет места для моделей.");
        long done=0;bytes=0;
        for(Asset a:assets) {
            check();
            File dest=file(a.path); File part=file(a.path+".part");
            if(dest.length()==a.size && verified(dest,a.hash)) {done+=a.size;bytes=done;continue;}
            File dir=dest.getParentFile();
            if(!dir.isDirectory() && !dir.mkdirs())throw new DownloadError("Не удалось создать каталог.");
            if(part.length()>a.size) Files.delete(part.toPath());
            if(dir.getUsableSpace()<a.size-part.length()+4L*1024*1024)throw new DownloadError("Освободите место на устройстве.");
            for(int attempt=0;;attempt++) {
                try {
                    message=attempt==0?"Скачивание моделей":"Повторное подключение";
                    while(part.length()<a.size) {check(); fetch(a,part,done);}
                    check(); message="Проверка SHA-256";
                    if(!verified(part,a.hash)) {
                        Files.delete(part.toPath());
                        throw new DownloadError("Контрольная сумма файла не совпала.");
                    }
                    break;
                } catch(IOException e) {
                    check();
                    if(attempt>=2) throw e;
                }
            }
            Files.move(part.toPath(),dest.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            done+=a.size;bytes=done;
        }
        check();Files.write(marker.toPath(),new byte[]{1});ready=true;
    }
    private static final Pattern RANGE=Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private void fetch(Asset a,File part,long done) throws IOException {
        long offset=part.length();
        URL url=a.url;
        for(int hop=0;hop<=6;hop++) {
            check();
            String host=url.getHost().toLowerCase(Locale.ROOT);
            boolean trusted=host.equals("huggingface.co") || host.endsWith(".huggingface.co") || host.equals("hf.co") || host.endsWith(".hf.co");
            if(!trusted || !url.getProtocol().equals("https") || url.getUserInfo()!=null || (url.getPort()!=-1 && url.getPort()!=443))
                throw new DownloadError("Небезопасное перенаправление.");
            HttpsURLConnection c=(HttpsURLConnection)url.openConnection();
            try {
                c.setConnectTimeout(15000); c.setReadTimeout(18000); c.setInstanceFollowRedirects(false);
                c.setUseCaches(false);c.setRequestProperty("Accept-Encoding","identity");
                if(offset>0)c.setRequestProperty("Range","bytes="+offset+"-");
                int code=c.getResponseCode();
                if(code==301 || code==302 || code==303 || code==307 || code==308) {
                    String location=c.getHeaderField("Location");
                    if(location==null)throw new DownloadError("Пустое перенаправление.");
                    url=new URL(url,location);continue;
                }
                long end=a.size;
                if(code==206) {
                    Matcher m=RANGE.matcher(String.valueOf(c.getHeaderField("Content-Range")));
                    if(!m.matches() || Long.parseLong(m.group(1))!=offset || Long.parseLong(m.group(3))!=a.size)
                        throw new DownloadError("Сервер вернул другой диапазон.");
                    end=Long.parseLong(m.group(2))+1;
                    if(end<=offset || end>a.size)throw new DownloadError("Неверная длина диапазона.");
                } else if(code==200) offset=0;
                else if(code==416 && offset>0){Files.deleteIfExists(part.toPath());throw new DownloadError("Повтор загрузки файла.");}
                else throw new DownloadError("Сервер моделей вернул HTTP "+code+".");
                long len=c.getContentLengthLong();
                if(len>=0 && len!=end-offset)throw new DownloadError("Неожиданный размер файла.");
                try(InputStream in=new BufferedInputStream(c.getInputStream());
                    FileOutputStream out=new FileOutputStream(part,offset>0)) {
                    byte[] buffer=new byte[65536]; long pos=offset; int n;
                    while((n=in.read(buffer))!=-1) {
                        check(); if(n>end-pos)throw new DownloadError("Превышен размер файла.");
                        out.write(buffer,0,n);pos+=n;bytes=done+pos;
                    }
                    if(pos!=end) throw new EOFException("Соединение прервано");
                }
                return;
            } finally {c.disconnect();}
        }
        throw new DownloadError("Слишком много перенаправлений.");
    }
    static boolean verified(File f,String hex) throws IOException {
        MessageDigest sha;
        try {sha=MessageDigest.getInstance("SHA-256");} catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
        try(InputStream in=new BufferedInputStream(new FileInputStream(f))) {
            byte[] buffer=new byte[65536]; int n;
            while((n=in.read(buffer))!=-1)sha.update(buffer,0,n);
        }
        byte[] digest=sha.digest();
        for(int i=0;i<digest.length;i++) if((digest[i]&255)!=Integer.parseInt(hex.substring(2*i,2*i+2),16))return false;
        return true;
    }
}
