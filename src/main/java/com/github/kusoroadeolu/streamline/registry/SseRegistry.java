package com.github.kusoroadeolu.streamline.registry;

import com.github.kusoroadeolu.streamline.exceptions.SseRegistryFullException;
import com.github.kusoroadeolu.streamline.exceptions.SseRegistryShutdownException;
import com.github.kusoroadeolu.streamline.exceptions.SseStreamCompletedException;
import com.github.kusoroadeolu.streamline.streams.SseStream;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static com.github.kusoroadeolu.streamline.utils.ApiUtils.assertPositive;

public final class SseRegistry<ID, E> {
    private final ConcurrentHashMap<ID, SseStream> streamRegistry;
    private final ExecutorService registryExecutor;
    private final ArrayList<E> eventRegistry;
    private final ReentrantLock lifeCycleLock;
    private final long timeout;
    private final int maxEvents;
    private final int maxStreams;
    private final Runnable onStreamComplete;
    private final Runnable onStreamTimeout;
    private final Consumer<Throwable> onStreamError;
    private volatile RegistryStatus status;

     SseRegistry(SseRegistryBuilder<ID, E> builder) {
        this.streamRegistry = new ConcurrentHashMap<>();
        this.registryExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.eventRegistry = new ArrayList<>();
        this.lifeCycleLock = new ReentrantLock();
        this.timeout = builder.timeout;
        this.maxEvents = builder.maxEvents;
        this.maxStreams = builder.maxStreams;
        this.onStreamComplete = builder.onComplete;
        this.onStreamTimeout = builder.onTimeout;
        this.onStreamError = builder.onError;
        this.status = RegistryStatus.ACTIVE;

     }

     public SseRegistryBuilder<ID, E> builder(){
         return new SseRegistryBuilder<>();
     }

     public void createAndRegister(ID id){
         this.lifeCycleLock.lock();
         try{
             if (this.status != RegistryStatus.ACTIVE) throw new SseRegistryShutdownException();
             final var stream = this.createStream();
             var absent = this.streamRegistry.putIfAbsent(id, stream);
             if(absent != null) return;
             this.registryExecutor.execute(stream::complete); //Complete the stream but dont block
         }finally {
             lifeCycleLock.unlock();
         }
     }

     public void register(ID id, SseStream stream){
         this.lifeCycleLock.lock();
         try{
             if (this.isShutdown()) throw new SseRegistryShutdownException();
             if (this.streamRegistry.size() > this.maxStreams) throw new SseRegistryFullException();
             var absent = this.streamRegistry.put(id, stream); //Different semantics from create and register. Replaces the previous sse stream
             if(absent != null) absent.complete(); //Complete the previous stream. Maybe dispatch this to a different thread to prevent blocking because of executor close op
         }finally {
             this.lifeCycleLock.unlock();
         }
     }

     public SseStream get(ID id){
         return this.streamRegistry.get(id); //Didn't lock this operation, locking adds a bit of overhead for minimal benefit. Only issue is the user could get a complete stream or no stream at all when the registry shuts down
     }

     public void remove(ID id){
         this.lifeCycleLock.lock(); //This lock might not be needed but to prevent race conditions when broadcasting or shutting down
         SseStream stream;
         try {
             if (this.isShutdown()) return;
             stream = this.streamRegistry.remove(id); //Same semantics as get()
         }finally {
             this.lifeCycleLock.unlock();
         }

         stream.complete(); // Didn't make this async to let the user know that this stream actually completed
     }

     public void broadcast(E event){
         this.lifeCycleLock.lock();
         try {
             this.streamRegistry.forEach((id, s) -> CompletableFuture.runAsync(() -> this.sendTo(id, event),
                     this.registryExecutor));
         }finally {
             this.lifeCycleLock.unlock();
         }
     }

     public boolean sendTo(ID id, E event){
         this.lifeCycleLock.lock();
         try {
             if (this.isShutdown()) return false;
             this.get(id).send(event); //This is non-blocking, overhead is minimal
         }catch (SseStreamCompletedException | CompletionException ignored){
             return false;
         }finally {
             this.lifeCycleLock.unlock();
         }
         return true;
     }

     public CompletableFuture<Void> shutdown(){
         this.lifeCycleLock.lock();
         try {
             if(this.isShutdown()) return new CompletableFuture<>(); //Return an incomplete future
             this.status = RegistryStatus.SHUTDOWN;

             List<CompletableFuture<?>> futures = new ArrayList<>(this.streamRegistry.size() + 1);
             this.streamRegistry.forEach((id, e) -> {
                 var c = CompletableFuture.runAsync(() -> this.remove(id), this.registryExecutor);
                 futures.add(c);
             });

             return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                     .thenRunAsync(this.registryExecutor::close, this.registryExecutor); //Return a completable future here so the user can deal with this

         }finally {
             this.lifeCycleLock.unlock();
         }
     }

     private boolean isShutdown(){
         return this.status == RegistryStatus.SHUTDOWN;
     }


     private SseStream createStream(){
         return SseStream.builder()
                 .withTimeout(this.timeout)
                 .onCompletion(this.onStreamComplete)
                 .onError(this.onStreamError)
                 .onTimeout(this.onStreamTimeout)
                 .build();
     }


}

class SseRegistryBuilder<ID, E>{
    protected int maxEvents;
    protected int maxStreams;
    protected Runnable onComplete;
    protected Runnable onTimeout;
    protected Consumer<Throwable> onError;
    protected long timeout;
    private final static String TIMEOUT_NEGATIVE_MESSAGE = "Sse timeout cannot be negative";


    public SseRegistryBuilder<ID, E> timeout(long timeout){
        this.timeout = timeout;
        return this;
    }

    public SseRegistryBuilder<ID, E> onStreamTimeout(Runnable callback) {
        this.onTimeout = callback;
        return this;
    }

    public SseRegistryBuilder<ID, E> onStreamError(Consumer<Throwable> callback) {
        this.onError = callback;
        return this;
    }

    public SseRegistryBuilder<ID, E> onStreamComplete(Runnable callback) {
        this.onComplete = callback;
        return this;
    }

    public SseRegistryBuilder<ID, E> streamTimeout(long timeout) {
        assertPositive(timeout, TIMEOUT_NEGATIVE_MESSAGE);
        this.timeout = timeout;
        return this;
    }

    public SseRegistryBuilder<ID, E> maxEvents(int maxEvents) {
        this.maxEvents = maxEvents;
        return this;
    }

    public SseRegistryBuilder<ID, E> maxStreams(int maxStreams) {
        this.maxStreams = maxStreams;
        return this;
    }

    public SseRegistry<ID, E> build(){
        return new SseRegistry<>(this);
    }
}

enum RegistryStatus{
    ACTIVE,
    SHUTDOWN
}
