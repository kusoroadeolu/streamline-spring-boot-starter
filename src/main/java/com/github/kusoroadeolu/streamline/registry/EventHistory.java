package com.github.kusoroadeolu.streamline.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

//Custom structure to prevent pinning of vthreads
final class EventHistory<E> {
    private final ArrayList<E> events;
    private final ReentrantLock lock;
    private final int maxSize;
    private final static int ZERO = 0;

    public EventHistory(int maxSize) {
        this.maxSize = maxSize;
        this.events = new ArrayList<>();
        this.lock = new ReentrantLock();
    }

    public void add(E event, EventEvictionPolicy policy) {
        this.lock.lock();
        try {
            this.events.add(event);
            if (this.events.size() > this.maxSize) {
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

    public List<E> getAllAfter(int from){
        return this.getFrom(from, this.events.size());
    }

    public List<E> getBetween(int from, int to){
        return this.getFrom(from, to);
    }

    private List<E> getFrom(int f, int l) {
        lock.lock();
        try {
            return this.events.subList(f, l);
        } finally {
            lock.unlock();
        }
    }
}