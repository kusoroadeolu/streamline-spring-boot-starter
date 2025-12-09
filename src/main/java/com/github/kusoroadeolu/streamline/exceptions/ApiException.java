package com.github.kusoroadeolu.streamline.exceptions;

public class ApiException extends RuntimeException {
    public ApiException(String message) {
        super(message);
    }

    public ApiException() {
        super();
    }

    public ApiException(Throwable cause) {
        super(cause);
    }
}
