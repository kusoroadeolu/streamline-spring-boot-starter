package com.github.kusoroadeolu.streamline.channels;

import org.springframework.http.MediaType;

import java.util.concurrent.CompletableFuture;

public interface SseChannel {
    CompletableFuture<Void> send(Object object);

    CompletableFuture<Void> send(Object object, MediaType mediaType);

    void complete();

    void completeWithError(Throwable ex);
}
