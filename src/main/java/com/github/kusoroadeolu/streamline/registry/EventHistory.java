package com.github.kusoroadeolu.streamline.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

//Custom structure to prevent pinning of vthreads with synchronized blocks
final class EventHistory<E> {
    private final ArrayList<E> events;
    private final ReentrantLock lock;
    private final int maxSize;

    public EventHistory(int maxSize) {
        this.maxSize = maxSize;
        this.events = new ArrayList<>();
        this.lock = new ReentrantLock();
    }

    public void add(E event, EventEvictionPolicy policy, Predicate<E> eventPredicate) {
        this.lock.lock();
        try {
            if (eventPredicate != null && eventPredicate.test(event)){
              this.events.add(event);
                if (this.events.size() < this.maxSize) return;
                switch (policy){
                    case FIFO -> this.events.removeFirst();
                    case LIFO -> this.events.removeLast();
                }

            }
        } finally {
            lock.unlock();
        }
    }

    public List<E> getAll() {
        lock.lock();
        try {
            return new ArrayList<>(this.events);
        } finally {
            lock.unlock();
        }
    }

}