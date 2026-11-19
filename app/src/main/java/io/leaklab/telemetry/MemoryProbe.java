package io.leaklab.telemetry;

import io.leaklab.leak.CustomerSessionCache;
import io.leaklab.leak.DirectBufferVault;
import io.leaklab.leak.NativeMemoryVault;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Takes a coherent picture of every memory layer that counts against the container limit:
 *
 * <pre>
 *   cgroup limit ─┬─ Java heap            (-Xmx / MaxRAMPercentage)           ← heap dump sees this
 *                 ├─ JVM non-heap         (class storage, JIT code/data, misc)
 *                 ├─ direct buffers       (-XX:MaxDirectMemorySize)
 *                 ├─ native allocations   (JNI, FFM, malloc arenas, thread stacks)
 *                 └─ page cache           (reclaimable, excluded from working set)
 * </pre>
 *
 * and derives a verdict: can this JVM, as configured, ever throw OutOfMemoryError — or will the
 * kernel kill it first?
 */
@ApplicationScoped
public class MemoryProbe {

    public record Heap(long used, long committed, long max, long liveAfterGc, long liveAfterMajor) {
    }

    public record NonHeap(long used, long committed) {
    }

    public record Pool(String name, String type, long used, long committed, long max, long afterGc) {
    }

    public record Collector(String name, long count, long timeMs) {
    }

    public record OffHeap(long directBuffers, long directUsed, long directCapacity, long directLimit,
                          long nativeBlocks, long nativeBytes) {
    }

    public record Leak(int sessions, long sessionBytes, int directBuffers, long directBytes,
                       int nativeBlocks, long nativeBytes) {
    }

    public record Process(long rss, long rssPeak, int threads, double cpuLoad) {
    }

    public record Container(String cgroup, long limit, long usage, long workingSet, long peak,
                            Map<String, Long> events) {
    }

    /**
     * {@code projectedPeak = heapCeiling + nativeFootprint + directAllowance}: the resident size the process reaches
     * when the heap fills up to -Xmx and direct buffers grow to their cap, while everything else stays as it is now.
     */
    public record Verdict(String level, String headline, String detail, long heapCeiling, long containerLimit,
                          long nativeFootprint, long directAllowance, long projectedPeak, double pressure) {
    }

    public record MemoryView(Heap heap, NonHeap nonHeap, List<Pool> pools, List<Collector> collectors,
                             OffHeap offHeap, Leak leak, Process process, Container container, Verdict verdict) {
    }

    private static final Path PROC_STATUS = Path.of("/proc/self/status");

    @Inject
    GcWatcher gcWatcher;

    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final long directLimit = parseDirectLimit();

    private static final long FOOTPRINT_HOLD_NANOS = 60_000_000_000L;
    private long footprintHeld = -1;
    private long footprintHeldAt;

    public MemoryView read() {
        MemoryUsage h = memory.getHeapMemoryUsage();
        MemoryUsage nh = memory.getNonHeapMemoryUsage();
        long heapMax = Runtime.getRuntime().maxMemory();

        List<MemoryPoolMXBean> beans = ManagementFactory.getMemoryPoolMXBeans();
        List<Pool> pools = beans.stream().map(p -> {
            MemoryUsage u = p.getUsage();
            MemoryUsage c = p.getCollectionUsage(); // null for pools the GC does not collect
            return new Pool(p.getName(), p.getType().name(), u.getUsed(), u.getCommitted(), u.getMax(),
                    c == null ? -1 : c.getUsed());
        }).toList();

        long liveAfterGc = gcWatcher.lastAfterGc();
        if (liveAfterGc < 0) {
            long sum = pools.stream().filter(p -> "HEAP".equals(p.type()) && p.afterGc() >= 0).mapToLong(Pool::afterGc).sum();
            liveAfterGc = sum > 0 ? sum : -1;
        }

        List<Collector> collectors = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(g -> new Collector(g.getName(), g.getCollectionCount(), g.getCollectionTime()))
                .toList();

        long directCount = 0, directUsed = 0, directCapacity = 0;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if ("direct".equals(pool.getName())) {
                directCount = pool.getCount();
                directUsed = pool.getMemoryUsed();
                directCapacity = pool.getTotalCapacity();
            }
        }

        Leak leak = new Leak(CustomerSessionCache.size(), CustomerSessionCache.payloadBytes(),
                DirectBufferVault.count(), DirectBufferVault.bytes(),
                NativeMemoryVault.count(), NativeMemoryVault.bytes());

        long[] rss = readRss();
        Process process = new Process(rss[0], rss[1], ManagementFactory.getThreadMXBean().getThreadCount(), cpuLoad());

        CgroupReader.Stats cg = CgroupReader.read();
        Container container = new Container(cg.version(), cg.limit(), cg.usage(), cg.workingSet(), cg.peak(), cg.events());

        Heap heap = new Heap(h.getUsed(), h.getCommitted(), heapMax, liveAfterGc, gcWatcher.lastAfterMajor());
        NonHeap nonHeap = new NonHeap(nh.getUsed(), nh.getCommitted());
        OffHeap offHeap = new OffHeap(directCount, directUsed, directCapacity, directLimit,
                NativeMemoryVault.count(), NativeMemoryVault.bytes());

        return new MemoryView(heap, nonHeap, pools, collectors, offHeap, leak, process, container,
                verdict(heap, container, heldFootprint(nativeFootprint(heap, nonHeap, offHeap, process)),
                        directAllowance(directLimit, heap.max(), directUsed), directLimit > 0));
    }

    /**
     * Everything resident that is not Java heap. Prefer the measured value (RSS - heap committed); fall back to what
     * the JVM itself accounts for when /proc is not available. Use non-heap *used*, not *committed*: OpenJ9 reports the
     * whole reserved JIT code cache (256 MiB) as committed, most of it never resident.
     */
    static long nativeFootprint(Heap heap, NonHeap nonHeap, OffHeap offHeap, Process process) {
        long accounted = nonHeap.used() + offHeap.directUsed() + offHeap.nativeBytes();
        return process.rss() > 0 ? Math.max(accounted, process.rss() - heap.committed()) : accounted;
    }

    /**
     * The measured footprint dips whenever the heap commits pages before touching them (committed rises before RSS).
     * Near the limit that dip flips the verdict back and forth, so hold the highest value for a minute: a real
     * decrease (native memory released) still shows up, one window later.
     */
    private synchronized long heldFootprint(long measured) {
        long now = System.nanoTime();
        if (measured >= footprintHeld || now - footprintHeldAt > FOOTPRINT_HOLD_NANOS) {
            footprintHeld = measured;
            footprintHeldAt = now;
        }
        return footprintHeld;
    }

    /** The cgroup memory limit in bytes, or -1 when none applies. */
    public long containerLimit() {
        return CgroupReader.read().limit();
    }

    /** Container working set / limit, or -1 when no cgroup limit applies. */
    public double containerPressure() {
        CgroupReader.Stats cg = CgroupReader.read();
        if (cg.limit() <= 0 || cg.workingSet() < 0) {
            return -1;
        }
        return (double) cg.workingSet() / cg.limit();
    }

    /**
     * Direct buffers can still grow by this much before the JVM's own cap stops them: the cap minus what is in use.
     * Without -XX:MaxDirectMemorySize the cap defaults to about the max heap, on top of the heap.
     */
    static long directAllowance(long directLimit, long heapMax, long directUsed) {
        long cap = directLimit > 0 ? directLimit : heapMax;
        return Math.max(0, cap - directUsed);
    }

    static Verdict verdict(Heap heap, Container container, long nativeFootprint, long directAllowance, boolean directCapped) {
        long limit = container.limit();
        long base = heap.max() + nativeFootprint;               // heap full, everything else as it is now
        long projected = base + directAllowance;                // ... and direct buffers grown to their cap
        double pressure = limit > 0 && container.workingSet() >= 0 ? (double) container.workingSet() / limit : -1;
        String sum = "Heap ceiling %s + non-heap footprint %s + direct-memory allowance %s%s = %s"
                .formatted(mib(heap.max()), mib(nativeFootprint), mib(directAllowance),
                        directCapped ? "" : " (no MaxDirectMemorySize: default ≈ max heap)", mib(projected));

        if (limit <= 0) {
            return new Verdict("UNBOUNDED", "No container memory limit detected",
                    "The JVM sized its heap from host RAM. Without a cgroup limit, a leak is bounded only by the node — "
                            + "in Kubernetes it ends in node-pressure eviction instead of a clean OOM. Always set resources.limits.memory.",
                    heap.max(), limit, nativeFootprint, directAllowance, projected, pressure);
        }
        if (heap.max() >= limit) {
            return new Verdict("OOMKILL_BY_DESIGN", "Heap ceiling exceeds the container limit",
                    "-Xmx is %s but the cgroup grants only %s. The heap is allowed to grow past what the kernel will provide, "
                            .formatted(mib(heap.max()), mib(limit))
                            + "so the process is SIGKILLed (exit 137, OOMKilled) before the JVM ever considers the heap full: "
                            + "no OutOfMemoryError, no heap dump, no javacore.",
                    heap.max(), limit, nativeFootprint, directAllowance, projected, pressure);
        }
        if (base >= limit) {
            return new Verdict("OOMKILL_LIKELY", "Heap ceiling + native footprint exceed the limit",
                    sum + ", above the %s limit. Even with no direct buffers, once the heap fills the kernel kills the "
                            .formatted(mib(limit))
                            + "container before -Xmx is reached. Lower -Xmx/MaxRAMPercentage or raise the limit.",
                    heap.max(), limit, nativeFootprint, directAllowance, projected, pressure);
        }
        if (projected >= limit) {
            return new Verdict("OOMKILL_LIKELY", "The off-heap allowance doesn't fit in the room left",
                    sum + ", above the %s limit. The heap fits, but a direct-buffer leak is killed by the kernel long "
                            .formatted(mib(limit))
                            + "before the JVM's own cap is reached: no OutOfMemoryError. Set -XX:MaxDirectMemorySize to what is left.",
                    heap.max(), limit, nativeFootprint, directAllowance, projected, pressure);
        }
        if (projected >= limit * 0.9) {
            return new Verdict("TIGHT", "Less than 10% headroom at full heap",
                    "At full heap and full direct allowance the process would sit at %.0f%% of the limit. Native growth "
                            .formatted(projected * 100.0 / limit)
                            + "(threads, JIT, FFM/JNI) can still tip it over.",
                    heap.max(), limit, nativeFootprint, directAllowance, projected, pressure);
        }
        return new Verdict("SIZED", "Heap and direct memory are exhausted inside the container budget",
                "At full heap and full direct allowance the process reaches ~%.0f%% of the limit, so a heap or "
                        .formatted(projected * 100.0 / limit)
                        + "direct-buffer leak surfaces as a Java OutOfMemoryError — unless the leak is native, which no JVM flag can contain.",
                heap.max(), limit, nativeFootprint, directAllowance, projected, pressure);
    }

    private static long[] readRss() {
        long rss = -1, hwm = -1;
        try {
            for (String line : Files.readAllLines(PROC_STATUS)) {
                if (line.startsWith("VmRSS:")) rss = kib(line);
                else if (line.startsWith("VmHWM:")) hwm = kib(line);
            }
        } catch (IOException | RuntimeException ignored) {
            // not Linux
        }
        return new long[]{rss, hwm};
    }

    private static long kib(String line) {
        return Long.parseLong(line.replaceAll("\\D+", "")) * 1024;
    }

    private static double cpuLoad() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getProcessCpuLoad();
        }
        return -1;
    }

    private static long parseDirectLimit() {
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("-XX:MaxDirectMemorySize=")) {
                return parseSize(arg.substring(arg.indexOf('=') + 1));
            }
        }
        return -1; // JVM default: bounded by the max heap size
    }

    static long parseSize(String value) {
        String v = value.trim().toLowerCase();
        long mult = switch (v.charAt(v.length() - 1)) {
            case 'k' -> 1L << 10;
            case 'm' -> 1L << 20;
            case 'g' -> 1L << 30;
            default -> 1;
        };
        String digits = mult == 1 ? v : v.substring(0, v.length() - 1);
        return Long.parseLong(digits) * mult;
    }

    private static String mib(long bytes) {
        return bytes >= 1L << 30 ? "%.2f GiB".formatted(bytes / 1073741824.0) : "%d MiB".formatted(bytes >> 20);
    }
}
