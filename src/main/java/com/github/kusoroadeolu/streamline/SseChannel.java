package com.github.kusoroadeolu.streamline;

import com.github.kusoroadeolu.streamline.utils.ApiUtils;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static com.github.kusoroadeolu.streamline.Status.*;
import static com.github.kusoroadeolu.streamline.utils.ApiUtils.assertNotNull;

public class SseChannel {

    private final SseEmitter emitter;
    private final ExecutorService executorService;
    private final ReentrantLock statusLock;
    private volatile Status channelStatus;

     SseChannel(SseChannelBuilder sseChannelBuilder) {
         this.emitter = sseChannelBuilder.emitter;
         this.executorService = sseChannelBuilder.executor;
         this.statusLock = sseChannelBuilder.statusLock;
         this.channelStatus = IDLE;
     }

     public static SseChannelBuilder builder(){
         return new SseChannelBuilder();
     }

     public void send(Object object){
         this.send(object, (MediaType) null);
     }

     public void send(Object object, MediaType mediaType){
        if (this.channelStatus == COMPLETED) return; //TODO Throw here
        else if(this.channelStatus == IDLE) this.setStatus(SENDING);
        this.executorService.execute(() -> {
            try {
                this.emitter.send(object, mediaType);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
     }

     public void complete(){
         if(this.channelStatus == IDLE) this.setStatus(COMPLETED);
         this.emitter.complete();
         this.executorService.close();
     }

     public void completeWithError(Throwable ex){
         if(this.channelStatus == IDLE) this.setStatus(COMPLETED);
         this.emitter.completeWithError(ex);
         this.executorService.close();
     }

     private void setStatus(Status status){
         this.statusLock.lock();
         try {
             this.channelStatus = status;
         }finally {
             this.statusLock.unlock();
         }
     }
}

class SseChannelBuilder{
    protected SseEmitter emitter;
    protected final ExecutorService executor;
    protected final ReentrantLock statusLock;
    private final static String SSE_NULL_MESSAGE = "Sse emitter cannot be null";
    private final static long DEFAULT_TIMEOUT = 60_000;


    public SseChannelBuilder(SseEmitter emitter, ExecutorService executor){
        this.emitter = emitter;
        this.executor = executor;
        this.statusLock = new ReentrantLock();
    }

    public SseChannelBuilder(){
        this(new SseEmitter(DEFAULT_TIMEOUT), Executors.newSingleThreadExecutor(Thread.ofVirtual().factory()));
    }

    public SseChannelBuilder onCompletion(Runnable callback){
        this.emitter.onCompletion(callback);
        return this;
    }

    public SseChannelBuilder onError(Consumer<Throwable> callback){
        this.emitter.onError(callback);
        return this;
    }

    public SseChannelBuilder onTimeout(Runnable callback){
        this.emitter.onTimeout(callback);
        return this;
    }

    public SseChannelBuilder fromEmitter(SseEmitter emitter){
        assertNotNull(emitter, SSE_NULL_MESSAGE);
        this.emitter = emitter;
        return this;
    }

    public SseChannel build(){
        assertNotNull(this.emitter, SSE_NULL_MESSAGE);
        return new SseChannel(this);
    }

}

enum Status{
    IDLE,
    SENDING,
    COMPLETED
}

