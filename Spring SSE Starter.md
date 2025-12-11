Spring SSE Starter

A thread-safe, blocking SSE (Server-Sent Events) starter for Spring Boot using virtual threads.

Why This Exists

Spring's built-in SSE support (SseEmitter) is bare-bones. You get the emitter, but managing them safely across threads, handling cleanup, broadcasting, reconnection - all that's manual boilerplate every time.

This starter provides:

Thread-safe SSE channel management

Automatic cleanup on disconnect/timeout/error

Event replay for reconnecting clients

Centralized registries per topic

Traditional blocking concurrency (not reactive)

Philosophy

Blocking over Reactive: Uses traditional concurrency patterns (virtual threads, explicit synchronization) instead of reactive programming. Virtual threads make blocking I/O viable at scale.

Registry-per-topic: Each topic gets its own registry bean, not one mega-registry with topic routing inside it.

Simple and explicit: No magic. You register channels, broadcast events, handle cleanup. Clear, debuggable code.

Core Concepts

SSE Channel
A wrapper around Spring's SseEmitter with:
Single virtual thread executor for thread-safe sends
Configurable timeouts and retry settings
Auto-registration with cleanup hooks

class SseChannel{
SseEmitter, Executor service(Single Virtual Thread executor service NOT Virtual thread per task executor service)
}

SSE Registry<ID, UserEvent>

Thread-safe storage and management for channels:

ConcurrentHashMap<String, SseChannel> for O(1) lookups
ConcurrentLinkedDeque<UserEvent> events;
Event history buffer (configurable size)
Broadcasting with virtual threads (one slow client doesn't block others)
Event replay for reconnecting clients (via Last-Event-ID)

SSE Cleanup

Lifecycle management:
Auto-removes channels on complete/timeout/error
Graceful executor shutdown on Spring context close
Leak detection and warnings

Basic Usage

1.  Create a Registry Bean
    @Configuration
    public class SseConfig {
        @Bean("ordersRegistry")

        public SseRegistry<UUID, OrderEvent> ordersRegistry() { - UUID Mapped to the Sse channel, not the Order event

            return SseRegistry.builder()
                .maxChannels(1000)
                .maxEvents(100)  // Keep last 100 events for replay
                .onChannelComplete(Runnable r)
                .onChannelError(e -> log.err(e))
                .allowEvent(e -> e.orderType == OrderType.SOME_ORDER_TYPE)
                .build();

        }



        @Bean("notificationsRegistry")
        public SseRegistry<UUID, NotificationEvent> notificationsRegistry() {  - UUID Mapped to the Sse channel, not the Notification event

            return SseRegistry.builder()
                .maxChannels(5000)
                .maxEvents(50)
                .onChannelComplete(Runnable r)
                .onChannelError(e -> log.err(e))
                .allowEvent(e -> e.notificationType == OrderType.SOME_NOTIFICATION_TYPE)  //Allow event
                .build();

        }

}

2. Manual Channel Management
   @RestController

public class OrdersController {  
 @Autowired
@Qualifier("ordersRegistry")

    private SseRegistry<OrderEvent> ordersRegistry;
    @GetMapping("/sse/orders")

    public SseEmitter subscribe(@RequestParam String userId) {
        SseChannel channel = ordersRegistry.createAndRegister(userId);
        return channel.getEmitter();
    }

}

3. With Meta Annotation (Optional)
   @RestController

public class OrdersController {
@SseEndpoint(registry = "ordersRegistry")
@GetMapping("/sse/orders")
public SseEmitter subscribe(@RequestParam String userId) {
// Channel automatically created and registered
// Returns emitter directly
}
}

Event Replay
When a client reconnects with Last-Event-ID header:
@GetMapping("/sse/orders")
public SseEmitter subscribe(

    @RequestParam String userId,

    @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId

) {
SseChannel channel = ordersRegistry.createAndRegister(userId);
if (lastEventId != null) {

        ordersRegistry.replayTo(userId, lastEventId);

    }

    return channel.getEmitter();

}

The registry will:

1. Find the event with matching ID in history

2. Send all events after it to the channel

3. Continue with live events

Registry Operations

// Create and register a channel

SseChannel channel = registry.createAndRegister("user-123");

// Manual registration  
SseChannel channel = new SseChannel(config);
registry.register("user-123", channel);

// Broadcast to all channels  
registry.broadcast(event);

// Replay to specific channel

registry.replayTo("user-123", lastEventId);

// Replay all history to specific channel

registry.replayTo("user-123");

// Replay to all channels

registry.replayToAll();

// Remove channel manually (usually auto-handled)

registry.remove("user-123");

// Get current size

int activeChannels = registry.size();

Configuration

SseChannel channel = SseChannel.builder()
.timeout(30_000L) // 30 seconds
.retryInterval(3000L) // Client retry after 3 seconds
.onError(error -> log.error("Channel error", error))
.onComplete(() -> log.info("Channel completed"))
.build();

SseRegistry Config

SseRegistry<String, UserEvent> registry = SseRegistry.builder()
.maxChannels(1000) // Soft limit (logs warning, doesn't reject)
.maxEvents(100) // Ring buffer size for event history
.name("orders") // For logging/metrics
.acceptEvent(e -> e.userattr == some unknown value)
.onChannelError(e -> log.error()) //Centralizes config for all sse channels
.onChannelComplete(() -> log.info)
.build();

Architecture Decisions

Why Virtual Threads?

Blocking I/O becomes viable at scale
Simpler than reactive programming

Why Registry-per-topic?
Simpler implementation (no topic routing)
Clear separation of concerns
Each registry independently configurable

Topic = which registry bean you inject

Why User-provided Channel IDs?
User knows their domain (user IDs, session IDs, etc.)
Enables reconnection with same ID
Predictable for debugging

Event Storage Limits
Max events: Ring buffer prevents unbounded memory growth
Max channels: Soft limit with warnings, doesn't reject (for now)
No predicates yet: Store all events, typed event filtering comes later

Cleanup Guarantees
Channels are automatically removed when:
Client disconnects
Timeout expires
Error occurs during send
Spring context shuts down (all registries cleaned)
Executors are gracefully shutdown on Spring context close.

Thread Safety
ConcurrentHashMap for registry storage
Virtual thread per channel for sends (no blocking)
Event history uses concurrent data structures
No explicit locks needed in typical usage

Future Enhancements (Not in v1)
[ ] TTL-based event expiration (currently size-based only)
[ ] Hard limits on registry size with eviction policies
[ ] Metrics and monitoring integration
[ ] Backpressure handling
[ ] Event batching

Requirements

Java 21+ (virtual threads)

Spring Boot 3.x

Non-Goals

Not a reactive library (use WebFlux if you want that)

Not distributed (single-instance only)

Not a full pub/sub system

Not production-optimized (yet)

License - Apache or MIT
