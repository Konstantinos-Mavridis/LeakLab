package io.leaklab.api;

import io.leaklab.diag.DumpService;
import io.leaklab.leak.CustomerSessionCache;
import io.leaklab.leak.DirectBufferVault;
import io.leaklab.leak.NativeMemoryVault;
import io.leaklab.load.LoadEngine;
import io.leaklab.telemetry.EventJournal;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

/**
 * Control plane for the UI. Deliberately unauthenticated: this is a lab. Never expose these
 * endpoints — especially dump download — on a production route.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class ControlResource {

    public record Burst(LoadEngine.Mode mode, int megabytes) {
    }

    public record Released(long heapBytes, long directBytes, long nativeBytes) {
    }

    public record GcResult(long heapBefore, long heapAfter, long durationMs) {
    }

    @Inject
    LoadEngine load;

    @Inject
    DumpService dumps;

    @Inject
    EventJournal journal;

    @GET
    @Path("/load")
    public LoadEngine.Settings settings() {
        return load.settings();
    }

    @PUT
    @Path("/load")
    @Consumes(MediaType.APPLICATION_JSON)
    public LoadEngine.Settings apply(LoadEngine.Settings settings) {
        if (settings == null) {
            throw new BadRequestException("settings required");
        }
        return load.apply(settings);
    }

    @POST
    @Path("/load/burst")
    @Consumes(MediaType.APPLICATION_JSON)
    public Burst burst(Burst burst) {
        int mb = LoadEngine.bound(burst.megabytes(), 1, 2048);
        long done = load.burst(burst.mode() == null ? LoadEngine.Mode.HEAP : burst.mode(), mb);
        return new Burst(burst.mode(), (int) (done >> 20));
    }

    @POST
    @Path("/memory/release")
    public Released release() {
        Released r = new Released(CustomerSessionCache.clear(), DirectBufferVault.clear(), NativeMemoryVault.clear());
        System.gc(); // lets direct-buffer Cleaners run and shows the floor drop immediately
        load.rearmParachute();
        journal.info("Released retained memory: heap %d MiB · direct %d MiB · native %d MiB"
                .formatted(r.heapBytes() >> 20, r.directBytes() >> 20, r.nativeBytes() >> 20));
        return r;
    }

    @POST
    @Path("/memory/gc")
    public GcResult gc() {
        long before = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long start = System.nanoTime();
        System.gc();
        long ms = (System.nanoTime() - start) / 1_000_000;
        long after = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        journal.info("Explicit GC: %d MiB → %d MiB in %d ms (whatever survives is reachable — i.e. the leak)"
                .formatted(before >> 20, after >> 20, ms));
        return new GcResult(before, after, ms);
    }

    @GET
    @Path("/dumps")
    public List<DumpService.DumpFile> listDumps() {
        return dumps.list();
    }

    @POST
    @Path("/dumps/{kind}")
    public Response createDump(@PathParam("kind") String kind) {
        DumpService.Kind k;
        try {
            k = DumpService.Kind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("kind must be heap, javacore or system");
        }
        try {
            return Response.ok(dumps.create(k, "operator")).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorBody(e.getMessage())).build();
        }
    }

    @GET
    @Path("/dumps/{name}/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(@PathParam("name") String name) {
        java.nio.file.Path file = dumps.resolve(name).orElseThrow(NotFoundException::new);
        return Response.ok(file.toFile())
                .header("Content-Disposition", "attachment; filename=\"" + file.getFileName() + "\"")
                .build();
    }

    public record ErrorBody(String error) {
    }
}
