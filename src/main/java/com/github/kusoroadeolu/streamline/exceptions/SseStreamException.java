package com.github.kusoroadeolu.streamline.exceptions;

public class SseStreamException extends ApiException {
    public SseStreamException() {
    }

    public SseStreamException(Throwable cause) {
        super(cause);
    }

    public SseStreamException(String message) {
        super(message);
    }
}
