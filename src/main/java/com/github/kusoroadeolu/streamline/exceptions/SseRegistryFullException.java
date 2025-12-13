package com.github.kusoroadeolu.streamline.exceptions;

public class SseRegistryFullException extends SseRegistryException {
    public SseRegistryFullException(String message) {
        super(message);
    }

    public SseRegistryFullException() {
        super();
    }
}
