package com.github.kusoroadeolu.streamline.streams;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.function.Consumer;

public interface SseStreamBuilder {
    SseStreamBuilder onCompletion(Runnable callback);

    SseStreamBuilder onError(Consumer<Throwable> callback);

    SseStreamBuilder onTimeout(Runnable callback);

    SseStreamBuilder fromEmitter(ImmutableSseEmitter emitter);

    SseStreamBuilder withTimeout(long timeout);

    SseStream build();
}
