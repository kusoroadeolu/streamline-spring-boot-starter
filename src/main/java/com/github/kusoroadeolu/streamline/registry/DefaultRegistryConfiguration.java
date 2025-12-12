package com.github.kusoroadeolu.streamline.registry;


import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DefaultRegistryConfiguration {

        @Bean(name = "defaultSseRegistry")
        @ConditionalOnMissingBean(SseRegistry.class)
        public SseRegistry<String, Object> defaultSseRegistry() {
            return SseRegistry.<String, Object>builder()
                    .maxStreams(1000)
                    .maxEvents(100)
                    .streamTimeout(60_000L)
                    .build();
        }
}
