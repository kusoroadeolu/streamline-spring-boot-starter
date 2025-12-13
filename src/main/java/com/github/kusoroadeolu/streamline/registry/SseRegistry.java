package com.github.kusoroadeolu.streamline.registry;

import com.github.kusoroadeolu.streamline.exceptions.SseRegistryFullException;
import com.github.kusoroadeolu.streamline.exceptions.SseRegistryShutdownException;
import com.github.kusoroadeolu.streamline.exceptions.SseStreamCompletedException;
import com.github.kusoroadeolu.streamline.streams.SseStream;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.github.kusoroadeolu.streamline.utils.ApiUtils.*;

//Split this class into life cyle and event replay just so I can navigate it easier

/**
 * Thread-safe registry managing multiple SSE streams.
 *
 * <p>Maps IDs to {@link SseStream} instances, stores event history,
 * and coordinates broadcasting. Uses virtual threads for non-blocking
 * concurrent sends.
 *
 * <p><b>Thread Safety:</b> All public methods are thread-safe. Internal
 * locking ensures consistent state during lifecycle operations (create,
 * register, remove, shutdown).
 *
 * <p><b>Limits:</b>
 * <ul>
 *   <li>Max streams: configured via builder (throws {@link SseRegistryFullException})
 *   <li>Event history: bounded, evicts based on {@link EventEvictionPolicy}
 *   <li>Single-instance only (no distributed state)
 * </ul>
 *
 * <p><b>Shutdown behavior:</b> After {@link #shutdown()}, the registry
 * rejects new operations with {@link SseRegistryShutdownException}. In-flight
 * operations complete normally.
 *
 * @param <ID> Stream identifier type (user ID, session ID, etc.)
 * @param <E> Event type stored in history
 */
public class SseRegistry<ID, E> {
    private final Map<ID, SseStream> streamRegistry;
    final ExecutorService registryExecutor;
    final EventHistory<E> eventRegistry;
    private final ReentrantLock lifeCycleLock;
    private final long timeout;
    private final int maxStreams;
    private final int maxQueuedEventsPerStream;
    private final long threadKeepAliveTime;
    private final Runnable onStreamComplete;
    private final Runnable onStreamTimeout;
    private final Consumer<Throwable> onStreamError;
    private final EventEvictionPolicy eventEvictionPolicy;
    private final Predicate<E> eventPredicate;
    private volatile RegistryStatus status;

    private static final String NULL_EVENT_MESSAGE = "Event cannot be null";
    private static final String NULL_ID_MESSAGE = "Id cannot be null";
    private static final String NULL_STREAM_MESSAGE = "Sse Stream cannot be null";
    private static final String COMPLETED_STREAM_MESSAGE = "Cannot add completed stream to sse registry";



    SseRegistry(SseRegistryBuilderImpl<ID, E> builder) {
        this.streamRegistry = new ConcurrentHashMap<>();
        this.registryExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.lifeCycleLock = new ReentrantLock();
        this.status = RegistryStatus.ACTIVE;

        this.eventEvictionPolicy = builder.eventEvictionPolicy;
        this.timeout = builder.timeout;
        this.maxQueuedEventsPerStream = builder.maxQueuedEventsPerStream;
        this.threadKeepAliveTime = builder.threadKeepAliveTime;
        this.maxStreams = builder.maxStreams;
        this.onStreamComplete = builder.onComplete;
        this.onStreamTimeout = builder.onTimeout;
        this.onStreamError = builder.onError;
        this.eventPredicate = builder.eventPredicate;

        int maxEvents = builder.maxEvents;
        this.eventRegistry = new EventHistory<>(maxEvents);


     }

     public static <ID, E> SseRegistryBuilder<ID, E> builder(){
         return new SseRegistryBuilderImpl<>();
     }


     //  --------------------------- REGISTRY LIFECYCLE ---------------------------------------

    /**
     * Creates a new stream and registers it with the given ID.
     *
     * <p>If a stream with this ID already exists, the old stream is
     * completed and replaced. The old stream's completion happens
     * asynchronously on the registry executor.
     *
     * @param id The ID to map to the new stream
     * @return The newly created stream
     * @throws IllegalArgumentException if {@code id} is null
     * @throws SseRegistryFullException if registry is at max capacity
     * @throws SseRegistryShutdownException if registry is shut down
     */
     public SseStream createAndRegister(ID id){
         assertNotNull(id, NULL_ID_MESSAGE);

         if (this.isShutdown()) throw new SseRegistryShutdownException();
         this.lifeCycleLock.lock();
         try{
             if (this.isShutdown()) throw new SseRegistryShutdownException();
             if (this.streamRegistry.size() >= this.maxStreams) throw new SseRegistryFullException();
             final var newStream = this.createStream(id);
             final var oldStream = this.streamRegistry.put(id, newStream);
             if (oldStream != null) this.registryExecutor.execute(oldStream::complete); // dispatch this to a different thread to prevent blocking because of executor close op
             return newStream;
         }finally {
             lifeCycleLock.unlock();
         }
     }

    /**
     * Registers an existing stream with the given ID.
     *
     * <p>If a stream with this ID already exists, the old stream is
     * completed and replaced. The old stream's completion happens
     * asynchronously on the registry executor.
     *
     * @param id The ID to map to the stream
     * @param stream The stream to register
     * @return The registered stream (same instance passed in)
     * @throws IllegalArgumentException if {@code id} or {@code stream} is null,
     *         or if {@code stream} is already completed
     * @throws SseRegistryFullException if registry is at max capacity
     * @throws SseRegistryShutdownException if registry is shut down
     */
     public SseStream register(ID id, SseStream stream){
         assertNotNull(id, NULL_ID_MESSAGE);
         assertNotNull(stream, NULL_STREAM_MESSAGE);
         assertTrue(!stream.isCompleted(), COMPLETED_STREAM_MESSAGE);

         if (this.isShutdown()) throw new SseRegistryShutdownException();
         this.lifeCycleLock.lock();
         try{
             if (this.isShutdown()) throw new SseRegistryShutdownException();
             if (this.size() >= this.maxStreams) throw new SseRegistryFullException();
             final var oldStream = this.streamRegistry.put(id, stream); //Same semantics as create and register. Replaces the previous sse stream and completes it
             if(oldStream != null) this.registryExecutor.execute(oldStream::complete); // dispatch this to a different thread to prevent blocking because of executor close op
             return stream;
         }finally {
             this.lifeCycleLock.unlock();
         }
     }

    /**
     * Retrieves the stream mapped to the given ID.
     *
     * <p><b>Note:</b> This method is not locked. In rare cases during
     * shutdown, it may return a completed stream or null for an ID
     * that was previously registered.
     *
     * @param id The ID to look up
     * @return The stream mapped to this ID, or {@code null} if none exists
     * @throws IllegalArgumentException if {@code id} is null
     * @throws SseRegistryShutdownException if registry is shut down
     */
     public SseStream get(ID id){
         assertNotNull(id, NULL_ID_MESSAGE);
         if (this.isShutdown()) throw new SseRegistryShutdownException();
         return this.streamRegistry.get(id); //Didn't lock this operation, locking adds a bit of overhead for minimal benefit. Only issue is the user could get a complete stream or no stream at all when the registry shuts down
     }

    /**
     * Removes and completes the stream mapped to the given ID.
     *
     * <p>The stream's completion happens asynchronously on the registry
     * executor. If the registry is shut down, this method returns silently.
     *
     * @param id The ID of the stream to remove
     * @throws IllegalArgumentException if {@code id} is null
     */
     public void remove(ID id){
         assertNotNull(id, NULL_ID_MESSAGE);
         if (this.isShutdown()) return;
         this.lifeCycleLock.lock();
         try {
             if (this.isShutdown()) return;
             final var stream = this.streamRegistry.remove(id);
             if (stream != null) this.registryExecutor.execute(stream::complete);
         }finally {
             this.lifeCycleLock.unlock();
         }
     }

     /**
      * @return The current size of the registry
      * */
     public int size(){
         if (this.isShutdown()) return 0;
         return this.streamRegistry.size();
     }

    /**
     * Broadcasts an event to all registered streams.
     *
     * <p>Sends happen in parallel using virtual threads. Completed streams
     * are automatically skipped. The event is added to the registry's event
     * history before broadcasting.
     *
     * <p><b>Race condition:</b> If shutdown occurs between the initial check
     * and submission, the broadcast may fail. This does not corrupt state -
     * the event simply isn't delivered.
     * </br> This decision was made to prevent locking sends which could lead to deadlocks if used poorly and also to prevent times when the lock is held for too long
     *
     * @param event The event to broadcast
     * @return A {@link CompletableFuture} that completes when all sends finish
     * @throws IllegalArgumentException if {@code event} is null
     * @throws SseRegistryShutdownException if registry is shut down
     */
     //Broadcasts of events during a shutdown that haven't been submitted yet will simply fail and throw a completion exception
     public CompletableFuture<Void> broadcast(E event){
         assertNotNull(event, NULL_EVENT_MESSAGE);
         if (this.isShutdown()) throw new SseRegistryShutdownException(); //Just a simple check here, a race condition here is not devastating since it doesn't corrupt the registry state. Just doesn't deliver the event clients
         this.registerEvent(event);
         final var futures = new ArrayList<CompletableFuture<Void>>();
         this.streamRegistry.forEach((id, s) -> futures.add(CompletableFuture.runAsync(() -> this.sendWithoutId(s, event), this.registryExecutor)));
         return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
     }

    /**
     * Sends an event to the stream mapped to the given ID.
     *
     * <p>The event is added to the registry's event history before sending.
     *
     * @param id The ID of the target stream
     * @param event The event to send
     * @return {@code true} if the event was sent successfully, {@code false}
     *         if the stream doesn't exist, is completed, or registry is shut down
     * @throws IllegalArgumentException if {@code id} or {@code event} is null
     */
     public boolean sendTo(ID id, E event){
         assertNotNull(id, NULL_ID_MESSAGE);
         assertNotNull(event, NULL_EVENT_MESSAGE);
         return this.send(id, event);
     }

    /**
     * Shuts down the registry.
     *
     * <p>All registered streams are submitted for completion on the registry
     * executor. The executor is closed after all streams are submitted, preventing
     * new tasks from being accepted.
     *
     * <p>This method is idempotent, calling it multiple times has no effect.
     *
     * @return A {@link CompletableFuture} that completes when all streams
     *         are completed and the executor is closed
     */
     public CompletableFuture<Void> shutdown(){
         if(this.isShutdown()) return CompletableFuture.completedFuture(null);

         this.lifeCycleLock.lock();
         try {
             if(this.isShutdown()) return CompletableFuture.completedFuture(null);
             this.status = RegistryStatus.SHUTDOWN;
         }finally {
             this.lifeCycleLock.unlock();
         }

         final var futures = new ArrayList<CompletableFuture<Void>>(this.streamRegistry.size());
         this.streamRegistry.forEach((id, s) -> {
             final var c = CompletableFuture.runAsync(s::complete, this.registryExecutor);
             futures.add(c);
         });
         futures.add(CompletableFuture.runAsync(this.registryExecutor::close)); //Close the exec after to reject new tasks. I actually realised that tasks maybe able to slip in if i completed the streams without closing the exec first.
         //But don't block the main thread.

         return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
     }
     /**
      * @return true or false if the registry is shutdown or not
      * */
     public boolean isShutdown(){
         return this.status == RegistryStatus.SHUTDOWN; //Should be correct 100% of the time with a 3% margin of error lol
     }

    private void sendWithoutId(SseStream stream, E event){
        try {
            if (this.isShutdown()) return;
            this.registerEvent(event);
            if (stream == null) return;
            stream.send(event);
        }catch (SseStreamCompletedException | CompletionException ignored){
        }
    }

    private boolean send(ID id, E event){
        try {
            if (this.isShutdown()) return false;
            this.registerEvent(event);

            final var stream = this.get(id);
            if (stream == null) return false;
            stream.send(event);
            return true;
        }catch (SseStreamCompletedException | CompletionException ignored){
            return false;
        }
    }

     private SseStream createStream(ID id){
         final Runnable onComplete = () -> {
             this.streamRegistry.remove(id);
             if (this.onStreamComplete != null) this.onStreamComplete.run();
         };

         final Runnable onTimeout = () -> {
             this.streamRegistry.remove(id);
             if (this.onStreamTimeout != null) this.onStreamTimeout.run();
         };

         final Consumer<Throwable> onError = e -> {
             this.streamRegistry.remove(id);
             if (this.onStreamError != null) this.onStreamError.accept(e);
         };


         return SseStream.builder()
                 .withTimeout(this.timeout)
                 .onCompletion(onComplete)
                 .onError(onError)
                 .onTimeout(onTimeout)
                 .maxQueuedEvents(this.maxQueuedEventsPerStream)
                 .threadKeepAliveTime(this.threadKeepAliveTime)
                 .build();
     }


    //  --------------------------- EVENT REPLAY ---------------------------------------
    /**
     * Registers an event in the history without broadcasting it.
     *
     * <p>Useful for pre-populating event history or when broadcasting
     * happens separately. The event is subject to the configured eviction
     * policy and predicate filter.
     *
     * @param event The event to register
     * @throws IllegalArgumentException if {@code event} is null
     * @throws SseRegistryShutdownException if registry is shut down
     */
    public void registerEvent(E event){
        assertNotNull(event, NULL_EVENT_MESSAGE);
        if (this.isShutdown()) throw new SseRegistryShutdownException();
        this.eventRegistry.add(event, this.eventEvictionPolicy, this.eventPredicate);
    }

    /**
     * Creates a builder for replaying events from history.
     *
     * <p>Example usage:
     * <pre>{@code
     * registry.replay()
     *     .matching(e -> e.getId().compareTo(lastEventId) > 0)
     *     .to(userId);
     * }</pre>
     *
     * @return A new {@link EventReplayBuilder} for configuring replay
     * @throws SseRegistryShutdownException if registry is shut down
     */
    public EventReplayBuilder<ID, E> replay(){
         if (this.isShutdown()) throw new SseRegistryShutdownException();
         return new EventReplayBuilder<>(this.registryExecutor, this.eventRegistry.getAll(), this.streamRegistry);
    }

    //For tests
    protected Collection<SseStream> getAllStreams(){
         return this.streamRegistry.values();
     }
}


final class SseRegistryBuilderImpl<ID, E> implements SseRegistryBuilder<ID, E>{
    int maxEvents;
    int maxStreams;
    int maxQueuedEventsPerStream = 50;
    long threadKeepAliveTime = 1;
    Runnable onComplete;
    Runnable onTimeout;
    Consumer<Throwable> onError;
    long timeout = 60_000L;
    EventEvictionPolicy eventEvictionPolicy = EventEvictionPolicy.FIFO;
    Predicate<E> eventPredicate;
    private final static String TIMEOUT_NEGATIVE_MESSAGE = "Sse timeout cannot be negative";
    private final static String KEEP_ALIVE_NEGATIVE_MESSAGE = "Sse stream queue keep alive time cannot be negative";
    private final static String MAX_QUEUED_EVENTS_NEGATIVE_MESSAGE = "Sse stream queue keep alive time cannot be negative";
    private final static String NULL_CALLBACK_MESSAGE = "Sse callbacks cannot be null";
    private final static String NULL_PREDICATE_MESSAGE = "Event predicate cannot be null";
    private final static String NULL_EVICTION_POLICY = "Event eviction policy cannot be null";
    private final static String LESS_THAN_ONE_MESSAGE = "Cannot be less than one";

    public SseRegistryBuilder<ID, E> onStreamTimeout(Runnable callback) {
        assertNotNull(callback, NULL_CALLBACK_MESSAGE);
        this.onTimeout = callback;
        return this;
    }


    public SseRegistryBuilderImpl<ID, E> eventEvictionPolicy(EventEvictionPolicy policy) {
        assertNotNull(policy, NULL_EVICTION_POLICY);
        this.eventEvictionPolicy = policy;
        return this;
    }

    public SseRegistryBuilderImpl<ID, E> allowEvents(Predicate<E> eventPredicate) {
        assertNotNull(eventEvictionPolicy, NULL_PREDICATE_MESSAGE);
        this.eventPredicate = eventPredicate;
        return this;
    }



    public SseRegistryBuilder<ID, E> onStreamError(Consumer<Throwable> callback) {
        assertNotNull(callback, NULL_CALLBACK_MESSAGE);
        this.onError = callback;
        return this;
    }


    public SseRegistryBuilder<ID, E> onStreamComplete(Runnable callback) {
        assertNotNull(callback, NULL_CALLBACK_MESSAGE);
        this.onComplete = callback;
        return this;
    }


    public SseRegistryBuilder<ID, E> streamThreadKeepAliveTime(long timeInSeconds){
        assertPositive(timeInSeconds, KEEP_ALIVE_NEGATIVE_MESSAGE);
        this.threadKeepAliveTime = timeInSeconds;
        return this;
    }


    public SseRegistryBuilder<ID, E> maxQueuedEventsPerStream(int maxQueuedEvents){
        assertPositive(maxQueuedEvents, MAX_QUEUED_EVENTS_NEGATIVE_MESSAGE);
        this.maxQueuedEventsPerStream = maxQueuedEvents;
        return this;
    }



    public SseRegistryBuilder<ID, E> streamTimeout(long timeout) {
        assertPositive(timeout, TIMEOUT_NEGATIVE_MESSAGE);
        this.timeout = timeout;
        return this;
    }


    public SseRegistryBuilder<ID, E> maxEvents(int maxEvents) {
        assertTrue(maxEvents >= 1, LESS_THAN_ONE_MESSAGE);
        this.maxEvents = maxEvents;
        return this;
    }


    public SseRegistryBuilder<ID, E> maxStreams(int maxStreams) {
        assertTrue(maxStreams >= 1, LESS_THAN_ONE_MESSAGE);
        this.maxStreams = maxStreams;
        return this;
    }

    @Override
    public SseRegistry<ID, E> build(){
        return new SseRegistry<>(this);
    }
}

enum RegistryStatus{
    ACTIVE,
    SHUTDOWN
}
