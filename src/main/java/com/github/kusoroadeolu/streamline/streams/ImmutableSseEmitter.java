package com.github.kusoroadeolu.streamline.streams;

import com.github.kusoroadeolu.streamline.exceptions.IllegalModificationException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A {@link SseEmitter} that prevents callback modification after initial setup.
 *
 * <p>Callbacks ({@link #onCompletion(Runnable)}, {@link #onTimeout(Runnable)},
 * {@link #onError(Consumer)}) can only be set once. Subsequent calls throw
 * {@link IllegalModificationException}.
 *
 * <p><b>Why this exists:</b> When passing emitters across service boundaries,
 * accidental callback overwrites can break stream lifecycle management. This
 * class prevents those bugs.
 *
 * <p>Still a standard Spring {@link SseEmitter} - can be returned from
 * {@code @GetMapping} endpoints normally.
 *
 * </p> {@link SseStream} only support this class
 */
public class ImmutableSseEmitter extends SseEmitter {
    private final AtomicBoolean onCompleteCalled;
    private final AtomicBoolean onErrorCalled;
    private final AtomicBoolean onTimeoutCalled;
    private final static String ERROR_MESSAGE = "Callbacks are managed by SseStream and cannot be modified. Configure callbacks via SseStreamBuilder instead.";

    public ImmutableSseEmitter() {
        super();
        this.onCompleteCalled = new AtomicBoolean(false);
        this.onErrorCalled = new AtomicBoolean(false);
        this.onTimeoutCalled = new AtomicBoolean(false);
    }

    public ImmutableSseEmitter(Long timeout) {
        super(timeout);
        this.onCompleteCalled = new AtomicBoolean(false);
        this.onErrorCalled = new AtomicBoolean(false);
        this.onTimeoutCalled = new AtomicBoolean(false);
    }


    /**
     * Registers a callback which runs when the emitter throws an error
     *
     * @throws IllegalModificationException if the callback is set more than once
     * */
    public void onError(Consumer<Throwable> callback) {
        if(!this.onErrorCalled.compareAndSet(false, true)) throw new IllegalModificationException(ERROR_MESSAGE);
        super.onError(callback);
    }

    /**
     * Registers a callback which runs when the emitter times out
     *
     * @throws IllegalModificationException if the callback is set more than once
     * */
    public void onTimeout(Runnable callback) {
        if(!this.onTimeoutCalled.compareAndSet(false, true)) throw new IllegalModificationException(ERROR_MESSAGE);
        super.onTimeout(callback);
    }

    /**
     * Registers a callback which runs when the emitter completes normally
     *
     * @throws IllegalModificationException if the callback is set more than once
     * */
    public void onCompletion(Runnable callback) {
        if(!this.onCompleteCalled.compareAndSet(false, true)) throw new IllegalModificationException(ERROR_MESSAGE);
        super.onCompletion(callback);
    }
}
