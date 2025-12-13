package com.github.kusoroadeolu.streamline.exceptions;

public class SseStreamCompletedException extends SseStreamException {

    public SseStreamCompletedException() {
        super();
    }

    public SseStreamCompletedException(String message) {
        super(message);
    }
}
