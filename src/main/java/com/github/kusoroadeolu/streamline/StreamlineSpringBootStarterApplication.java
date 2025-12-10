package com.github.kusoroadeolu.streamline;

import com.github.kusoroadeolu.streamline.streams.ImmutableSseEmitter;
import com.github.kusoroadeolu.streamline.streams.SseStream;
import com.github.kusoroadeolu.streamline.streams.SseStreamBuilder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@SpringBootApplication
public class StreamlineSpringBootStarterApplication {
    public static void main(String[] args) throws InterruptedException {
        SpringApplication.run(StreamlineSpringBootStarterApplication.class, args);
    }


}

