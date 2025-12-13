package com.github.kusoroadeolu.streamline;

import com.github.kusoroadeolu.streamline.registry.SseRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TestConfiguration {
    @Bean(name = "registry")
    public SseRegistry<String, TestEvent> defaultSseRegistry() {
        return SseRegistry.<String, TestEvent>builder()
                .maxStreams(1000)
                .maxEvents(100)
                .streamTimeout(60_000L)
                .build();
    }
}
