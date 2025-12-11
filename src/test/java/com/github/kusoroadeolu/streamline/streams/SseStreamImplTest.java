package com.github.kusoroadeolu.streamline.streams;

import com.github.kusoroadeolu.streamline.exceptions.SseStreamCompletedException;
import com.github.kusoroadeolu.streamline.exceptions.SseStreamIOException;
import org.apache.tomcat.util.threads.VirtualThreadExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class SseStreamImplTest {

    private SseStreamBuilderImpl sseStreamBuilderImpl;
    private FakeEmitter emitter;
    private SseStream stream;

    private SseStreamBuilderImpl errorSseStreamBuilderImpl;
    private ErrorEmitter errorEmitter;
    private SseStream errorStream;


    @BeforeEach
    public void onSetup(){
        emitter = new FakeEmitter();
        sseStreamBuilderImpl = new SseStreamBuilderImpl(emitter);
        stream = sseStreamBuilderImpl.build();

        errorEmitter = new ErrorEmitter();
        errorSseStreamBuilderImpl = new SseStreamBuilderImpl(errorEmitter);
        errorStream = errorSseStreamBuilderImpl.build();
    }

    @Test
    void shouldSuccessfullyPopulateList_onSend()  {
        int id = 1;
        String name = "event";
        String content = "content";
        TestEvent testEvent = new TestEvent(id, name, content);

        SseStream stream = sseStreamBuilderImpl.build();
        stream.send(testEvent).join();

        assertFalse(emitter.sent.isEmpty());
        assertEquals(1, emitter.sent.size());
        assertEquals(testEvent, emitter.sent.getFirst());
    }

    @Test
    void shouldOrderEventsCorrectly_onSend()  {
        int id = 1;
        String name = "event";
        String content = "content";
        TestEvent testEvent = new TestEvent(id, name, content);

        int id1 = 2;
        String name1 = "event1";
        String content1 = "content1";
        TestEvent testEvent1 = new TestEvent(id1, name1, content1);

        int id2 = 2;
        String name2 = "event1";
        String content2 = "content1";
        TestEvent testEvent2 = new TestEvent(id2, name2, content2);



        CompletableFuture.allOf(stream.send(testEvent), stream.send(testEvent1), stream.send(testEvent2)).join();

        assertFalse(emitter.sent.isEmpty());
        assertEquals(3, emitter.sent.size());
        assertEquals(testEvent, emitter.sent.getFirst());
        assertEquals(testEvent1, emitter.sent.get(1));
        assertEquals(testEvent2, emitter.sent.get(2));
    }

    @Test
    void shouldRunAsync_onSend(){
        SlowEmitter sm = new SlowEmitter();
        SseStream slowStream = new SseStreamBuilderImpl(sm).build();

        int id = 1;
        String name = "event";
        String content = "content";
        TestEvent testEvent = new TestEvent(id, name, content);
        CompletableFuture<Void> v = slowStream.send(testEvent);

        assertFalse(v.isDone());
        assertTrue(sm.senderThread.isVirtual());
        assertNotEquals(Thread.currentThread(), sm.senderThread);
    }

    @Test
    void shouldPropagateSseIOException_onIOEx(){
        int id = 1;
        String name = "event";
        String content = "content";
        TestEvent testEvent = new TestEvent(id, name, content);
        CompletableFuture<Void> v = errorStream.send(testEvent);

        assertFalse(v.isDone());
        var ex = assertThrows(CompletionException.class, v::join);
        assertEquals(SseStreamIOException.class, ex.getCause().getClass());
    }

    @Test
    void shouldThrowStreamCompletedException_onStreamComplete(){
        int id = 1;
        String name = "event";
        String content = "content";
        TestEvent testEvent = new TestEvent(id, name, content);
        stream.send(testEvent).join();
        stream.complete();
        assertThrows(SseStreamCompletedException.class, () -> stream.send(testEvent));
    }

    @Test
    void shouldHandleConcurrentSends(){
        String name = "event";
        String content = "content";
        ArrayList<CompletableFuture<Void>> futures = new ArrayList<>();
        try(ExecutorService executor = new VirtualThreadExecutor("")){
            for (int i = 0; i < 1000; i++){
                TestEvent testEvent = new TestEvent(i, name, content);
                futures.add(CompletableFuture.runAsync(() -> stream.send(testEvent), executor));

            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        }

        assertEquals(1000, emitter.sent.size());
    }

    @Test
    void shouldNotSendAfterComplete_underConcurrentSends(){
        String name = "event";
        String content = "content";
        ArrayList<CompletableFuture<Void>> futures = new ArrayList<>();
        try(ExecutorService executor = new VirtualThreadExecutor("")){
            for (int i = 0; i < 100; i++){
                TestEvent testEvent = new TestEvent(i, name, content);
                futures.add(CompletableFuture.runAsync(() -> stream.send(testEvent), executor));
                if (i == 30) stream.complete();
            }

            try{
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            }catch (CompletionException e){

            }
        }

        assertTrue(emitter.sent.size() <= 30);
        assertTrue(emitter.sent.stream().map(TestEvent::id).allMatch(i -> i <= 30));
    }

    @Test
    void shouldDoNothing_whenCompleteIsCalled_afterStreamIsCompleted(){
        stream.complete();
        assertDoesNotThrow(() -> stream.complete());
    }

    @Test
    void shouldThrowNothing_onSlowSend(){
        SlowEmitter sm = new SlowEmitter();
        SseStream slowStream = new SseStreamBuilderImpl(sm).build();

        int id = 1;
        String name = "event";
        String content = "content";
        TestEvent testEvent = new TestEvent(id, name, content);
        var v = slowStream.send(testEvent);
        stream.complete();

        assertDoesNotThrow(v::join);
    }


    @Test
    void onEmitterComplete_shouldNotThrow_OnStreamComplete(){
        stream.getEmitter().complete();
        assertDoesNotThrow(() -> stream.complete());
    }



}

record TestEvent(int id, String name, String content){}

class FakeEmitter extends ImmutableSseEmitter{
    public List<TestEvent> sent = new ArrayList<>();
    public Thread senderThread =  null;
    public FakeEmitter() { super(Long.MAX_VALUE); }

    @Override
    public void send(Object obj, MediaType type) throws IOException{
        sent.add((TestEvent) obj);
        senderThread = Thread.currentThread();
    }
}

class ErrorEmitter extends FakeEmitter{
    @Override
    public void send(Object obj, MediaType type) throws IOException{
        throw new IOException("fail");
    }
}

class SlowEmitter extends FakeEmitter{
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