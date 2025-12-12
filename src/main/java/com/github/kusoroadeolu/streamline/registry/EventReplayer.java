package com.github.kusoroadeolu.streamline.registry;

import com.github.kusoroadeolu.streamline.streams.SseStream;
import com.github.kusoroadeolu.streamline.utils.ApiUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

import static com.github.kusoroadeolu.streamline.utils.ApiUtils.*;


public final class EventReplayer<ID, E>{
    private final ExecutorService executorService;
    private final List<E> events;
    private final Map<ID, SseStream> streams;
    private final int from;
    private final int to;
    private final boolean all;
    private final Predicate<E> matching;
    private static final String NULL_ID_MESSAGE = "Id cannot be null";


    EventReplayer(EventReplayBuilder<ID, E> eventReplayBuilder) {
        this.executorService = eventReplayBuilder.executorService;
        this.events = eventReplayBuilder.events;
        this.streams = eventReplayBuilder.streams;
        this.from = eventReplayBuilder.from;
        this.to = eventReplayBuilder.to;
        this.all = eventReplayBuilder.all;
        this.matching = eventReplayBuilder.matching;
     }

     public CompletableFuture<Void> to(ID id){
        assertNotNull(id, NULL_ID_MESSAGE);
        final var stream = this.streams.get(id);
        if(stream == null) return CompletableFuture.failedFuture(new NullPointerException());
        return stream.send(this.eventsToSend());
     }

     public CompletableFuture<Void> toAll(){
         final var futures = new ArrayList<CompletableFuture<Void>>();
         final var events  = this.eventsToSend();
         streams.forEach((id, s) -> futures.add(CompletableFuture.runAsync(() -> s.send(events), this.executorService)));
         return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
     }

     private List<E> eventsToSend(){
        if (this.all) return this.events;
        else if (this.matching != null) return this.getMatchingEvents();
        else if (this.from != -1 && this.to == -1) return this.events.subList(from, this.events.size());
        else return this.events.subList(from, to);
    }

    private List<E> getMatchingEvents() {
        return this.events.stream().filter(this.matching).toList();
    }
}

final class EventReplayBuilder<ID, E> {
    int from = -1;
    int to = -1;
    boolean all;
    Predicate<E> matching;
    final ExecutorService executorService;
    final List<E> events;
    final Map<ID, SseStream> streams;
    private final static String PREDICATE_NULL_MESSAGE = "Predicate cannot be null";
    private final static String FROM_INVALID_MESSAGE = "From index must pertain to list bounds";
    private final static String TO_INVALID_MESSAGE = "To index must pertain to list bounds";
    private final static String GREATER_THAN_MESSAGE = "To index must be greater than or equal to from index";

    public EventReplayBuilder(ExecutorService executorService, List<E> events, Map<ID, SseStream> streams) {
        this.executorService = executorService;
        this.events = events;
        this.streams = streams;
    }


    public EventReplayer<ID, E> from(int from) {
        assertBetween(from, 0, this.events.size() - 1, FROM_INVALID_MESSAGE);
        this.from = from;
        return new EventReplayer<>(this);
    }

    public EventReplayer<ID, E> between(int from, int to) {
        assertTrue(to >= from, GREATER_THAN_MESSAGE);
        assertBetween(from, 0, this.events.size() - 1, FROM_INVALID_MESSAGE);
        assertBetween(to, 0, this.events.size() - 1, TO_INVALID_MESSAGE);
        this.to = to;
        this.from = from;
        return new EventReplayer<>(this);    }

    public EventReplayer<ID, E> all() {
        this.all = true;
        return new EventReplayer<>(this);    }

    public EventReplayer<ID, E> matching(Predicate<E> matching) {
        assertNotNull(matching, PREDICATE_NULL_MESSAGE);
        this.matching = matching;
        return new EventReplayer<>(this);
    }
}
