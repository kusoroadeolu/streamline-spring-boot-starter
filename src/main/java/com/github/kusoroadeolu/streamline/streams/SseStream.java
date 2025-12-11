package com.github.kusoroadeolu.streamline.streams;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

public interface SseStream {

    static SseStreamBuilder builder(){
        return new SseStreamBuilderImpl();
    }

    CompletableFuture<Void> send(Object object);

    CompletableFuture<Void> send(Object object, MediaType mediaType);

    void complete();

    void completeWithError(Throwable ex);

    SseEmitter getEmitter();
}
