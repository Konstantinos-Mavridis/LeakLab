package io.leaklab.leak;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulated business service. Each call represents one checkout request: it renders a quote
 * (the payload), and — on the "retry-aware" code path — remembers the session in the
 * {@link CustomerSessionCache}. That remember call is the allocation site you will see in the
 * leak-suspect stack and in the dominator tree.
 */
@ApplicationScoped
public class CheckoutService {

    private static final String[] TIERS = {"bronze", "silver", "gold", "platinum"};

    /**
     * @param payloadBytes size of the rendered quote held by the session
     * @param retryAware   true for requests that go through the buggy caching path
     * @return the number of bytes that stay reachable after the request completes
     */
    public long process(int payloadBytes, boolean retryAware) {
        String requestId = UUID.randomUUID().toString();
        if (retryAware && CustomerSessionCache.lookup(requestId) != null) {
            return 0; // never happens: the key is unique per request
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        byte[] quote = new byte[payloadBytes];
        for (int i = 0; i < quote.length; i += 4096) {
            quote[i] = (byte) rnd.nextInt(); // touch every page so it really costs RSS
        }
        SessionContext ctx = new SessionContext(
                requestId,
                "cust-" + rnd.nextInt(1_000_000),
                Map.of("tier", TIERS[rnd.nextInt(TIERS.length)], "channel", "web", "currency", "EUR"),
                quote);

        if (retryAware) {
            CustomerSessionCache.remember(ctx);
            return payloadBytes;
        }
        return 0; // ctx becomes garbage when this frame returns: healthy churn
    }
}
