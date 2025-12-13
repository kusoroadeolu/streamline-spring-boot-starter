package com.github.kusoroadeolu.streamline.registry;


import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DefaultRegistryConfiguration {

        @Bean(name = "defaultSseRegistry", destroyMethod = "shutdown")
        @ConditionalOnMissingBean(SseRegistry.class)
        public SseRegistry<Object, Object> defaultSseRegistry() {
            return SseRegistry.builder()
                    .maxStreams(100)
                    .maxEvents(100)
                    .build();
        }
}
