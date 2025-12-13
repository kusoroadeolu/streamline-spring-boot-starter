package com.github.kusoroadeolu.streamline.registry;

import com.github.kusoroadeolu.streamline.exceptions.SseRegistryFullException;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Builder for configuring and creating {@link SseRegistry} instances.
 *
 * <p>All configuration is optional - sensible defaults are provided:
 * <ul>
 *   <li>Stream timeout: 60 seconds
 *   <li>Max queued events per stream: 50
 *   <li>Thread keep-alive: 1 second
 *   <li>Event eviction policy: FIFO
 * </ul>
 *
 * @param <ID> Stream identifier type
 * @param <E> Event type
 */
public interface SseRegistryBuilder<ID, E> {

    /**
     * Sets the callback invoked when a stream times out.
     *
     * <p>Runs on the registry executor after the stream is removed.
     *
     * @param callback The callback to run
     * @return This builder
     */
    SseRegistryBuilder<ID, E> onStreamTimeout(Runnable callback);


    /**
     * Sets the event eviction policy for this registry
     *
     * @param policy The specified event eviction policy
     * @return This builder
     * */
    SseRegistryBuilder<ID, E> eventEvictionPolicy(EventEvictionPolicy policy);

    /**
     * Sets the predicate for the types of events allowed to be stored. Null events are rejected by default
     *
     * @param eventPredicate The specified event predicate
     * @return This builder
     * */
    SseRegistryBuilder<ID, E> allowEvents(Predicate<E> eventPredicate);

    /**
     * Sets the callback invoked when a stream encounters an error.
     *
     * <p>Runs on the registry executor after the stream is removed.
     *
     * @param callback The callback to run, receives the error
     * @return This builder
     */
    SseRegistryBuilder<ID, E> onStreamError(Consumer<Throwable> callback);

    /**
     * Sets the callback invoked when a stream completes normally.
     *
     * <p>Runs on the registry executor after the stream is removed.
     *
     * @param callback The callback to run
     * @return This builder
     */
    SseRegistryBuilder<ID, E> onStreamComplete(Runnable callback);

    /**
     * Sets how long stream executors keep idle threads alive.
     *
     * @param timeInSeconds Keep-alive time in seconds (must be positive)
     * @return This builder
     * @throws IllegalArgumentException if time is negative
     */
    SseRegistryBuilder<ID, E> streamThreadKeepAliveTime(long timeInSeconds);


    /**
     * Sets the maximum events queued per stream.
     *
     * <p>If a stream's queue fills, further sends block until space is
     * available. Lower values evict slow clients faster.
     *
     * @param maxQueuedEvents Maximum queue size (must be positive)
     * @return This builder
     * @throws IllegalArgumentException if value is negative
     */
    SseRegistryBuilder<ID, E> maxQueuedEventsPerStream(int maxQueuedEvents);


    /**
     * Sets the SSE timeout for newly created streams.
     *
     * @param timeout Timeout in milliseconds (must be positive)
     * @return This builder
     * @throws IllegalArgumentException if timeout is negative
     */
    SseRegistryBuilder<ID, E> streamTimeout(long timeout);

    /**
     * Sets the maximum events stored in history.
     *
     * <p>When exceeded, events are evicted based on the configured
     * {@link EventEvictionPolicy}.
     *
     * @param maxEvents Maximum history size
     * @return This builder
     */
    SseRegistryBuilder<ID, E> maxEvents(int maxEvents);

    /**
     * Sets the maximum concurrent streams allowed.
     *
     * <p>When exceeded, {@link SseRegistry#createAndRegister(Object)}
     * throws {@link SseRegistryFullException}.
     *
     * @param maxStreams Maximum stream count
     * @return This builder
     */
    SseRegistryBuilder<ID, E> maxStreams(int maxStreams);

    /**
     * Builds the configured registry.
     *
     * @return A new {@link SseRegistry} instance
     */
    SseRegistry<ID, E> build();
}
