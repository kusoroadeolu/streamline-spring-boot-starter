package com.github.kusoroadeolu.streamline.channels;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.function.Consumer;

public interface SseChannelBuilder {
    SseChannelBuilder onCompletion(Runnable callback);

    SseChannelBuilder onError(Consumer<Throwable> callback);

    SseChannelBuilder onTimeout(Runnable callback);

    SseChannelBuilder fromEmitter(SseEmitter emitter);

    SseChannelImpl build();
}
