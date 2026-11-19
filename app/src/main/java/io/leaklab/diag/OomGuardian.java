package io.leaklab.diag;

import io.leaklab.config.LeakLabConfig;
import io.leaklab.telemetry.EventJournal;
import io.leaklab.telemetry.MemoryProbe;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The kernel OOM killer sends SIGKILL: no shutdown hook, no -Xdump agent, no
 * HeapDumpOnOutOfMemoryError ever runs. The only way to get evidence out of a container that is
 * about to be OOMKilled is to take it <em>before</em> the limit is reached.
 *
 * <p>The guardian watches the cgroup working set (the number kubelet and the OOM killer act on)
 * and, once it crosses {@code leaklab.guardian.threshold}, writes one javacore and one heap dump.
 * It re-arms after usage falls below {@code leaklab.guardian.rearm}.
 */
@ApplicationScoped
public class OomGuardian {

    public record State(boolean enabled, double threshold, double rearm, boolean armed,
                        double pressure, int firedCount, long lastFiredAt) {
    }

    @Inject
    LeakLabConfig config;

    @Inject
    MemoryProbe probe;

    @Inject
    DumpService dumps;

    @Inject
    EventJournal journal;

    private volatile boolean armed = true;
    private volatile int fired;
    private volatile long lastFiredAt;

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void watch() {
        if (!config.guardian().enabled()) {
            return;
        }
        double pressure = probe.containerPressure();
        if (pressure < 0) {
            return; // no cgroup limit: nothing to guard against
        }
        if (armed && pressure >= effectiveThreshold()) {
            armed = false;
            fired++;
            lastFiredAt = System.currentTimeMillis();
            journal.critical("Guardian: container working set at %.0f%% of the cgroup limit — capturing evidence before a possible OOMKill"
                    .formatted(pressure * 100));
            try {
                dumps.create(DumpService.Kind.JAVACORE, "guardian");
                dumps.create(DumpService.Kind.HEAP, "guardian");
            } catch (RuntimeException e) {
                // already journaled by DumpService
            }
        } else if (!armed && pressure < config.guardian().rearm()) {
            armed = true;
            journal.info("Guardian re-armed (pressure %.0f%%)".formatted(pressure * 100));
        }
    }

    public State state() {
        return new State(config.guardian().enabled(), effectiveThreshold(), config.guardian().rearm(),
                armed, probe.containerPressure(), fired, lastFiredAt);
    }

    /**
     * The configured threshold, lowered when it would leave less than the dump reserve below the limit. Measured on
     * HotSpot: at 90 % of a 512 MiB limit the guardian's heap dump itself pushed the container over (killed mid-dump,
     * leaving a truncated .hprof). In larger containers the configured ratio already leaves enough room.
     */
    double effectiveThreshold() {
        double threshold = config.guardian().threshold();
        long limit = probe.containerLimit();
        int reserveMb = config.guardian().reserveMb() >= 0 ? config.guardian().reserveMb() : dumps.openJ9() ? 0 : 64;
        if (limit <= 0 || reserveMb == 0) {
            return threshold;
        }
        return Math.min(threshold, 1.0 - (reserveMb * 1048576.0) / limit);
    }
}
