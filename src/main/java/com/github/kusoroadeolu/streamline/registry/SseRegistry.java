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

     public SseStream get(ID id){
         assertNotNull(id, NULL_ID_MESSAGE);
         if (this.isShutdown()) throw new SseRegistryShutdownException();
         return this.streamRegistry.get(id); //Didn't lock this operation, locking adds a bit of overhead for minimal benefit. Only issue is the user could get a complete stream or no stream at all when the registry shuts down
     }

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

     public int size(){
         return this.streamRegistry.size();
     }


     //Broadcasts of events during a shutdown that haven't been submitted yet will simply fail and throw a completion exception
     public CompletableFuture<Void> broadcast(E event){
         assertNotNull(event, NULL_EVENT_MESSAGE);
         if (this.isShutdown()) throw new SseRegistryShutdownException(); //Just a simple check here, a race condition here is not devastating since it doesn't corrupt the registry state. Just doesn't deliver the event clients
         this.registerEvent(event);
         final var futures = new ArrayList<CompletableFuture<Void>>();
         this.streamRegistry.forEach((id, s) -> futures.add(CompletableFuture.runAsync(() -> this.send(id, event), this.registryExecutor)));
         return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
     }

     public boolean sendTo(ID id, E event){
         assertNotNull(id, NULL_ID_MESSAGE);
         assertNotNull(event, NULL_EVENT_MESSAGE);
         return this.send(id, event);
     }

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
         this.streamRegistry.forEach((id, e) -> {
             final var c = CompletableFuture.runAsync(() -> this.removeWithoutLocking(id), this.registryExecutor);
             futures.add(c);
         });
         futures.add(CompletableFuture.runAsync(this.registryExecutor::close)); //Close the exec after to reject new tasks. I actually realised that tasks maybe able to slip in if i completed the streams without closing the exec first.
         //But don't block the main thread.

         return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
     }

     public boolean isShutdown(){
         return this.status == RegistryStatus.SHUTDOWN; //Should be correct 100% of the time with a 3% margin of error lol
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

    private void removeWithoutLocking(ID id){ //helper method to prevent deadlocks that would occur with using `remove` instead
        final var stream = this.streamRegistry.remove(id); //Same semantics as get()
        if (stream != null) stream.complete();
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
    // Same semantics as broadcast
    public void registerEvent(E event){
        this.eventRegistry.add(event, this.eventEvictionPolicy, this.eventPredicate);
    }

    public EventReplayBuilder<ID, E> replay(){
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
    EventEvictionPolicy eventEvictionPolicy;
    Predicate<E> eventPredicate;
    private final static String TIMEOUT_NEGATIVE_MESSAGE = "Sse timeout cannot be negative";
    private final static String KEEP_ALIVE_NEGATIVE_MESSAGE = "Sse stream queue keep alive time cannot be negative";
    private final static String MAX_QUEUED_EVENTS_NEGATIVE_MESSAGE = "Sse stream queue keep alive time cannot be negative";

    @Override
    public SseRegistryBuilder<ID, E> onStreamTimeout(Runnable callback) {
        this.onTimeout = callback;
        return this;
    }

    public SseRegistryBuilderImpl<ID, E> eventEvictionPolicy(EventEvictionPolicy policy) {
        this.eventEvictionPolicy = policy;
        return this;
    }

    public SseRegistryBuilderImpl<ID, E> allowEvents(Predicate<E> eventPredicate) {
        this.eventPredicate = eventPredicate;
        return this;
    }

    @Override
    public SseRegistryBuilder<ID, E> onStreamError(Consumer<Throwable> callback) {
        this.onError = callback;
        return this;
    }

    @Override
    public SseRegistryBuilder<ID, E> onStreamComplete(Runnable callback) {
        this.onComplete = callback;
        return this;
    }

    @Override
    public SseRegistryBuilder<ID, E> streamThreadKeepAliveTime(long timeInSeconds){
        assertPositive(timeInSeconds, KEEP_ALIVE_NEGATIVE_MESSAGE);
        this.threadKeepAliveTime = timeInSeconds;
        return this;
    }

    @Override
    public SseRegistryBuilder<ID, E> maxQueuedEventsPerStream(int maxQueuedEvents){
        assertPositive(maxQueuedEvents, MAX_QUEUED_EVENTS_NEGATIVE_MESSAGE);
        this.maxQueuedEventsPerStream = maxQueuedEvents;
        return this;
    }


    @Override
    public SseRegistryBuilder<ID, E> streamTimeout(long timeout) {
        assertPositive(timeout, TIMEOUT_NEGATIVE_MESSAGE);
        this.timeout = timeout;
        return this;
    }

    @Override
    public SseRegistryBuilder<ID, E> maxEvents(int maxEvents) {
        this.maxEvents = maxEvents;
        return this;
    }

    @Override
    public SseRegistryBuilder<ID, E> maxStreams(int maxStreams) {
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
