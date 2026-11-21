package ru.local.voyah.musicsearch;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/** Offline regression for the release downloader, using a deterministic HTTPS transport in this JVM only. */
public class ModelStoreTest {
    private static final byte[] content="small known model bytes".getBytes(StandardCharsets.UTF_8);
    private static String scenario;
    private static int requests;
    private static final List<String> ranges=new ArrayList<>();

    static class Reply extends HttpsURLConnection {
        final String rangeMode;
        int start;
        Reply(URL u){super(u);rangeMode=scenario;}
        public void connect(){}
        public void disconnect(){}
        public boolean usingProxy(){return false;}
        public String getCipherSuite(){return "test";}
        public Certificate[] getLocalCertificates(){return null;}
        public Certificate[] getServerCertificates(){return null;}
        public int getResponseCode() {
            requests++;
            String range=getRequestProperty("Range");ranges.add(range);
            start=range==null?0:Integer.parseInt(range.substring(6,range.length()-1));
            if(rangeMode.equals("redirect"))return 302;
            if(rangeMode.equals("ignore-range"))start=0;
            return start>0?206:200;
        }
        public String getHeaderField(String key) {
            if(key.equals("Location"))return "https://unrelated.example/download";
            if(key.equals("Content-Range"))return "bytes "+start+"-"+(content.length-1)+"/"+content.length;
            return null;
        }
        public long getContentLengthLong(){return content.length-start;}
        public InputStream getInputStream() throws IOException {
            if(rangeMode.equals("interrupt") && requests>1)throw new IOException("interrupted");
            int len=rangeMode.equals("interrupt")?6:content.length-start;
            byte[] b=Arrays.copyOfRange(content,start,start+len);
            if(rangeMode.equals("bad-hash"))b[0]^=1;
            return new ByteArrayInputStream(b);
        }
    }
    static ModelStore open(Path directory) throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format("%02x",b));
        String manifest="asr/file\t"+content.length+"\t"+hex+"\thttps://huggingface.co/test/file\n";
        return new ModelStore(directory.toFile(),new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)));
    }
    static void configure(String mode){scenario=mode;requests=0;ranges.clear();}
    static void require(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    private static ModelStore bundle(Path directory,boolean futo) throws Exception {
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        String entry="\t"+content.length+"\t"+hash+"\t";
        String catalog="asr/base"+entry+"https://huggingface.co/test/base\n"
                +"wake/shared"+entry+"https://huggingface.co/test/wake\n"
                +"futo/alternate"+entry+"https://huggingface.co/test/futo\n";
        return new ModelStore(directory.toFile(),new ByteArrayInputStream(catalog.getBytes(StandardCharsets.UTF_8)),futo);
    }
    static void expectFailure(ModelStore store) throws Exception {
        try {store.install();throw new AssertionError("accepted invalid download");}
        catch(IOException expected){require(!store.ready && !new File(store.root,"complete").exists(),"bad ready marker");}
    }
    public static void main(String[] args) throws Exception {
        if(args.length==2) {
            try(InputStream catalog=Files.newInputStream(Paths.get(args[0]))) {
                ModelStore live=new ModelStore(Paths.get(args[1]).toFile(),catalog);
                live.install();require(live.ready && live.bytes==live.total,"incomplete release catalog");
                System.out.println("PASS release catalog over HTTPS: "+live.bytes+" verified bytes");
            }
            return;
        }
        URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
            protected URLConnection openConnection(URL u){return new Reply(u);}
        }:null);
        Path temp=Files.createTempDirectory("music-model-test-");
        for(String resume:new String[]{"range","ignore-range"}){
            Path p=temp.resolve(resume); ModelStore s=open(p);require(!s.ready,"clean install");
            configure("interrupt");expectFailure(s);
            require(s.file("asr/file.part").length()==6,"resume offset");
            configure(resume);s=open(p);s.install();
            require(ranges.contains("bytes=6-") && s.ready && open(p).ready,"verified resume");
            require(Arrays.equals(content,Files.readAllBytes(s.file("asr/file").toPath())),"wrong bytes");
            Files.write(s.file("asr/file").toPath(),new byte[]{0});require(!open(p).ready,"truncated installed file");
        }
        for(String mode:new String[]{"bad-hash","redirect"}){configure(mode);expectFailure(open(temp.resolve(mode)));}
        Path twoEngines=temp.resolve("two-engines");
        configure("range");
        ModelStore futo=bundle(twoEngines,true);
        futo.install();
        require(futo.ready && bundle(twoEngines,true).ready && !bundle(twoEngines,false).ready,"separate completion");
        require(requests==2 && futo.file("wake/shared").isFile() && !futo.file("asr/base").exists(),"FUTO-only installation");
        ModelStore base=bundle(twoEngines,false);
        base.install();
        require(base.ready && requests==3 && bundle(twoEngines,true).ready,"reuse shared wake file");
        Files.delete(futo.file("futo/alternate").toPath());
        require(bundle(twoEngines,false).ready && !bundle(twoEngines,true).ready,"independent model health");
        System.out.println("PASS clean / interrupted / process recreation / HTTP 206 / HTTP 200 restart / digest mismatch / off-origin redirect / truncated installation");
        System.out.println("PASS independent Base and FUTO bundles / shared wake model reuse");
    }
}
