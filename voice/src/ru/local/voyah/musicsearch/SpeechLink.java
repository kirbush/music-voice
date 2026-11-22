package ru.local.voyah.musicsearch;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Keeps the :asr process and its loaded model while anyone holds the link.
 * A voice command holds it from the wake phrase to its end, and every decode holds it for its own duration.
 * When the last holder closes, SpeechWorker exits and returns the model memory to the system.
 */
final class SpeechLink implements ServiceConnection {
    private static final long BUDGET_MS=24000;
    private final Context context;
    private volatile CompletableFuture<Messenger> worker;
    private volatile CompletableFuture<Bundle> pending;
    private int holders;
    private int engine;

    SpeechLink(Context context) {this.context=context;}

    /** Take a hold and start loading the engine's model. Every open needs one close. */
    synchronized void open(int engine) {
        this.engine=engine;
        if(holders++==0) {
            worker=new CompletableFuture<>();
            if(!context.bindService(new Intent(context,SpeechWorker.class),this,Context.BIND_AUTO_CREATE))
                worker.completeExceptionally(new IOException("Распознаватель недоступен"));
        } else if(worker.isDone() && !worker.isCompletedExceptionally()) warm(worker.join());
    }

    synchronized void close() {
        if(holders==0 || --holders>0) return;
        try {context.unbindService(this);} catch(IllegalArgumentException notBound) {/* the bind had failed */}
        CompletableFuture<Messenger> closed=worker;
        worker=null;
        closed.completeExceptionally(new IOException("Распознаватель недоступен"));
        drop();
    }

    private void drop() {
        CompletableFuture<Bundle> lost=pending;
        if(lost!=null) lost.complete(new Bundle());
    }

    private void warm(Messenger ready) {
        Message request=Message.obtain(null,SpeechWorker.WARM);
        Bundle data=new Bundle();
        data.putInt("engine",engine);
        request.setData(data);
        try {ready.send(request);} catch(RemoteException gone) {/* onServiceDisconnected follows */}
    }

    @Override public synchronized void onServiceConnected(ComponentName name,IBinder binder) {
        if(holders==0) return;
        Messenger ready=new Messenger(binder);
        if(worker.isDone()) worker=new CompletableFuture<>();
        worker.complete(ready);
        warm(ready);
    }

    @Override public synchronized void onServiceDisconnected(ComponentName name) {
        if(holders==0) return;
        // BIND_AUTO_CREATE brings the process back. The request it was decoding is lost.
        worker=new CompletableFuture<>();
        drop();
    }

    /** Returns the worker's reply. It has no "text" when the worker failed or went away. The caller holds the link. */
    Bundle transcribe(byte[] pcm,String language,int engine,CompletableFuture<Boolean> cancelled) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+BUDGET_MS;
        CompletableFuture<Messenger> link=worker;
        if(link==null) throw new IOException("Распознаватель недоступен");
        CompletableFuture<Bundle> reply=new CompletableFuture<>();
        pending=reply;
        await(link,cancelled,deadline);
        Message request=Message.obtain();
        Bundle data=new Bundle();
        data.putByteArray("pcm",pcm);
        data.putString("lang",language);
        data.putInt("engine",engine);
        request.setData(data);
        request.replyTo=new Messenger(new Handler(Looper.getMainLooper(),answer->{reply.complete(answer.getData());return true;}));
        try {link.get().send(request);} catch(RemoteException gone) {return new Bundle();}
        await(reply,cancelled,deadline);
        return reply.get();
    }

    private static void await(CompletableFuture<?> wanted,CompletableFuture<Boolean> cancelled,long deadline) throws Exception {
        long left=Math.max(1,deadline-SystemClock.elapsedRealtime());
        try {CompletableFuture.anyOf(wanted,cancelled).get(left,TimeUnit.MILLISECONDS);}
        catch(TimeoutException elapsed) {throw new IOException("Истекло время распознавания",elapsed);}
        catch(ExecutionException closed) {
            if(!cancelled.isDone()) throw new IOException("Распознаватель недоступен",closed.getCause());
        }
        if(cancelled.isDone()) throw new CancellationException();
    }
}
