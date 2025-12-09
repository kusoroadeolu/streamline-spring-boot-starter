package com.github.kusoroadeolu.streamline.channels;

import com.github.kusoroadeolu.streamline.exceptions.SseChannelCompletedException;
import com.github.kusoroadeolu.streamline.exceptions.SseIOException;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static com.github.kusoroadeolu.streamline.channels.ChannelStatus.*;
import static com.github.kusoroadeolu.streamline.utils.ApiUtils.assertNotNull;

public class SseChannelImpl implements SseChannel {

    private final SseEmitter emitter;
    private final ExecutorService executorService;
    private final ReentrantLock statusLock;
    private ChannelStatus channelStatus;

    SseChannelImpl(SseChannelImplBuilder sseChannelImplBuilder) {
        this.emitter = sseChannelImplBuilder.emitter;
        this.executorService = sseChannelImplBuilder.executor;
        this.statusLock = sseChannelImplBuilder.statusLock;
        this.channelStatus = ACTIVE;
    }

    public static SseChannelImplBuilder builder(){
        return new SseChannelImplBuilder();
    }

    public CompletableFuture<Void> send(Object object){
       return this.send(object, (MediaType) null);
    }

    public CompletableFuture<Void> send(Object object, MediaType mediaType){
        this.statusLock.lock();
        try {
            if(this.isCompleted()) throw new SseChannelCompletedException();
            return CompletableFuture.runAsync(() -> {
                        try {
                            this.emitter.send(object, mediaType);
                        } catch (IOException e) {
                            throw new CompletionException(new SseIOException(e));
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

    private void markCompleted(){
        this.statusLock.lock();
        try {
            if(this.isCompleted()) throw new SseChannelCompletedException(); //Already completed no use
            else this.channelStatus = COMPLETED;
        }finally {
            this.statusLock.unlock();
        }
    }

    private boolean isCompleted(){
        assert this.statusLock.isHeldByCurrentThread() : "ChannelStatus lock must be held";
        return this.channelStatus == COMPLETED;
    }

}

class SseChannelImplBuilder implements SseChannelBuilder{
    protected SseEmitter emitter;
    protected final ExecutorService executor;
    protected final ReentrantLock statusLock;
    private final static String SSE_NULL_MESSAGE = "Sse emitter cannot be null";
    private final static long DEFAULT_TIMEOUT = 60_000;


    public SseChannelImplBuilder(SseEmitter emitter, ExecutorService executor){
        this.emitter = emitter;
        this.executor = executor;
        this.statusLock = new ReentrantLock();
    }

    public SseChannelImplBuilder(){
        this(new SseEmitter(DEFAULT_TIMEOUT), Executors.newSingleThreadExecutor(Thread.ofVirtual().factory()));
    }


    public SseChannelImplBuilder onCompletion(Runnable callback){
        this.emitter.onCompletion(callback);
        return this;
    }

    public SseChannelImplBuilder onError(Consumer<Throwable> callback){
        this.emitter.onError(callback);
        return this;
    }

    public SseChannelImplBuilder onTimeout(Runnable callback){
        this.emitter.onTimeout(callback);
        return this;
    }

    public SseChannelImplBuilder fromEmitter(SseEmitter emitter){
        assertNotNull(emitter, SSE_NULL_MESSAGE);
        this.emitter = emitter;
        return this;
    }


    public SseChannelImpl build(){
        assertNotNull(this.emitter, SSE_NULL_MESSAGE);
        return new SseChannelImpl(this);
    }

}

enum ChannelStatus {
    ACTIVE,
    COMPLETED
}

