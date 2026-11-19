package io.leaklab.load;

import io.leaklab.config.LeakLabConfig;
import io.leaklab.diag.DumpService;
import io.leaklab.leak.CheckoutService;
import io.leaklab.leak.DirectBufferVault;
import io.leaklab.leak.NativeMemoryVault;
import io.leaklab.telemetry.EventJournal;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Drives synthetic traffic at a rate the control room changes in real time. Runs on one
 * dedicated, named platform thread ({@code leaklab-load-engine}) so it is easy to spot in a
 * javacore / thread dump and in the "allocated by" view of a profiler.
 */
@ApplicationScoped
public class LoadEngine {

    public enum Mode { HEAP, DIRECT, NATIVE }

    /**
     * What the operator dialled in. Retention is the share of requests that take the leaking path.
     * {@code keepRunningOnOom}: after an OutOfMemoryError the engine pauses (false, the lab default: the JVM stays
     * diagnosable) or keeps driving load (true), as a real service keeps receiving requests. Null in a request means
     * "unchanged", so API clients that do not know the field keep the current behaviour.
     */
    public record Settings(boolean running, Mode mode, int ratePerSecond, int payloadKb, int retentionPercent,
                           Boolean keepRunningOnOom) {

        public Settings clamp() {
            return new Settings(running,
                    mode == null ? Mode.HEAP : mode,
                    bound(ratePerSecond, 0, 2_000),
                    bound(payloadKb, 1, 4_096),
                    bound(retentionPercent, 0, 100),
                    keepRunningOnOom);
        }

        boolean keepsRunning() {
            return Boolean.TRUE.equals(keepRunningOnOom);
        }

        Settings paused() {
            return new Settings(false, mode, ratePerSecond, payloadKb, retentionPercent, keepRunningOnOom);
        }
    }

    /** {@code value} limited to [min, max]. Math.clamp would do, but it is Java 21+ and LeakLab also builds for 17. */
    public static int bound(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Stats(long requests, long retainedRequests, long allocatedBytes, double requestsPerSecond,
                        String lastError, long lastErrorAt) {
    }

    private static final long TICK_NANOS = 100_000_000L; // 10 ticks per second
    private static final int PARACHUTE_BYTES = 24 * 1024 * 1024;
    /** With the engine running on through OOMs, one journal entry per this interval instead of one per OOM. */
    private static final long OOM_JOURNAL_INTERVAL_MS = 10_000;

    @Inject
    CheckoutService checkout;

    @Inject
    EventJournal journal;

    @Inject
    DumpService dumps;

    @Inject
    LeakLabConfig config;

    /**
     * Lab-only "memory parachute": released on OutOfMemoryError so the control plane can still serve the UI and the
     * "Release" call. Without it, a JVM after OOM is in an undefined state — call sites whose invokedynamic linkage
     * failed during the OOM keep throwing BootstrapMethodError forever. Production services should use
     * -XX:+ExitOnOutOfMemoryError instead (after the dump agents have run).
     */
    private volatile byte[] parachute = new byte[PARACHUTE_BYTES];

    private volatile Settings settings = new Settings(false, Mode.HEAP, 40, 256, 25, false);
    private volatile boolean shutdown;
    private volatile String lastError;
    private volatile long lastErrorAt;
    private volatile double observedRate;
    private long lastOomJournalAt;
    private int oomsSinceJournal;

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong retained = new AtomicLong();
    private final AtomicLong allocated = new AtomicLong();

    void onStart(@Observes StartupEvent event) {
        settings = new Settings(false, config.load().mode(), 40, 256, 25, config.load().keepRunningOnOom());
        Thread worker = new Thread(this::loop, "leaklab-load-engine");
        worker.setDaemon(true);
        worker.start();
    }

    void onStop(@Observes ShutdownEvent event) {
        shutdown = true;
    }

    public Settings settings() {
        return settings;
    }

    public Settings apply(Settings requested) {
        Settings previous = settings;
        Settings next = requested.clamp();
        if (next.keepRunningOnOom() == null) {
            next = new Settings(next.running(), next.mode(), next.ratePerSecond(), next.payloadKb(),
                    next.retentionPercent(), previous.keepRunningOnOom());
        }
        settings = next;
        if (previous.running() != next.running()) {
            journal.info(next.running() ? "Load engine started: " + describe(next) : "Load engine paused");
        } else if (!previous.equals(next)) {
            journal.info("Load profile changed: " + describe(next));
        }
        return next;
    }

    public Stats stats() {
        return new Stats(requests.get(), retained.get(), allocated.get(), observedRate, lastError, lastErrorAt);
    }

    /** Retain {@code megabytes} immediately in the given store (the "spike" buttons). */
    public long burst(Mode mode, int megabytes) {
        int chunk = 1024 * 1024;
        long done = 0;
        try {
            for (int i = 0; i < megabytes; i++) {
                retainOne(mode, chunk);
                done += chunk;
            }
            journal.warn("Burst retained " + (done >> 20) + " MiB in " + mode + " memory");
        } catch (OutOfMemoryError oom) {
            recordOom(oom, "spike stopped", false);
        }
        return done;
    }

    private void loop() {
        long next = System.nanoTime();
        double carry = 0;
        long windowStart = System.nanoTime();
        long windowCount = 0;

        while (!shutdown) {
            Settings s = settings;
            if (s.running() && s.ratePerSecond() > 0) {
                carry += s.ratePerSecond() / 10.0;
                int batch = (int) carry;
                carry -= batch;
                try {
                    for (int i = 0; i < batch; i++) {
                        handleRequest(s);
                    }
                    windowCount += batch;
                } catch (OutOfMemoryError oom) {
                    onLoopOom(s, oom);
                }
            } else {
                carry = 0;
            }

            long now = System.nanoTime();
            if (now - windowStart >= 1_000_000_000L) {
                observedRate = windowCount * 1e9 / (now - windowStart);
                windowStart = now;
                windowCount = 0;
            }

            next += TICK_NANOS;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                LockSupport.parkNanos(sleep);
            } else {
                next = System.nanoTime(); // fell behind: do not try to catch up in a burst
            }
        }
    }

    private void handleRequest(Settings s) {
        int bytes = s.payloadKb() * 1024;
        boolean leak = ThreadLocalRandom.current().nextInt(100) < s.retentionPercent();
        requests.incrementAndGet();
        allocated.addAndGet(bytes);
        if (leak) {
            retained.incrementAndGet();
        }
        switch (s.mode()) {
            case HEAP -> checkout.process(bytes, leak);
            case DIRECT -> {
                if (leak) DirectBufferVault.retain(bytes); else DirectBufferVault.churn(bytes);
            }
            case NATIVE -> {
                if (leak) NativeMemoryVault.retain(bytes); else NativeMemoryVault.churn(bytes);
            }
        }
    }

    private void retainOne(Mode mode, int bytes) {
        switch (mode) {
            case HEAP -> checkout.process(bytes, true);
            case DIRECT -> DirectBufferVault.retain(bytes);
            case NATIVE -> NativeMemoryVault.retain(bytes);
        }
        allocated.addAndGet(bytes);
    }

    /** Re-pack the parachute once memory has been released. */
    public void rearmParachute() {
        if (parachute == null) {
            parachute = new byte[PARACHUTE_BYTES];
        }
    }

    /**
     * The engine thread must outlive the OOM. Pausing comes first (dropping the parachute frees room for the small
     * allocations that follow). Running on, the heap stays full: recording the error may itself throw, and that
     * second OOM must not escape and kill the thread (seen on OpenJ9 before this guard).
     */
    private void onLoopOom(Settings s, OutOfMemoryError oom) {
        try {
            if (s.keepsRunning()) {
                recordOom(oom, "load engine keeps running", true);
            } else {
                parachute = null;
                settings = s.paused();
                recordOom(oom, "load engine paused", false);
            }
        } catch (OutOfMemoryError noRoomToRecordIt) {
            lastErrorAt = System.currentTimeMillis(); // allocation-free: the next journal entry counts this one too
        }
    }

    /**
     * Drops the parachute, remembers the error and writes a journal entry. {@code throttle}: the engine keeps running
     * into the same full memory and raises OOMs many times a second, so write at most one entry per interval, with
     * the number of OOMs since the last one. Between entries nothing is allocated, so OOM handling cannot fail there.
     */
    private void recordOom(OutOfMemoryError oom, String consequence, boolean throttle) {
        parachute = null;
        lastErrorAt = System.currentTimeMillis();
        String note = dumpNote(oom); // every OOM counts towards the dump agents' rations, written or not
        int repeats = 0;
        if (throttle) {
            oomsSinceJournal++;
            if (lastErrorAt - lastOomJournalAt < OOM_JOURNAL_INTERVAL_MS) {
                return;
            }
            lastOomJournalAt = lastErrorAt; // before allocating: a failed entry is not retried on every tick
            repeats = oomsSinceJournal;
            oomsSinceJournal = 0;
        }
        lastError = "OutOfMemoryError: " + oom.getMessage();
        journal.critical(lastError + " — " + consequence
                + (repeats > 1 ? " (" + repeats + " OOMs since the last entry)" : "") + ". " + note);
    }

    /**
     * Which dump agents saw this error. OpenJ9's default agents listen for "systhrow" (raised by the VM itself:
     * heap, metaspace). "Cannot reserve … direct buffer memory" is thrown by Java code in java.nio.Bits — a "throw"
     * event, so only an explicit -Xdump:…:events=throw,filter=java/lang/OutOfMemoryError,msg_filter=*direct* agent captures it.
     */
    private String dumpNote(OutOfMemoryError oom) {
        boolean vmThrown = oom.getMessage() == null || !oom.getMessage().contains("direct buffer memory");
        // Every automatic dump is rationed: HotSpot dumps on the first OOM only, OpenJ9's defaults on the first 4 events
        // (one incident can raise 2), the image's throw agent on the first. Count what this engine has seen.
        int seen = (vmThrown ? VM_OOMS : LIBRARY_OOMS).incrementAndGet();
        if (!dumps.openJ9()) {
            if (!vmThrown) {
                return "Thrown by the class library: even -XX:+HeapDumpOnOutOfMemoryError does not fire for it. Take a dump now.";
            }
            if (!HOTSPOT_OOM_DUMP) {
                return "HotSpot wrote NO dump: -XX:+HeapDumpOnOutOfMemoryError is off. Take one now (jcmd 1 GC.heap_dump).";
            }
            return seen == 1
                    ? "HeapDumpOnOutOfMemoryError wrote an .hprof (it does so for the first OOM of this JVM only)."
                    : "NO dump this time: HeapDumpOnOutOfMemoryError only fires on the first OOM of a JVM. Take one now (jcmd 1 GC.heap_dump).";
        }
        if (vmThrown) {
            return "OpenJ9's default dump agents (event systhrow) wrote heapdump/javacore/snap, and a core on the first event "
                    + "(they stop after the 4th OOM event).";
        }
        if (!THROW_AGENT) {
            return "Thrown by the class library, not the VM: OpenJ9's default (systhrow) agents do NOT fire, so no dump was written. "
                    + "Take one now, or add -Xdump:java+heap:events=throw,filter=java/lang/OutOfMemoryError,msg_filter=*direct*.";
        }
        return seen == 1
                ? "Thrown by the class library, not the VM: the image's events=throw agent wrote a heapdump + javacore."
                : "Thrown by the class library: NO dump this time, the image's events=throw agent fires on the first occurrence only.";
    }

    private static final AtomicInteger VM_OOMS = new AtomicInteger();
    private static final AtomicInteger LIBRARY_OOMS = new AtomicInteger();

    private static final boolean HOTSPOT_OOM_DUMP = hotSpotFlag("HeapDumpOnOutOfMemoryError");

    private static boolean hotSpotFlag(String name) {
        try {
            return Boolean.parseBoolean(ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
                    .getVMOption(name).getValue());
        } catch (RuntimeException e) {
            return false; // not HotSpot, or the flag does not exist
        }
    }

    private static final boolean THROW_AGENT = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .anyMatch(a -> a.startsWith("-Xdump:") && a.contains("events=throw") && a.contains("OutOfMemoryError"));

    private static String describe(Settings s) {
        return "%s · %d req/s · %d KiB · %d%% retained · on OOM: %s".formatted(s.mode(), s.ratePerSecond(),
                s.payloadKb(), s.retentionPercent(), s.keepsRunning() ? "keep running" : "pause");
    }
}
