package com.github.kusoroadeolu.streamline.streams;

import com.github.kusoroadeolu.streamline.exceptions.*;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Thread-safe wrapper for Spring's {@link SseEmitter}.
 *
 * <p>Guarantees sequential sends using a single virtual thread executor
 * with a bounded queue. Prevents concurrent modification of the underlying
 * emitter and provides automatic cleanup on completion.
 *
 * <p><b>Thread Safety:</b> All public methods are thread-safe. Multiple
 * threads can call {@link #send(Object)} concurrently - sends are queued
 * and executed sequentially to preserve event order.
 *
 * <p><b>Lifecycle:</b> Streams start {@code ACTIVE} and transition to
 * {@code COMPLETED} when:
 * <ul>
 *   <li>{@link #complete()} is called
 *   <li>{@link #completeWithError(Throwable)} is called
 *   <li>The underlying emitter times out
 *   <li>The client disconnects (triggers Spring's completion callback)
 * </ul>
 *
 * <p>Once completed, the stream rejects new sends with
 * {@link SseStreamCompletedException}.
 *
 * <p><b>Queue behavior:</b> If the send queue fills, {@link #send(Object)}
 * blocks until space is available. This prevents fast producers from
 * overwhelming slow consumers.
 */
public interface SseStream {

    /**
     * Creates a builder for constructing streams.
     *
     * @return A new builder instance
     */
     static SseStreamBuilder builder(){
        return new SseStreamBuilderImpl();
    }

    /**
     * Sends an event to the client.
     *
     * <p>Events are queued and sent sequentially by a single virtual thread.
     * If the queue is full, this method blocks until space is available.
     *
     * @param object The event data to send
     * @return A {@link CompletableFuture} that completes when the send finishes
     * @throws SseStreamCompletedException if the stream is completed
     * @throws CompletionException wrapping {@link SseStreamIOException} if
     *         the send fails due to I/O error or executor rejection
     */
    CompletableFuture<Void> send(Object object);

    /**
     * Sends an event with a specific media type to the client.
     *
     * <p>Events are queued and sent sequentially by a single virtual thread.
     * If the queue is full, this method blocks until space is available.
     *
     * @param object The event data to send
     * @param mediaType The media type for the event (e.g., {@code APPLICATION_JSON})
     * @return A {@link CompletableFuture} that completes when the send finishes
     * @throws SseStreamCompletedException if the stream is completed
     * @throws CompletionException wrapping {@link SseStreamIOException} if
     *         the send fails due to I/O error or executor rejection
     */
    CompletableFuture<Void> send(Object object, MediaType mediaType);

    /**
     * Completes the stream normally.
     *
     * <p>Shuts down the executor (rejecting new sends), then completes the
     * underlying emitter. This method is idempotent - calling it multiple
     * times has no effect.
     *
     * <p>Typically called automatically by Spring when the client disconnects
     * or the stream times out.
     */
    void complete();

    /**
     * Completes the stream with an error.
     *
     * <p>Shuts down the executor (rejecting new sends), then completes the
     * underlying emitter with the given error. This method is idempotent -
     * calling it multiple times has no effect.
     *
     * @param ex The error that caused completion
     */
    void completeWithError(Throwable ex);

    /**
     * Returns the underlying Spring emitter.
     *
     * <p>Use this to return from {@code @GetMapping} endpoints. The emitter
     * is an {@link ImmutableSseEmitter} - its callbacks can only be set once.
     *
     * @return The wrapped {@link SseEmitter}
     */
    SseEmitter getEmitter();

    /**
     * Returns the current number of queued send operations.
     *
     * <p>Useful for monitoring slow clients. High queue sizes indicate the
     * client isn't consuming events fast enough.
     *
     * @return The number of sends waiting to execute
     */
    int queueSize();

    /**
     * Checks if the stream is completed.
     *
     * @return {@code true} if the stream has completed, {@code false} if active
     */
    boolean isCompleted();
}
