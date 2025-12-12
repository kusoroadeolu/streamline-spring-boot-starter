package com.github.kusoroadeolu.streamline.registry;

import java.util.function.Consumer;
import java.util.function.Predicate;

public interface SseRegistryBuilder<ID, E> {
    SseRegistryBuilder<ID, E> onStreamTimeout(Runnable callback);

    SseRegistryBuilderImpl<ID, E> eventEvictionPolicy(EventEvictionPolicy policy);

    SseRegistryBuilderImpl<ID, E> allowEvents(Predicate<E> eventPredicate);

    SseRegistryBuilder<ID, E> onStreamError(Consumer<Throwable> callback);

    SseRegistryBuilder<ID, E> onStreamComplete(Runnable callback);

    SseRegistryBuilder<ID, E> streamTimeout(long timeout);

    SseRegistryBuilder<ID, E> maxEvents(int maxEvents);

    SseRegistryBuilder<ID, E> maxStreams(int maxStreams);

    SseRegistry<ID, E> build();
}
