package com.github.kusoroadeolu.streamline.streams;

import com.github.kusoroadeolu.streamline.exceptions.SseStreamCompletedException;
import com.github.kusoroadeolu.streamline.exceptions.SseStreamIOException;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static com.github.kusoroadeolu.streamline.streams.StreamStatus.ACTIVE;
import static com.github.kusoroadeolu.streamline.streams.StreamStatus.COMPLETED;
import static com.github.kusoroadeolu.streamline.utils.ApiUtils.assertNotNull;
import static com.github.kusoroadeolu.streamline.utils.ApiUtils.assertPositive;

public class SseStreamImpl implements SseStream {

    private final SseEmitter emitter;
    private final ExecutorService executorService;
    private final ReentrantLock statusLock;
    private volatile StreamStatus streamStatus;

    SseStreamImpl(SseStreamImplBuilder sseChannelImplBuilder) {
        this.emitter = sseChannelImplBuilder.emitter;
        this.executorService = sseChannelImplBuilder.executor;
        this.statusLock = sseChannelImplBuilder.statusLock;
        this.streamStatus = ACTIVE;
    }

    public static SseStreamBuilder builder(){
        return new SseStreamImplBuilder();
    }

    public CompletableFuture<Void> send(Object object){
       return this.send(object, (MediaType) null);
    }

    public CompletableFuture<Void> send(Object object, MediaType mediaType){
        this.statusLock.lock();
        try {
            if(this.isCompleted()) throw new SseStreamCompletedException();
            return CompletableFuture.runAsync(() -> {
                        try {
                            this.emitter.send(object, mediaType);
                        } catch (IOException e) {
                            throw new CompletionException(new SseStreamIOException(e));
                        }
                    }, this.executorService);
        }finally{
            this.statusLock.unlock();
        }
    }

    public void complete(){
        this.markCompleted();
        this.executorService.close();
        this.emitter.complete();
    }

    public void completeWithError(Throwable ex){
        this.markCompleted();
        this.executorService.close();
        this.emitter.completeWithError(ex);
    }

    public SseEmitter getEmitter(){
        return this.emitter;
    }

    private void markCompleted(){
        this.statusLock.lock();
        try {
            if(!this.isCompleted()) this.streamStatus = COMPLETED;
        }finally {
            this.statusLock.unlock();
        }
    }

    private boolean isCompleted(){
        assert this.statusLock.isHeldByCurrentThread() : "StreamStatus lock must be held";
        return this.streamStatus == COMPLETED;
    }

}

class SseStreamImplBuilder implements SseStreamBuilder {
    protected SseEmitter emitter;
    protected final ExecutorService executor;
    protected final ReentrantLock statusLock;

    private long timeout;
    private Runnable onCompletionCallback;
    private Runnable onTimeoutCallback;
    private Consumer<Throwable> onErrorCallback;

    private final static String SSE_NULL_MESSAGE = "Sse emitter cannot be null";
    private final static String TIMEOUT_NEGATIVE_MESSAGE = "Sse timeout cannot be negative";
    private final static long DEFAULT_TIMEOUT = 60_000L;


     SseStreamImplBuilder(ExecutorService executor){
        this.executor = executor;
        this.timeout = DEFAULT_TIMEOUT;
        this.statusLock = new ReentrantLock();
    }

    SseStreamImplBuilder(SseEmitter emitter){
        this(Executors.newSingleThreadExecutor(Thread.ofVirtual().factory()));
        this.emitter = emitter;
    }

     SseStreamImplBuilder(){
        this(Executors.newSingleThreadExecutor(Thread.ofVirtual().factory()));
    }

    public SseStreamBuilder onCompletion(Runnable callback){
        this.onCompletionCallback = callback;
        return this;
    }

    public SseStreamBuilder onError(Consumer<Throwable> callback){
        this.onErrorCallback = callback;
        return this;
    }

    public SseStreamBuilder onTimeout(Runnable callback){
        this.onTimeoutCallback = callback;
        return this;
    }

    public SseStreamBuilder fromEmitter(SseEmitter emitter){
        assertNotNull(emitter, SSE_NULL_MESSAGE);
        this.emitter = emitter;
        return this;
    }

    public SseStreamBuilder withTimeout(long timeout){
        assertPositive(timeout, TIMEOUT_NEGATIVE_MESSAGE);
        this.timeout = timeout;
        return this;
    }


    public SseStream build(){
        if (this.emitter == null) this.emitter = new SseEmitter(this.timeout);
        final SseStream stream = new SseStreamImpl(this);
        this.configureCallback(stream);
        return stream;
    }

    private void configureCallback(SseStream stream){
         this.emitter.onTimeout(() -> {
             stream.complete();
             if(this.onTimeoutCallback != null) this.onTimeoutCallback.run();
         });

        this.emitter.onCompletion(() -> {
            stream.complete();
            if(this.onCompletionCallback != null) this.onCompletionCallback.run();
        });

        this.emitter.onError(ex -> {
            stream.completeWithError(ex);
            if(this.onErrorCallback != null) this.onErrorCallback.accept(ex);
        });
    }

}

enum StreamStatus {
    ACTIVE,
    COMPLETED
}

