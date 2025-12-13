package com.github.kusoroadeolu.streamline.streams;

import java.util.function.Consumer;

/**
 * Builder for configuring and creating {@link SseStream} instances.
 *
 * <p>Provides sensible defaults:
 * <ul>
 *   <li>Timeout: 60 seconds
 *   <li>Thread keep-alive: 60 seconds
 *   <li>Max queued events: 100
 * </ul>
 *
 * Of course all these are configurable
 */
public interface SseStreamBuilder {
    /**
     * Sets the callback invoked when the stream completes normally.
     *
     * <p>Runs after the stream is marked completed but before the
     * emitter completes.
     *
     * @param callback The callback to run
     * @return This builder
     */
    SseStreamBuilder onCompletion(Runnable callback);

    /**
     * Sets the callback invoked when the stream encounters an error.
     *
     * <p>Runs after the stream is marked completed but before the
     * emitter completes with error.
     *
     * @param callback The callback to run, receives the error
     * @return This builder
     */
    SseStreamBuilder onError(Consumer<Throwable> callback);

    /**
     * Sets the callback invoked when the stream times out.
     *
     * <p>Runs after the stream is marked completed but before the
     * emitter completes.
     *
     * @param callback The callback to run
     * @return This builder
     */
    SseStreamBuilder onTimeout(Runnable callback);

    /**
     * Sets the SSE timeout duration.
     *
     * @param timeout Timeout in milliseconds (must be positive)
     * @return This builder
     * @throws IllegalArgumentException if timeout is negative
     */
    SseStreamBuilder withTimeout(long timeout);

    /**
     * Sets how long the executor keeps its thread alive when idle.
     *
     * @param timeInSeconds Keep-alive time in seconds (must be positive)
     * @return This builder
     * @throws IllegalArgumentException if time is negative
     */
    SseStreamBuilder threadKeepAliveTime(long timeInSeconds);

    /**
     * Sets the maximum events that can be queued for sending.
     *
     * <p>If the queue fills, {@link #send(Object)} blocks until space
     * is available. Lower values evict slow clients faster.
     *
     * @param max Maximum queue size (must be positive)
     * @return This builder
     * @throws IllegalArgumentException if max is negative
     */
    SseStreamBuilder maxQueuedEvents(int max);

    /**
     * Creates a stream wrapping an existing emitter.
     *
     * <p>Use this when you need to configure the emitter manually before
     * wrapping it in a stream. The emitter must be an {@link ImmutableSseEmitter}.
     *
     * <p><b>Note:</b> Callbacks configured on the builder will still be
     * applied to the emitter.
     *
     * @param emitter The emitter to wrap
     * @return A new stream wrapping the emitter
     * @throws IllegalArgumentException if emitter is null
     */
    SseStream fromEmitter(ImmutableSseEmitter emitter);

    /**
     * Builds the configured stream.
     *
     * <p>If no emitter was provided via {@link #fromEmitter(ImmutableSseEmitter)},
     * creates a new one with the configured timeout.
     *
     * <p>Automatically wires Spring's callbacks ({@code onTimeout},
     * {@code onCompletion}, {@code onError}) to the stream's lifecycle methods.
     *
     * @return A new {@link SseStream} instance
     */
    SseStream build();
}
