package io.leaklab.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leaklab.config.LeakLabConfig;
import io.leaklab.diag.DumpService;
import io.leaklab.diag.OomGuardian;
import io.leaklab.load.LoadEngine;
import io.leaklab.telemetry.EventJournal;
import io.leaklab.telemetry.GcWatcher;
import io.leaklab.telemetry.MemoryProbe;
import io.leaklab.telemetry.RuntimeInfo;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;
import java.util.List;

@Path("/api/telemetry")
public class TelemetryResource {

    public record Snapshot(long timestamp, String scenario, String dumpDirectory, RuntimeInfo runtime,
                           MemoryProbe.MemoryView memory, LoadEngine.Settings load, LoadEngine.Stats loadStats,
                           OomGuardian.State guardian, List<GcWatcher.GcEvent> gcEvents,
                           List<EventJournal.Entry> journal) {
    }

    private static final RuntimeInfo RUNTIME = RuntimeInfo.detect();

    @Inject
    LeakLabConfig config;

    @Inject
    MemoryProbe probe;

    @Inject
    LoadEngine load;

    @Inject
    OomGuardian guardian;

    @Inject
    GcWatcher gc;

    @Inject
    EventJournal journal;

    @Inject
    DumpService dumps;

    @Inject
    ObjectMapper mapper;

    /**
     * Build and serialize one snapshot at boot so every lambda / string-concat call site on the telemetry path is
     * linked while memory is plentiful. A first-time invokedynamic linkage that fails under OOM stays failed.
     */
    void warmUp(@Observes StartupEvent event) throws Exception {
        mapper.writeValueAsString(snapshot());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Snapshot snapshot() {
        return new Snapshot(System.currentTimeMillis(), config.scenario(), dumps.directory().toString(), RUNTIME,
                probe.read(), load.settings(), load.stats(), guardian.state(), gc.recent(24), journal.latest(60));
    }

    /** One snapshot per second as Server-Sent Events — the control room's only data feed. */
    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<Snapshot> stream() {
        return Multi.createFrom().ticks().every(Duration.ofSeconds(1))
                .onOverflow().drop()
                .emitOn(Infrastructure.getDefaultWorkerPool())
                .map(tick -> snapshot());
    }
}
