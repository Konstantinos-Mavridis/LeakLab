package io.leaklab.leak;

import java.time.Instant;
import java.util.Map;

/**
 * State built while processing one checkout request. On its own a perfectly reasonable,
 * short-lived object — the problem is who keeps a reference to it afterwards.
 *
 * <p>In a heap dump this is the class whose instance count explodes in the histogram and
 * whose {@code renderedQuote} byte[] dominates the retained size.
 */
public final class SessionContext {

    private final String requestId;
    private final String customerId;
    private final Instant createdAt;
    private final Map<String, String> attributes;
    private final byte[] renderedQuote;

    public SessionContext(String requestId, String customerId, Map<String, String> attributes, byte[] renderedQuote) {
        this.requestId = requestId;
        this.customerId = customerId;
        this.createdAt = Instant.now();
        this.attributes = attributes;
        this.renderedQuote = renderedQuote;
    }

    public String requestId() {
        return requestId;
    }

    public String customerId() {
        return customerId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public int payloadBytes() {
        return renderedQuote.length;
    }
}
