package com.github.kusoroadeolu.streamline.streams;

import com.github.kusoroadeolu.streamline.exceptions.SseStreamCompletedException;
import com.github.kusoroadeolu.streamline.exceptions.SseStreamIOException;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static com.github.kusoroadeolu.streamline.streams.StreamStatus.ACTIVE;
import static com.github.kusoroadeolu.streamline.streams.StreamStatus.COMPLETED;
import static com.github.kusoroadeolu.streamline.utils.ApiUtils.assertNotNull;
import static com.github.kusoroadeolu.streamline.utils.ApiUtils.assertPositive;

public class SseStreamImpl implements SseStream {

    private final ImmutableSseEmitter emitter;
    private final ExecutorService executorService;
    private final ReentrantLock statusLock;
    private volatile StreamStatus streamStatus;

    SseStreamImpl(SseStreamBuilderImpl sseChannelImplBuilder) {
        this.emitter = sseChannelImplBuilder.emitter;
        this.executorService = sseChannelImplBuilder.executor;
        this.statusLock = sseChannelImplBuilder.statusLock;
        this.streamStatus = ACTIVE;
    }

    public static SseStreamBuilder builder(){
        return new SseStreamBuilderImpl();
    }

    public CompletableFuture<Void> send(Object object){
       return this.send(object, (MediaType) null);
    }

    public CompletableFuture<Void> send(Object object, MediaType mediaType){
        if(this.isCompleted()) throw new SseStreamCompletedException(); //Check first, worst case two threads still try to acquire the lock, best case no lock is acquired
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
        if(this.isCompleted()) return;
        this.markCompleted();
        this.executorService.close();
        this.emitter.complete();
    }

    public void completeWithError(Throwable ex){
        if(this.isCompleted()) return;
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

    public boolean isCompleted(){
        return this.streamStatus == COMPLETED;
    }

}

class SseStreamBuilderImpl implements SseStreamBuilder {
    protected ImmutableSseEmitter emitter;
    protected final ExecutorService executor;
    protected final ReentrantLock statusLock;

    private long timeout;
    private Runnable onCompletionCallback;
    private Runnable onTimeoutCallback;
    private Consumer<Throwable> onErrorCallback;

    private final static String TIMEOUT_NEGATIVE_MESSAGE = "Sse timeout cannot be negative";
    private final static long DEFAULT_TIMEOUT = 60_000L;


     SseStreamBuilderImpl(ThreadPoolExecutor executor){
        this.executor = executor;
        this.timeout = DEFAULT_TIMEOUT;
        this.statusLock = new ReentrantLock();
     }

    SseStreamBuilderImpl(ImmutableSseEmitter emitter){
        this();
        this.emitter =  emitter;
    }

     SseStreamBuilderImpl(){
        this(new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10)));
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

    public SseStreamBuilder withTimeout(long timeout){
        assertPositive(timeout, TIMEOUT_NEGATIVE_MESSAGE);
        this.timeout = timeout;
        return this;
    }

    public SseStream fromEmitter(SseEmitter emitter){
         assertNotNull(emitter, "Sse emitter cannot be null");
         this.emitter = (ImmutableSseEmitter) emitter;
         final var stream = new SseStreamImpl(this);
         this.configureCallback(stream);
         return stream;
    }


    public SseStream build(){
        if (this.emitter == null) this.emitter = new ImmutableSseEmitter(this.timeout);
        final var stream = new SseStreamImpl(this);
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

