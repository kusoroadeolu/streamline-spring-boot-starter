package com.github.kusoroadeolu.streamline;

import com.github.kusoroadeolu.streamline.registry.SseRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SseIntegrationTest {

    @Autowired
    private SseRegistry<String, TestEvent> registry;

    @Test
    void shouldInjectDefaultRegistry() {
        assertNotNull(registry);
    }

    @Test
    void shouldHandleMultipleStreamsInSpringContext() {
        for (int i = 0; i < 100; i++) {
            registry.createAndRegister("user" + i);
        }

        assertEquals(100, registry.size());

        assertDoesNotThrow(() -> registry.broadcast(new TestEvent(1, "", "")));

        registry.shutdown().join();
        assertTrue(registry.isShutdown());
    }

    @Test
    void shouldHandleConcurrentOperationsInSpringContext()  {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = 0; i < 500; i++) {
                final int id = i;
                futures.add(CompletableFuture.runAsync(() ->
                        registry.createAndRegister("user" + id), executor));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            assertTrue(registry.size() > 0);

        } finally {
            executor.close();
        }
    }
}