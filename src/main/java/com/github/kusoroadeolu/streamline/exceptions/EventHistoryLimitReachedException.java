package com.github.kusoroadeolu.streamline.exceptions;

public class EventHistoryLimitReachedException extends ApiException {
    public EventHistoryLimitReachedException(String message) {
        super(message);
    }
}
