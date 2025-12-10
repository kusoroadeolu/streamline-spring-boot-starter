package com.github.kusoroadeolu.streamline.streams;

import com.github.kusoroadeolu.streamline.exceptions.SseStreamCompletedException;
import com.github.kusoroadeolu.streamline.exceptions.SseStreamIOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class SseStreamImplTest {

    private SseStreamImplBuilder sseStreamImplBuilder;
    private FakeEmitter emitter;
    private SseStream stream;

    private SseStreamImplBuilder errorSseStreamImplBuilder;
    private ErrorEmitter errorEmitter;
    private SseStream errorStream;

    @BeforeEach
    public void onSetup(){
        emitter = new FakeEmitter();
        sseStreamImplBuilder = new SseStreamImplBuilder(emitter);
        stream = sseStreamImplBuilder.build();

        errorEmitter = new ErrorEmitter();
        errorSseStreamImplBuilder = new SseStreamImplBuilder(errorEmitter);
        errorStream = errorSseStreamImplBuilder.build();
    }

    @Test
    void shouldSuccessfullyPopulateList_onSend()  {
        int id = 1;
        String name = "event";
        String content = "content";
        TestEvent testEvent = new TestEvent(id, name, content);

        SseStream stream = sseStreamImplBuilder.build();
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
        int id = 1;
        String name = "event";
        String content = "content";
        TestEvent testEvent = new TestEvent(id, name, content);
        CompletableFuture<Void> v = stream.send(testEvent);

        assertFalse(v.isDone());
        assertTrue(emitter.senderThread.isVirtual());
        assertNotEquals(Thread.currentThread(), emitter.senderThread);
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

}

record TestEvent(int id, String name, String content){}

class FakeEmitter extends SseEmitter{
    public List<Object> sent = new ArrayList<>();
    public Thread senderThread =  null;
    public FakeEmitter() { super(Long.MAX_VALUE); }

    @Override
    public void send(Object obj, MediaType type) throws IOException{
        sent.add(obj);
        senderThread = Thread.currentThread();
    }
}

class ErrorEmitter extends FakeEmitter{
    @Override
    public void send(Object obj, MediaType type) throws IOException{
        throw new IOException("fail");
    }
}