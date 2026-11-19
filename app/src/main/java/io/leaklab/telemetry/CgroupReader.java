package io.leaklab.telemetry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the container's memory accounting straight from the cgroup filesystem — the same numbers
 * the kernel OOM killer and the kubelet act on. Supports cgroup v2 (current Docker, OpenShift 4.14+)
 * and falls back to cgroup v1.
 */
final class CgroupReader {

    private static final Path V2_ROOT = Path.of("/sys/fs/cgroup");
    private static final Path V1_ROOT = Path.of("/sys/fs/cgroup/memory");
    private static final long V1_UNLIMITED = 1L << 60;

    /**
     * @param limit       hard limit in bytes, -1 when unlimited / unknown
     * @param usage       total charged memory (anon + page cache + kernel)
     * @param workingSet  usage minus inactive file cache — what kubelet reports as
     *                    {@code container_memory_working_set_bytes} and what eviction acts on
     * @param peak        high-water mark since the cgroup was created, -1 if not exposed
     * @param events      v2 {@code memory.events} counters ({@code max}, {@code oom}, {@code oom_kill}...)
     */
    record Stats(String version, long limit, long usage, long workingSet, long peak, Map<String, Long> events) {

        static Stats none() {
            return new Stats("none", -1, -1, -1, -1, Map.of());
        }
    }

    private CgroupReader() {
    }

    static Stats read() {
        try {
            if (Files.exists(V2_ROOT.resolve("memory.max"))) {
                return readV2();
            }
            if (Files.exists(V1_ROOT.resolve("memory.limit_in_bytes"))) {
                return readV1();
            }
        } catch (IOException | RuntimeException ignored) {
            // not on Linux, or a restricted /sys: report "none"
        }
        return Stats.none();
    }

    private static Stats readV2() throws IOException {
        String max = Files.readString(V2_ROOT.resolve("memory.max")).trim();
        long limit = "max".equals(max) ? -1 : Long.parseLong(max);
        long usage = readLong(V2_ROOT.resolve("memory.current"));
        long inactiveFile = keyed(V2_ROOT.resolve("memory.stat")).getOrDefault("inactive_file", 0L);
        long peak = readLong(V2_ROOT.resolve("memory.peak"));
        return new Stats("v2", limit, usage, Math.max(0, usage - inactiveFile), peak, keyed(V2_ROOT.resolve("memory.events")));
    }

    private static Stats readV1() throws IOException {
        long limit = readLong(V1_ROOT.resolve("memory.limit_in_bytes"));
        if (limit >= V1_UNLIMITED) {
            limit = -1;
        }
        long usage = readLong(V1_ROOT.resolve("memory.usage_in_bytes"));
        long inactiveFile = keyed(V1_ROOT.resolve("memory.stat")).getOrDefault("total_inactive_file", 0L);
        long peak = readLong(V1_ROOT.resolve("memory.max_usage_in_bytes"));
        Map<String, Long> events = new LinkedHashMap<>();
        events.put("max", readLong(V1_ROOT.resolve("memory.failcnt")));
        events.put("oom_kill", keyed(V1_ROOT.resolve("memory.oom_control")).getOrDefault("oom_kill", 0L));
        return new Stats("v1", limit, usage, Math.max(0, usage - inactiveFile), peak, events);
    }

    private static long readLong(Path file) {
        try {
            return Long.parseLong(Files.readString(file).trim());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    /** Parses "key value" files such as memory.stat and memory.events. */
    private static Map<String, Long> keyed(Path file) {
        Map<String, Long> result = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2) {
                    try {
                        result.put(parts[0], Long.parseLong(parts[1]));
                    } catch (NumberFormatException ignored) {
                        // skip non-numeric entries
                    }
                }
            }
        } catch (IOException ignored) {
            // file not present on this kernel
        }
        return result;
    }
}
