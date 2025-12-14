package com.github.kusoroadeolu.streamline.registry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class EventHistoryTest {
    @Test
    void shouldActuallyAddEventsToHistory() {
        EventHistory<String> history = new EventHistory<>(5);

        history.add("event1", EventEvictionPolicy.FIFO, e -> true);
        history.add("event2", EventEvictionPolicy.FIFO, e -> true);

        List<String> events = history.getAll();
        assertEquals(2, events.size());
        assertTrue(events.contains("event1"));
        assertTrue(events.contains("event2"));
    }

    @Test
    void shouldRespectPredicateFilter() {
        EventHistory<Integer> history = new EventHistory<>(5);

        // Only allow even numbers
        Predicate<Integer> onlyEvens = n -> n % 2 == 0;

        history.add(1, EventEvictionPolicy.FIFO, onlyEvens); // Should be filtered
        history.add(2, EventEvictionPolicy.FIFO, onlyEvens); // Should be added
        history.add(3, EventEvictionPolicy.FIFO, onlyEvens); // Should be filtered
        history.add(4, EventEvictionPolicy.FIFO, onlyEvens); // Should be added

        List<Integer> events = history.getAll();
        assertEquals(2, events.size());
        assertEquals(List.of(2, 4), events);
    }
}