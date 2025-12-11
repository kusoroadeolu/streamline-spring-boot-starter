package com.github.kusoroadeolu.streamline.registry;

import com.github.kusoroadeolu.streamline.exceptions.SseRegistryFullException;
import com.github.kusoroadeolu.streamline.exceptions.SseRegistryShutdownException;
import com.github.kusoroadeolu.streamline.streams.ImmutableSseEmitter;
import com.github.kusoroadeolu.streamline.streams.SseStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class SseRegistryTest {

    private FakeEmitter emitter;

    private ErrorEmitter errorEmitter;
    private SseRegistry<Object, TestEvent> registry;
    private ExecutorService testExec;

    @BeforeEach
    public void onSetup(){
        emitter = new FakeEmitter();
        errorEmitter = new ErrorEmitter();
        this.registry = createRegistry(5);
        this.testExec = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Test
    void shouldCreateAndRegisterSseStream() {
       String id = "id";
       this.registry.createAndRegister(id);
       assertNotNull(this.registry.get(id));
    }

    @Test
    void shouldThrowSseRegistryFullException_onRegistryFull(){
        for (int i = 0; i < 5; i++){
            registry.createAndRegister(i);
        }

        assertThrows(SseRegistryFullException.class, () -> registry.createAndRegister(1000));
    }

    @Test
    void onConcurrentWrites_registrySizeShouldBeLessThan5() {
        for (int i = 0; i < 1000; i++){
            try {
                final int j = i;
                CompletableFuture.runAsync(() -> this.registry.createAndRegister(j), testExec);
            }catch (CompletionException ignored){}

        }

        assertEquals(5, this.registry.size());
    }

    @Test
    void shouldNotThrowError_onSend_whileShuttingDown() throws InterruptedException {
        this.registry = createRegistry(1000);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 1000; i++){
            final var j = i;
            var slow = SseStream.builder().fromEmitter(new SlowEmitter());
            var future = CompletableFuture.runAsync(() -> this.registry.register(j, slow), testExec);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        CompletableFuture.runAsync(() -> registry.broadcast(new TestEvent(1, "", "")));
        Thread.sleep(10);
        assertDoesNotThrow(() -> registry.shutdown().join());
    }

    @Test
    void shutdownBlocksNewRegistrations() {
        registry.shutdown();
        assertThrows(SseRegistryShutdownException.class, () -> registry.createAndRegister("id"));
    }

    @Test
    void shutdownCompletesAllStreams() throws InterruptedException {
        this.registry = createRegistry(100);
        for (int i = 0; i < 100; i++){
            registry.createAndRegister(i);
        }

        registry.shutdown();
        Thread.sleep(10);

        for (SseStream s : registry.getAllStreams()){
            assertTrue(s.isCompleted());
        }

    }

    @Test
    void operationsDuringShutdown_returnFalseOrThrow() {
        this.registry = createRegistry(100);
        for (int i = 0; i < 100; i++){
            registry.createAndRegister(i);
        }

        registry.shutdown();
        var test = new TestEvent(0, null, null);
        assertFalse(this.registry.sendTo(1, test));
        assertThrows(SseRegistryShutdownException.class, () -> registry.broadcast(test));
        assertDoesNotThrow(() -> this.registry.remove(5));

    }

    @Test
    void concurrentRegisterAndRemove_sameId() throws InterruptedException {
        String id = "race-condition-id";
        List<SseStream> createdStreams = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            var stream = SseStream.builder().fromEmitter(new FakeEmitter());
            createdStreams.add(stream);
        }

        // register and remove same ID repeatedly
        CountDownLatch latch = new CountDownLatch(200);

        for (int i = 0; i < 100; i++) {
            final var stream = createdStreams.get(i);
            CompletableFuture.runAsync(() -> {
                registry.register(id, stream);
                latch.countDown();
            }, testExec);
        }

        for (int i = 0; i < 100; i++) {
            CompletableFuture.runAsync(() -> {
                registry.remove(id);
                latch.countDown();
            }, testExec);
        }

        latch.await();

        var finalStream = registry.get(id);
        long completedCount = createdStreams.stream()
                .filter(SseStream::isCompleted)
                .count();
        assertTrue(completedCount > 0, "Some streams should have been completed");
        assertDoesNotThrow(() -> registry.shutdown().join());
    }

    @Test
    void concurrentSendToSameStream() throws InterruptedException {
        FakeEmitter fe = new FakeEmitter();
        this.registry.register(1, SseStream.builder().fromEmitter(fe));
        for (int i = 0; i < 100; i++){
            this.registry.sendTo(1, new TestEvent(i, null, null));
        }

        Thread.sleep(100);
        assertEquals(100, fe.sent.size());
        List<Integer> ids = fe.sent.stream().map(TestEvent::id).toList();
        List<Integer> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        assertEquals(sorted, ids);
    }

    @Test
    void registerSameIdTwice_replacesAndCompletesStream() throws InterruptedException {
        SseStream oldStream = SseStream.builder().build();
        this.registry.register(1, oldStream);
        assertEquals(1, this.registry.size());

        SseStream newStream = SseStream.builder().build();
        this.registry.register(1, newStream);
        Thread.sleep(100); //Wait for the stream to complete

        assertTrue(oldStream.isCompleted());
        assertNotEquals(oldStream, this.registry.get(1));
    }

    @Test
    void sendToCompletedStream_returnsFalse() {
        SseStream stream = SseStream.builder().build();
        stream.complete();
        this.registry.register(1, stream);
        assertFalse(this.registry.sendTo(1, new TestEvent(1, null, null)));

    }

    @Test
    void broadcastWithSlowClient_doesntBlockOthers() throws InterruptedException {
        this.registry = createRegistry(120);
        SseStream stream = SseStream.builder().fromEmitter(new SlowEmitter());
        this.registry.register(0, stream);
        List<FakeEmitter> emitters = new ArrayList<>();

        for (int i = 1; i < 100; i++){
            FakeEmitter e = new FakeEmitter();
            this.registry.register(i, SseStream.builder().fromEmitter(e));
            emitters.add(e);
        }

        this.registry.broadcast(new TestEvent(1, null, null));
        Thread.sleep(10);

        FakeEmitter e = emitters.getFirst();
        SlowEmitter slow = (SlowEmitter) stream.getEmitter();
        assertFalse(e.sent.isEmpty());
        assertTrue(slow.sent.isEmpty());

    }


    private SseRegistry<Object, TestEvent> createRegistry(int maxStreams){
        return SseRegistry.<Object, TestEvent>builder().maxEvents(10).maxStreams(maxStreams).build();
    }

}

class FakeEmitter extends ImmutableSseEmitter {
    public List<TestEvent> sent = new ArrayList<>();
    public Thread senderThread =  null;
    public FakeEmitter() { super(Long.MAX_VALUE); }

    @Override
    public void send(Object obj, MediaType type) throws IOException {
        sent.add((TestEvent) obj);
        senderThread = Thread.currentThread();
    }
}

class ErrorEmitter extends FakeEmitter {
    @Override
    public void send(Object obj, MediaType type) throws IOException{
        throw new IOException("fail");
    }
}

class SlowEmitter extends FakeEmitter {
    public Thread senderThread =  null;
    @Override
    public void send(Object obj, MediaType type) throws IOException{
        senderThread = Thread.currentThread();
        try {
            Thread.sleep(400);
        }catch (InterruptedException e){}
        sent.add((TestEvent) obj);
    }
}

record TestEvent(int id, String name, String content){}
