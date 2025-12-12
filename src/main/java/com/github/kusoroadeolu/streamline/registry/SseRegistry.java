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

import static com.github.kusoroadeolu.streamline.utils.ApiUtils.assertNotNull;
import static com.github.kusoroadeolu.streamline.utils.ApiUtils.assertPositive;

public class SseRegistry<ID, E> {
    private final Map<ID, SseStream> streamRegistry;
    final ExecutorService registryExecutor;
    final EventHistory<E> eventRegistry;
    private final ReentrantLock lifeCycleLock;
    private final long timeout;
    private final int maxEvents;
    private final int maxStreams;
    private final Runnable onStreamComplete;
    private final Runnable onStreamTimeout;
    private final Consumer<Throwable> onStreamError;
    private final EventEvictionPolicy eventEvictionPolicy;
    private final Predicate<E> eventPredicate;
    private volatile RegistryStatus status;

    private static final String NULL_EVENT_MESSAGE = "Event cannot be null";
    private static final String NULL_ID_MESSAGE = "Id cannot be null";
    private static final String NULL_STREAM_MESSAGE = "Stream cannot be null";


    SseRegistry(SseRegistryBuilderImpl<ID, E> builder) {
        this.streamRegistry = new ConcurrentHashMap<>();
        this.registryExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.lifeCycleLock = new ReentrantLock();
        this.status = RegistryStatus.ACTIVE;

        this.eventEvictionPolicy = builder.eventEvictionPolicy;
        this.timeout = builder.timeout;
        this.maxEvents = builder.maxEvents;
        this.maxStreams = builder.maxStreams;
        this.onStreamComplete = builder.onComplete;
        this.onStreamTimeout = builder.onTimeout;
        this.onStreamError = builder.onError;
        this.eventPredicate = builder.eventPredicate;

        this.eventRegistry = new EventHistory<>(this.maxEvents);


     }

     public static <ID, E> SseRegistryBuilder<ID, E> builder(){
         return new SseRegistryBuilderImpl<>();
     }


     /*  --------------------------- REGISTRY LIFECYCLE --------------------------------------- */


     public void createAndRegister(ID id){
         assertNotNull(id, NULL_ID_MESSAGE);
         if (this.isShutdown()) throw new SseRegistryShutdownException();
         this.lifeCycleLock.lock();
         try{
             if (this.isShutdown()) throw new SseRegistryShutdownException();
             if (this.streamRegistry.size() >= this.maxStreams) throw new SseRegistryFullException();
             final var newStream = this.createStream();
             final var absent = this.streamRegistry.putIfAbsent(id, newStream);
             if(absent != null) return;
             this.registryExecutor.execute(newStream::complete); //Complete the newly created stream but dont block
         }finally {
             lifeCycleLock.unlock();
         }
     }

     public void register(ID id, SseStream stream){
         assertNotNull(id, NULL_ID_MESSAGE);
         assertNotNull(stream, NULL_STREAM_MESSAGE);

         if (this.isShutdown()) throw new SseRegistryShutdownException();
         this.lifeCycleLock.lock();
         try{
             if (this.isShutdown()) throw new SseRegistryShutdownException();
             if (this.size() >= this.maxStreams) throw new SseRegistryFullException();
             final var absent = this.streamRegistry.put(id, stream); //Different semantics from create and register. Replaces the previous sse stream and completes it
             if(absent != null) this.registryExecutor.execute(absent::complete); // dispatch this to a different thread to prevent blocking because of executor close op
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
             if (stream == null) return;
             this.registryExecutor.execute(stream::complete);
         }finally {
             this.lifeCycleLock.unlock();
         }
     }

     public int size(){
         return this.streamRegistry.size();
     }

     public CompletableFuture<Void> broadcast(E event){
         assertNotNull(event, NULL_EVENT_MESSAGE);
         if (this.isShutdown()) throw new SseRegistryShutdownException(); //Just a simple check here, a race condition here is not devastating since it doesn't corrupt the registry state
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
         var soonCompleted = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
         this.registryExecutor.close(); //Close the exec first to reject new tasks. I actually realised that tasks maybe able to slip in if i completed the streams without closing the exec first
         return soonCompleted;
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

     private SseStream createStream(){
         return SseStream.builder()
                 .withTimeout(this.timeout)
                 .onCompletion(this.onStreamComplete)
                 .onError(this.onStreamError)
                 .onTimeout(this.onStreamTimeout)
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
    Runnable onComplete;
    Runnable onTimeout;
    Consumer<Throwable> onError;
    long timeout;
    EventEvictionPolicy eventEvictionPolicy;
    Predicate<E> eventPredicate;
    private final static String TIMEOUT_NEGATIVE_MESSAGE = "Sse timeout cannot be negative";

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
