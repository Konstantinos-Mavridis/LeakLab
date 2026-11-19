package io.leaklab.leak;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * "Caches" checkout sessions so that a retried request can be answered without re-pricing.
 *
 * <p><b>THE LEAK (intentional):</b> the cache is keyed by {@code requestId}, which is unique
 * per request. Retries carry a new id, so the cache never produces a hit — and because there
 * is no TTL, no maximum size and no eviction, every entry lives until the JVM dies.
 *
 * <p>Heap-dump signature you should find in Eclipse MAT:
 * <pre>
 *   class io.leaklab.leak.CustomerSessionCache        (GC root: System Class / static field)
 *     └─ SESSIONS  java.util.concurrent.ConcurrentHashMap       retained ≈ 90%+ of heap
 *          └─ table  ConcurrentHashMap$Node[]
 *               └─ ConcurrentHashMap$Node  × N
 *                    └─ val  io.leaklab.leak.SessionContext × N
 *                         └─ renderedQuote  byte[]  × N
 * </pre>
 *
 * <p>The fix would be a bounded, expiring cache (Caffeine with {@code maximumSize} and
 * {@code expireAfterWrite}) keyed by an idempotency key that is stable across retries.
 */
public final class CustomerSessionCache {

    private static final Map<String, SessionContext> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicLong PAYLOAD_BYTES = new AtomicLong();

    private CustomerSessionCache() {
    }

    static SessionContext lookup(String requestId) {
        return SESSIONS.get(requestId);
    }

    static void remember(SessionContext ctx) {
        if (SESSIONS.put(ctx.requestId(), ctx) == null) {
            PAYLOAD_BYTES.addAndGet(ctx.payloadBytes());
        }
    }

    public static int size() {
        return SESSIONS.size();
    }

    public static long payloadBytes() {
        return PAYLOAD_BYTES.get();
    }

    /** Operational escape hatch exposed through the UI ("Release retained memory"). */
    public static long clear() {
        long freed = PAYLOAD_BYTES.getAndSet(0);
        SESSIONS.clear();
        return freed;
    }
}
