package com.github.kusoroadeolu.streamline.registry;

import com.github.kusoroadeolu.streamline.exceptions.EventHistoryLimitReachedException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

//Custom structure to prevent pinning of vthreads with synchronized blocks
final class EventHistory<E> {
    private final ArrayList<E> events;
    private final ReentrantLock lock;
    private final int maxSize;
    private final static String ERR_MESSAGE = "Event history max size of %s has been reached.";

    public EventHistory(int maxSize) {
        this.maxSize = maxSize;
        this.events = new ArrayList<>();
        this.lock = new ReentrantLock();
    }

    public void add(E event, EventEvictionPolicy policy, Predicate<E> eventPredicate) {
        this.lock.lock();
        try {
            if (eventPredicate != null && eventPredicate.test(event)){
                if (this.events.size() < this.maxSize) return;
                switch (policy){
                    case STRICT -> throw new EventHistoryLimitReachedException(ERR_MESSAGE.formatted(this.events.size()));
                    case FIFO -> this.events.removeFirst();
                    case LIFO -> this.events.removeLast();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void remove(E event){
        if(this.events.isEmpty()) return;
        this.lock.lock();
        try {
            if (this.events.isEmpty()) return;
            this.events.remove(event);
        }finally {
            this.lock.unlock();
        }
    }



    public List<E> getAll() {
        if (this.events.isEmpty()) return List.of();
        lock.lock();
        try {
            return Collections.unmodifiableList(this.events);
        } finally {
            lock.unlock();
        }
    }

}