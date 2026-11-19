package io.leaklab.telemetry;

import io.leaklab.leak.CustomerSessionCache;
import io.leaklab.leak.DirectBufferVault;
import io.leaklab.leak.NativeMemoryVault;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Prometheus gauges at /q/metrics. Combine with kube-state-metrics / cAdvisor in Grafana:
 * {@code container_memory_working_set_bytes / container_spec_memory_limit_bytes} next to
 * {@code jvm_memory_used_bytes{area="heap"}} tells you which layer is growing.
 */
@ApplicationScoped
public class LeakMetrics {

    @Inject
    MeterRegistry registry;

    @Inject
    GcWatcher gcWatcher;

    void register(@Observes StartupEvent event) {
        Gauge.builder("leaklab.retained.bytes", CustomerSessionCache::payloadBytes).tag("store", "heap")
                .baseUnit("bytes").description("Bytes retained by the intentional leak, per store").register(registry);
        Gauge.builder("leaklab.retained.bytes", DirectBufferVault::bytes).tag("store", "direct")
                .baseUnit("bytes").register(registry);
        Gauge.builder("leaklab.retained.bytes", NativeMemoryVault::bytes).tag("store", "native")
                .baseUnit("bytes").register(registry);
        Gauge.builder("leaklab.heap.live.after.gc", gcWatcher::lastAfterGc)
                .baseUnit("bytes").description("Heap occupancy after the most recent GC").register(registry);
        Gauge.builder("leaklab.cgroup.working.set", () -> CgroupReader.read().workingSet())
                .baseUnit("bytes").register(registry);
        Gauge.builder("leaklab.cgroup.limit", () -> CgroupReader.read().limit())
                .baseUnit("bytes").register(registry);
    }
}
