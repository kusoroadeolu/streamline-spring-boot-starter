package com.github.kusoroadeolu.streamline.registry;

import java.util.function.Consumer;

public interface SseRegistryBuilder<ID, E> {
    SseRegistryBuilder<ID, E> onStreamTimeout(Runnable callback);

    SseRegistryBuilder<ID, E> onStreamError(Consumer<Throwable> callback);

    SseRegistryBuilder<ID, E> onStreamComplete(Runnable callback);

    SseRegistryBuilder<ID, E> streamTimeout(long timeout);

    SseRegistryBuilder<ID, E> maxEvents(int maxEvents);

    SseRegistryBuilder<ID, E> maxStreams(int maxStreams);

    SseRegistry<ID, E> build();
}
