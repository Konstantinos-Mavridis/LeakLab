package io.leaklab.diag;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.leaklab.config.LeakLabConfig;
import io.leaklab.telemetry.EventJournal;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Produces diagnostic artefacts on demand. On IBM Semeru / Eclipse OpenJ9 it drives the
 * {@code com.ibm.jvm.Dump} API (via reflection, so the code also compiles and runs on HotSpot):
 *
 * <ul>
 *   <li><b>heap</b>    → Portable Heap Dump ({@code .phd}): object graph without primitive values. Open with Eclipse MAT + IBM DTFJ.</li>
 *   <li><b>javacore</b> → Java dump ({@code .txt}): threads, locks, GC history and the NATIVEMEMINFO breakdown.</li>
 *   <li><b>system</b>  → OS core ({@code .dmp}): the entire process image, including primitive values and native memory.
 *                        Analyse with {@code jdmpview} or MAT + DTFJ. Large — roughly the size of the process RSS.</li>
 * </ul>
 *
 * On HotSpot the heap dump is an {@code .hprof} (with primitive values) and the javacore becomes a
 * {@code vmreport.*.txt}: the output of jcmd VM.command_line, VM.flags, VM.native_memory summary (needs
 * -XX:NativeMemoryTracking at start-up) and Thread.print.
 */
@ApplicationScoped
public class DumpService {

    public enum Kind { HEAP, JAVACORE, SYSTEM }

    public record DumpFile(String name, String kind, long sizeBytes, long modifiedAt) {
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss");

    @Inject
    LeakLabConfig config;

    @Inject
    EventJournal journal;

    private Path directory;
    private Class<?> openj9Dump;

    @PostConstruct
    void init() {
        directory = resolveDirectory(Path.of(config.dumpDir()));
        try {
            openj9Dump = Class.forName("com.ibm.jvm.Dump");
        } catch (ClassNotFoundException e) {
            openj9Dump = null;
        }
    }

    public boolean openJ9() {
        return openj9Dump != null;
    }

    public Path directory() {
        return directory;
    }

    public synchronized DumpFile create(Kind kind, String trigger) {
        String base = "%s.%s.%s.%d".formatted(prefix(kind), config.scenario(), LocalDateTime.now().format(STAMP),
                ProcessHandle.current().pid());
        long start = System.nanoTime();
        try {
            Path written = openJ9() ? openJ9Dump(kind, base) : hotSpotDump(kind, base, !"guardian".equals(trigger));
            long ms = (System.nanoTime() - start) / 1_000_000;
            DumpFile file = describe(written);
            journal.warn("%s dump written by %s in %d ms: %s (%s)".formatted(
                    kind.name().toLowerCase(), trigger, ms, file.name(), human(file.sizeBytes())));
            return file;
        } catch (Exception e) {
            Throwable cause = e instanceof InvocationTargetException ite ? ite.getCause() : e;
            journal.critical("%s dump failed: %s".formatted(kind, cause));
            throw new IllegalStateException(kind + " dump failed: " + cause.getMessage(), cause);
        }
    }

    private Path openJ9Dump(Kind kind, String base) throws ReflectiveOperationException {
        String method = switch (kind) {
            case HEAP -> "heapDumpToFile";
            case JAVACORE -> "javaDumpToFile";
            case SYSTEM -> "systemDumpToFile";
        };
        String suffix = switch (kind) {
            case HEAP -> ".phd";
            case JAVACORE -> ".txt";
            case SYSTEM -> ".dmp";
        };
        Method m = openj9Dump.getMethod(method, String.class);
        String written = (String) m.invoke(null, directory.resolve(base + suffix).toString());
        return Path.of(written);
    }

    private Path hotSpotDump(Kind kind, String base, boolean liveOnly) throws IOException {
        switch (kind) {
            case HEAP -> {
                // Uncompressed: GC.heap_dump -gz=1 is far smaller and faster (measured: 7.6 MB in 1.6 s instead of 385 MB
                // in 8.8 s), but HotSpot does not export GC.heap_dump over JMX, and spawning jcmd would start a second
                // JVM inside the already-full container. Operators use jcmd -gz=1 from outside (collect-evidence.sh).
                // Either way the dump needs ~30 MiB of extra memory while the JVM is paused (see OomGuardian).
                Path target = directory.resolve(base + ".hprof");
                // liveOnly runs a full GC first. The guardian skips it: near the limit, with -Xmx above it, that GC can
                // grow the heap and get the container killed mid-dump (measured). MAT drops unreachable objects anyway.
                ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).dumpHeap(target.toString(), liveOnly);
                return target;
            }
            case JAVACORE -> {
                // HotSpot has no javacore. The closest equivalent is a set of jcmd diagnostic commands, run in-process
                // through the DiagnosticCommand MBean (no jcmd binary or attach needed): what `jcmd 1 <cmd>` prints.
                Path target = directory.resolve(base.replaceFirst("^javacore", "vmreport") + ".txt");
                StringBuilder out = new StringBuilder("HotSpot VM report (the counterpart of an OpenJ9 javacore)\n")
                        .append("JVM start time: ").append(ManagementFactory.getRuntimeMXBean().getStartTime()).append('\n')
                        .append("Process start ticks: ").append(processStartTicks()).append('\n');
                for (String[] cmd : HOTSPOT_REPORT) {
                    out.append("\n===== jcmd 1 ").append(cmd[0]).append(cmd.length > 2 ? " " + cmd[2] : "").append(" =====\n")
                            .append(diagnosticCommand(cmd));
                }
                Files.writeString(target, out);
                return target;
            }
            default -> throw new UnsupportedOperationException(
                    "System dumps need OpenJ9 (com.ibm.jvm.Dump). On HotSpot use gcore / jcmd instead.");
        }
    }

    /** {jcmd name, DiagnosticCommand MBean operation, optional argument}. */
    private static final String[][] HOTSPOT_REPORT = {
            {"VM.command_line", "vmCommandLine"},
            {"VM.flags", "vmFlags"},
            {"VM.native_memory", "vmNativeMemory", "summary"},
            {"Thread.print", "threadPrint"},
    };

    /**
     * Field 22 of /proc/self/stat: when the process started, in clock ticks since boot. Identifies this JVM instance
     * the same way whether the report comes from here or from jcmd (scripts/collect-evidence.sh), so the analyzer only
     * diffs NMT reports of one JVM.
     */
    private static String processStartTicks() {
        try {
            String stat = Files.readString(Path.of("/proc/self/stat"));
            return stat.substring(stat.lastIndexOf(')') + 2).split(" ")[19];
        } catch (IOException | RuntimeException e) {
            return "?";
        }
    }

    private static String diagnosticCommand(String[] cmd) {
        try {
            ObjectName name = new ObjectName("com.sun.management:type=DiagnosticCommand");
            Object[] args = {Arrays.copyOfRange(cmd, 2, cmd.length)};
            return String.valueOf(ManagementFactory.getPlatformMBeanServer()
                    .invoke(name, cmd[1], args, new String[]{String[].class.getName()}));
        } catch (Exception e) {
            return "(unavailable: " + e + ")\n";
        }
    }

    public List<DumpFile> list() {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(this::describe)
                    .sorted(Comparator.comparingLong(DumpFile::modifiedAt).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Resolves a file name inside the dump directory, rejecting any path traversal. */
    public Optional<Path> resolve(String name) {
        Path candidate = directory.resolve(name).normalize();
        if (!candidate.getParent().equals(directory) || !Files.isRegularFile(candidate)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    private DumpFile describe(Path p) {
        String n = p.getFileName().toString();
        String kind = n.endsWith(".phd") || n.endsWith(".hprof") || n.endsWith(".hprof.gz") ? "heap"
                : n.startsWith("javacore") || n.startsWith("vmreport") ? "javacore"
                : n.startsWith("core") || n.endsWith(".dmp") ? "system"
                : n.startsWith("Snap") || n.endsWith(".trc") ? "snap"
                : n.startsWith("verbosegc") || n.startsWith("gc.") ? "gclog"
                : "other";
        try {
            return new DumpFile(n, kind, Files.size(p), Files.getLastModifiedTime(p).toMillis());
        } catch (IOException e) {
            return new DumpFile(n, kind, -1, 0);
        }
    }

    private static String prefix(Kind kind) {
        return switch (kind) {
            case HEAP -> "heapdump";
            case JAVACORE -> "javacore";
            case SYSTEM -> "core";
        };
    }

    private static Path resolveDirectory(Path preferred) {
        for (Path p : List.of(preferred, Path.of(System.getProperty("java.io.tmpdir"), "leaklab-dumps"))) {
            try {
                Files.createDirectories(p);
                if (Files.isWritable(p)) {
                    return p.toAbsolutePath().normalize();
                }
            } catch (IOException ignored) {
                // try the next candidate
            }
        }
        throw new IllegalStateException("No writable dump directory");
    }

    static String human(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1 << 20) return (bytes >> 10) + " KiB";
        return "%.1f MiB".formatted(bytes / 1048576.0);
    }
}
