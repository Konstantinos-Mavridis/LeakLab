package io.leaklab.telemetry;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Subscribes to GC notifications (supported by both OpenJ9 and HotSpot) and records the heap
 * occupancy <em>after</em> each collection. The after-GC occupancy of global/major collections is
 * the live set — the single most important leak signal: in a healthy service it is flat, in a
 * leaking one it climbs with every cycle ("sawtooth with a rising floor").
 */
@Startup
@ApplicationScoped
public class GcWatcher {

    public record GcEvent(long timestamp, String collector, String action, String cause,
                          long durationMs, long heapBefore, long heapAfter, boolean major) {
    }

    private static final int CAPACITY = 64;

    private final Deque<GcEvent> recent = new ArrayDeque<>();
    private Set<String> heapPools = Set.of();
    private volatile long lastAfterGc = -1;
    private volatile long lastAfterMajor = -1;
    private volatile boolean supported;

    @PostConstruct
    void subscribe() {
        heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == MemoryType.HEAP)
                .map(MemoryPoolMXBean::getName)
                .collect(Collectors.toUnmodifiableSet());
        try {
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (gc instanceof NotificationEmitter emitter) {
                    emitter.addNotificationListener((n, h) -> onNotification(n), null, null);
                    supported = true;
                }
            }
        } catch (Throwable t) {
            supported = false; // JVM without com.sun.management notifications: fall back to pool collection usage
        }
    }

    private void onNotification(Notification notification) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
            return;
        }
        try {
            GarbageCollectionNotificationInfo info =
                    GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
            GcInfo gc = info.getGcInfo();
            long before = sumHeap(gc.getMemoryUsageBeforeGc());
            long after = sumHeap(gc.getMemoryUsageAfterGc());
            boolean major = isMajor(info.getGcName(), info.getGcAction());
            GcEvent event = new GcEvent(System.currentTimeMillis(), info.getGcName(), info.getGcAction(),
                    info.getGcCause(), gc.getDuration(), before, after, major);
            lastAfterGc = after;
            if (major) {
                lastAfterMajor = after;
            }
            synchronized (recent) {
                recent.addLast(event);
                while (recent.size() > CAPACITY) {
                    recent.removeFirst();
                }
            }
        } catch (Throwable ignored) {
            // never let diagnostics break the JVM's notification thread
        }
    }

    private long sumHeap(Map<String, MemoryUsage> usage) {
        long total = 0;
        for (Map.Entry<String, MemoryUsage> e : usage.entrySet()) {
            if (heapPools.contains(e.getKey())) {
                total += e.getValue().getUsed();
            }
        }
        return total;
    }

    /** OpenJ9 gencon: "scavenge" (minor) / "global" (major). HotSpot: action "end of major GC". */
    static boolean isMajor(String name, String action) {
        String n = name.toLowerCase(Locale.ROOT);
        String a = action == null ? "" : action.toLowerCase(Locale.ROOT);
        return a.contains("major") || n.contains("global") || n.contains("old")
                || n.contains("marksweep") || n.contains("full");
    }

    public boolean supported() {
        return supported;
    }

    public long lastAfterGc() {
        return lastAfterGc;
    }

    public long lastAfterMajor() {
        return lastAfterMajor;
    }

    public List<GcEvent> recent(int max) {
        synchronized (recent) {
            List<GcEvent> all = new ArrayList<>(recent);
            return all.subList(Math.max(0, all.size() - max), all.size());
        }
    }
}
