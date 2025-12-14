# Streamline
Thread safe SSE management for Spring Boot using virtual threads.

## ⚠️ Important Release Notice
> **v1.0.3 is the first stable and usable release.**  
> Versions `v1.0.0` and `v1.0.1` and `v1.0.2` failed to build due to a packaging misconfiguration and  cannot be used.


## What is SSE
Server Sent Events (SSE) is a standard HTTP mechanism for pushing unidirectional event streams from server to browser over a long lived http connection.

## What is this?
A Spring Boot library that wraps Spring's `SseEmitter` with thread-safe management, automatic cleanup, and event replay. Built on virtual threads for blocking I/O at scale.

## What problem does it solve?
Spring's `SseEmitter` gives you the emitter, but nothing else. Every SSE implementation ends up writing the same boilerplate:

**Without Streamline:**
```java
// Manual thread-safety, cleanup, and dead connection tracking
private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
private final ReentrantLock lock = new ReentrantLock();

public void broadcast(Event event) {
    lock.lock();
    try {
        List<String> dead = new ArrayList<>();
        emitters.forEach((id, emitter) -> {
            try { emitter.send(event); } 
            catch (IOException e) { dead.add(id); }
        });
        dead.forEach(emitters::remove);
    } finally { lock.unlock(); }
}
// + auto removal on death, event history, reconnection replay, shutdown hooks
```

**With Streamline:**
```java
private final SseRegistry<CustomId, Event> registry; 

registry.broadcast(event);  // All of the above handled
```

Streamline handles concurrency, lifecycle, cleanup, and replay so you don't have to.

## Streamline Mental Model

Streamline has three core components that work together:

```
┌─────────────────────────────────────────────────────────┐
│                      SseRegistry                        │
│  • Manages multiple SseStreams by ID                    │
│  • Stores event history for replay                      │
│  • Coordinates broadcasting and cleanup                 │
└────────────────┬────────────────────────────────────────┘
                 │
                 │ creates/manages
                 │
        ┌────────▼─────────┐  ┌──────────────┐  ┌───────────────┐
        │    SseStream     │  │  SseStream   │  │  SseStream    │
        │  ID: "user-123"  │  │ ID:"user-456 │  │ ID:"user-789" │
        │                  │  │              │  │               │
        │  ┌────────────┐  │  │ ┌──────────┐ │  │ ┌──────────┐  │
        │  │  Virtual   │  │  │ │ Virtual  │ │  │ │ Virtual  │  │
        │  │  Thread    │  │  │ │ Thread   │ │  │ │ Thread   │  │
        │  │  Executor  │  │  │ │ Executor │ │  │ │ Executor │  │
        │  └─────┬──────┘  │  │ └────┬─────┘ │  │ └────┬─────┘  │
        │        │         │  │      │       │  │      │        │
        │  ┌─────▼──────┐  │  │ ┌────▼─────┐ │  │ ┌────▼─────┐  │
        │  │ Immutable  │  │  │ │Immutable │ │  │ │Immutable │  │
        │  │ SseEmitter │  │  │ │SseEmitter│ │  │ │SseEmitter│  │
        │  └────────────┘  │  │ └──────────┘ │  │ └──────────┘  │
        └──────────────────┘  └──────────────┘  └───────────────┘
                 │                    │                 │
                 └────────────────────┴─────────────────┘
                                      │
                              HTTP Connections
                                      │
                           ┌──────────▼──────────┐
                           │   Client Browsers   │
                           └─────────────────────┘
```

### Components

**SseRegistry**: The central manager
- Maps IDs (user IDs, session IDs, etc.) to `SseStream` instances
- Stores event history in a bounded buffer with configurable eviction
- Broadcasts events to all streams or sends to specific IDs
- Handles lifecycle (creation, removal, shutdown)
- One registry per topic/domain (orders, notifications, etc.)

**SseStream**: Thread-safe wrapper for a single connection
- Wraps an `ImmutableSseEmitter` (Spring's `SseEmitter`)
- Uses a single virtual thread executor for sequential sends
- Bounded event queue prevents memory exhaustion from slow clients
- State machine: `ACTIVE` → `COMPLETED`
- Auto-cleanup on complete/timeout/error

**ImmutableSseEmitter**: Protected `SseEmitter`
- Callbacks (`onTimeout`, `onError`, `onCompletion`) can only be set once
- Prevents accidental overwrites when passing across service boundaries
- Still a Spring `SseEmitter` - return it from `@GetMapping` endpoints

### Flow
1. Client hits endpoint: `GET /stream?userId=user-123`
2. Controller calls `registry.createAndRegister("user-123")`
3. Registry creates `SseStream` with dedicated virtual thread
4. Controller returns `stream.getEmitter()` (Spring handles HTTP)
5. Service broadcasts: `registry.broadcast(event)`
6. Registry dispatches to all streams via virtual threads (parallel, non-blocking)
7. Each stream's executor queues and sends sequentially (ordering preserved per stream)
8. Client disconnects → Spring calls callback → Stream marks `COMPLETED` → Registry removes stream

## Quick Start
**1. Add dependency:**
[![](https://jitpack.io/v/kusoroadeolu/streamline-spring-boot-starter.svg)](https://jitpack.io/#kusoroadeolu/streamline-spring-boot-starter)
**Maven**
```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.kusoroadeolu</groupId>
    <artifactId>streamline-spring-boot-starter</artifactId>
    <version>1.0.2</version>
</dependency>
```
**Gradle**
```gradle
dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
		repositories {
			mavenCentral()
			maven { url 'https://jitpack.io' }
	}
}

dependencies {
	implementation 'com.github.kusoroadeolu:streamline-spring-boot-starter: 1.0.2'
}
```

**2. Create a registry bean:**

```java
@Configuration
public class SseConfig {
    @Bean(destroyMethod = "shutdown")
    public SseRegistry<String, OrderEvent> ordersRegistry() {
        return SseRegistry.<String, OrderEvent>builder()
            .maxStreams(1000)
            .maxEvents(100)
            .build();
    }
}
```

**3. Create an endpoint:**

```java
@RestController
public class OrderController {
    
    private final SseRegistry<String, OrderEvent> ordersRegistry;
    
    public OrderController(SseRegistry<String, OrderEvent> ordersRegistry){
        this.ordersRegistry = ordersRegistry;
    }
    
    @GetMapping("/orders/stream")
    public SseEmitter subscribe(@RequestParam String userId) {
        SseStream stream = ordersRegistry.createAndRegister(userId);
        return stream.getEmitter();
    }
}
```

**4. Broadcast events:**

```java
@Service
public class OrderService {
    
    private final SseRegistry<String, OrderEvent> ordersRegistry;

    public OrderController(SseRegistry<String, OrderEvent> ordersRegistry){
        this.ordersRegistry = ordersRegistry;
    }
    
    public void processOrder(Order order) {
        OrderEvent event = new OrderEvent(order.getId(), "PROCESSING");
        ordersRegistry.broadcast(event);
    }
}
```

**5. Client connects:**
```javascript
const es = new EventSource('/orders/stream?userId=user-123');
es.onmessage = (e) => console.log(JSON.parse(e.data));
```


## Auto Configuration
Streamline provides an autoconfigured SseRegistry bean when added to your project. No manual configuration required unless you want to customize it

```java
        @Bean(name = "defaultSseRegistry", destroyMethod = "shutdown")
        @ConditionalOnMissingBean(SseRegistry.class)
        public SseRegistry<Object, Object> defaultSseRegistry() {
            return SseRegistry.builder()
                    .maxStreams(100)
                    .maxEvents(100)
                    .build();
        }
```

## Requirements

| Component | Requirement |
|-----------|-------------|
| **Java** | 21+ (virtual threads) |
| **Spring Boot** | 3.x |
| **Architecture** | Single-instance only (not distributed) |
| **Concurrency Model** | Blocking I/O + virtual threads (not reactive) |

**Stack Fit For:**
- Traditional Spring MVC applications
- Spring Boot apps needing SSE with simple concurrency
-  Teams avoiding reactive programming complexity

**Stack Unfit for:**
- WebFlux/reactive applications (use built-in reactive SSE instead)
- Multi-instance deployments requiring sticky sessions or shared state

## Constraints & Failure Modes

### Hard Limits

| Constraint | Behavior | Impact |
|------------|----------|--------|
| **maxStreams exceeded** | Throws `SseRegistryFullException` | Client can't connect |
| **maxEvents exceeded** | Evicts based on policy (FIFO/LIFO) or throws (STRICT) | Older events lost for replay |
| **Stream queue full** | `send()` blocks until space available | Slow client can delay that stream's processing |
| **Registry shutdown** | Throws `SseRegistryShutdownException` on new ops | Can't register new streams during shutdown |
| **Stream completed** | Throws `SseStreamCompletedException` on send | Event not delivered to that client |

### Eviction Policies

```java
.eventEvictionPolicy(EventEvictionPolicy.FIFO)  // Remove oldest (default)
.eventEvictionPolicy(EventEvictionPolicy.LIFO)  // Remove newest
.eventEvictionPolicy(EventEvictionPolicy.STRICT) // Throw exception
```

### Failure Scenarios

**Slow Client:**
- Stream has bounded queue (`maxQueuedEventsPerStream()`, default 50)
- If queue fills, executor blocks on `send()` until space available
- Other streams unaffected (each has dedicated virtual thread)
- Configure lower `maxQueuedEventsPerStream()` for aggressive eviction

**Client Disconnect:**
- Spring detects disconnect and calls `emitter.onCompletion()` or `emitter.onError()`
- Stream marks itself `COMPLETED` and shuts down executor
- Registry removes stream from map
- Automatic, no manual cleanup needed

**Broadcast During Shutdown:**
- Registry checks `isShutdown()` before accepting events
- Already-submitted broadcasts complete normally
- New broadcasts after shutdown throw `SseRegistryShutdownException`
- Race condition possible but doesn't corrupt state (event just not delivered)

**Memory Exhaustion:**
- Bounded event history (`maxEvents`)
- Bounded per-stream queue (`maxQueuedEventsPerStream`)
- Soft limit on `maxStreams` (throws exception, doesn't evict)
- No unbounded growth

**Thread Starvation:**
- Uses virtual threads (lightweight, millions possible)
- Each stream gets one virtual thread for dispatching events + guaranteed event ordering(no thread pool contention)
- Registry uses `Executors.newVirtualThreadPerTaskExecutor()` for broadcasts
- Blocking operations don't starve platform threads

### When It Breaks

**You'll have problems if:**
- Clients never disconnect and you hit `maxStreams`
- Event rate exceeds client consumption rate for extended periods
- Events are very large (>1MB) and you have many concurrent streams
- You need guaranteed delivery (SSE is best-effort, use a message queue instead)
- You need multi-instance deployments with shared state (use Redis + pub/sub instead)

**Debug checklist:**
```java
// Check active streams
int active = registry.size();

// Check stream queue size
SseStream stream = registry.get(userId);
int queued = stream.queueSize();

// Monitor shutdown
registry.isShutdown();

// Check if specific stream completed
stream.isCompleted();
```

## Configuration Reference

```java
SseRegistry.<String, Event>builder()
    // Capacity
    .maxStreams(1000)              // Max concurrent streams (soft limit)
    .maxEvents(100)                // Event history size
    .maxQueuedEventsPerStream(50)  // Per-stream send queue
    
    // Timeouts
    .streamTimeout(30_000L)        // Stream timeout in ms
    .streamThreadKeepAliveTime(60) // Executor keep-alive in seconds
    
    // Event handling
    .eventEvictionPolicy(EventEvictionPolicy.FIFO)  // FIFO, LIFO, STRICT
    .allowEvents(e -> e.isValid()) // Filter events before storing
    
    // Callbacks (called on registry executor)
    .onStreamComplete(() -> log.info("Stream completed"))
    .onStreamTimeout(() -> log.warn("Stream timeout"))
    .onStreamError(e -> log.error("Stream error", e))
    
    .build();
```

## Event Replay
Reconnecting clients can replay missed events using event history:

```java
@GetMapping("/stream")
public SseEmitter subscribe(
    @RequestParam String userId,
    @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
) {
    SseStream stream = registry.createAndRegister(userId);
    
    if (lastEventId != null) {
        registry.replay()
            .matching(e -> e.getId().compareTo(lastEventId) > 0)
            .to(userId);
    }
    
    return stream.getEmitter();
}
```

**Replay options:**
```java
registry.replay().all().to(userId);                    // All events
registry.replay().from(10).to(userId);                 // From index
registry.replay().between(5, 20).to(userId);           // Range
registry.replay().matching(e -> e.urgent).to(userId);  // Filter
registry.replay().all().toAll();                       // Broadcast replay
```

## Advanced Usage

### Multiple Registries

```java
@Configuration
public class SseConfig {
    @Bean(destroyMethod = "shutdown")
    public SseRegistry<String, OrderEvent> ordersRegistry() {
        return SseRegistry.<String, OrderEvent>builder()
            .maxStreams(1000)
            .build();
    }
    
    @Bean(destroyMethod = "shutdown")
    public SseRegistry<String, NotificationEvent> notificationsRegistry() {
        return SseRegistry.<String, NotificationEvent>builder()
            .maxStreams(5000)
            .build();
    }
}
```

### Custom Stream

```java
SseStream stream = SseStream.builder()
    .withTimeout(60_000L)
    .maxQueuedEvents(100)
    .onCompletion(() -> log.info("Done"))
    .build();

registry.register(userId, stream);
```

### Manual Emitter

```java
ImmutableSseEmitter emitter = new ImmutableSseEmitter(30_000L);
SseStream stream = SseStream.builder()
    .fromEmitter(emitter);

registry.register(userId, stream);
```

## License
Apache 2.0
