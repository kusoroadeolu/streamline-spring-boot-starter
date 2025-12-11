package com.github.kusoroadeolu.streamline.exceptions;

public class SseRegistryFullException extends ApiException {
    public SseRegistryFullException(String message) {
        super(message);
    }

    public SseRegistryFullException() {
        super();
    }
}
