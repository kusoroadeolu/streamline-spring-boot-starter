package com.github.kusoroadeolu.streamline;

import com.github.kusoroadeolu.streamline.registry.SseRegistry;
import com.github.kusoroadeolu.streamline.streams.SseStream;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/test")
class SseTestController {

    private final SseRegistry<String, TestEvent> registry;

    public SseTestController(SseRegistry<String, TestEvent> registry) {
        this.registry = registry;
    }

    @GetMapping("/stream/{id}")
    public SseEmitter subscribe(@PathVariable String id) {
        SseStream stream = registry.createAndRegister(id);
        return stream.getEmitter();
    }

    @PostMapping("/broadcast")
    public void broadcast(@RequestBody TestEvent event) {
        registry.broadcast(event);
    }

    @PostMapping("/send/{id}")
    public void sendTo(@PathVariable String id, @RequestBody TestEvent event) {
        registry.sendTo(id, event);
    }
}



