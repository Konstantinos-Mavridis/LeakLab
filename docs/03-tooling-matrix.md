# Tooling matrix — what to use, when, and what to look for

## By question

| Question | OpenJ9 / IBM Semeru | HotSpot (Temurin, Red Hat build of OpenJDK…) | Look for |
|---|---|---|---|
| Is it a leak at all? | Verbose GC (`-Xverbosegclog`) → **IBM GCMV** | `-Xlog:gc*:file=…` → GCeasy / GCViewer / JDK Mission Control | Heap **after global GC** rising cycle after cycle |
| What is retained, and by whom? | **PHD** → **Eclipse MAT + IBM DTFJ**; IBM HeapAnalyzer | **HPROF** → Eclipse MAT, VisualVM | Leak Suspects, dominator tree, path to GC roots |
| What grows over time? | 2–3 PHDs → MAT *Compare to another Heap Dump*; `jcmd GC.class_histogram` diff | Same; JFR `jdk.ObjectCount` | Per-class deltas proportional to traffic |
| Where was it allocated? | `-Xdump:stack:events=allocation,filter=#…`; Health Center (where supported) | **JFR** `jdk.OldObjectSample` (path-to-GC-roots), async-profiler `alloc` | Call path into the accumulating structure |
| Field values (keys, ids, strings)? | **System dump** → MAT+DTFJ / `jdmpview` | HPROF already has them | Prove *why* entries never match |
| Native / off-heap growth? | **javacore NATIVEMEMINFO** diff; `pmap`, `smaps_rollup` | **NMT** (`-XX:NativeMemoryTracking=summary`, `jcmd VM.native_memory summary.diff`) | Which category grows: Direct/Unsafe, Threads, JIT, Classes, or none (→ malloc/JNI) |
| Threads, locks, hangs? | javacore → **IBM TMDA** | `jcmd Thread.print`, JMC | Thread count growth, blocked pools |
| Was it an OOMKill? | `docker inspect`, `oc describe pod`, node `journalctl -k`, cgroup `memory.events` | same | `OOMKilled`/137 and *absence* of JVM dump lines |
| Live trend in a fleet? | Micrometer → Prometheus/Grafana; LeakLab UI | same | `working_set / limit`, live-set slope, restarts with reason |

## Eclipse MAT checklist

| Step | Where | What good evidence looks like |
|---|---|---|
| Leak Suspects | wizard on open | One suspect holding a large % via one accumulation point |
| Dominator tree | toolbar | Small shallow / huge retained object near the top |
| Histogram → Immediate Dominators | right-click `byte[]` | Your domain class (not a JDK class) owns the arrays |
| Path to GC Roots (excl. weak/soft) | right-click instance | A *static field*, a *thread*, or a *classloader* you can name |
| Merge Shortest Paths | right-click class | All instances converge on one root ⇒ one leak |
| Compare dumps | histogram toolbar | Instance delta ∝ elapsed requests |
| OQL | toolbar | Targeted proof (`@retainedHeapSize`, field filters) |
| Thread Overview | Java Basics | Per-thread retained (ThreadLocal leaks) |
| Class Loader Explorer | Java Basics | Duplicate classes ⇒ classloader leak |

## OpenJ9 dump-agent cheat sheet

```text
-Xdump:what                                          # print the active agents (do this first)
-Xdump:directory=/dumps                              # one place for every artefact
-Xdump:heap:events=user                              # kill -QUIT → PHD too
-Xdump:heap+java:events=systhrow,filter=java/lang/OutOfMemoryError,range=1..4   # (default behaviour, explicit)
-Xdump:java+heap:events=throw,filter=java/lang/OutOfMemoryError,msg_filter=*direct*,range=1..1      # OOM thrown by Java code (direct buffers): no default agent fires
-Xdump:heap:events=allocation,filter=#256m           # dump on any single allocation ≥ 256 MiB
-Xdump:stack:events=allocation,filter=#8m            # print stack of large allocators
-Xdump:system:none                                   # disable cores (disk / data-protection constraints)
-Xdump:heap:opts=CLASSIC                             # text heap dump instead of PHD
-Xverbosegclog:/dumps/verbosegc.%Y%m%d.%H%M%S.%pid.%seq.log,5,20000 # rotating GC log
```

On Linux, OpenJ9 system cores are produced through the kernel's core-dump mechanism, so the node's
`/proc/sys/kernel/core_pattern` (host-wide, often piped to `systemd-coredump` on RHCOS) decides where a core lands.
Prefer PHD + javacore in containers; reach for system cores deliberately.

## javacore sections that matter for memory

| Tag | Meaning |
|---|---|
| `1CICONTINFO` · `1CICGRPINFO` · `3CICGRPINFO` (ENVINFO) | `Running in container : TRUE`, cgroup support, **`Memory Limit`** the JVM saw, `Memory Usage`, `Approached/Reached memory limit count` |
| `0SECTION NATIVEMEMINFO` → `1MEMUSER`…`5MEMUSER` | Native memory by JVM component: Classes, Memory Manager (GC) → Java Heap, Threads → Native Stack, JIT → Code/Data Cache, Class Libraries → `sun.misc.Unsafe` → Direct Byte Buffers / Other (FFM, Unsafe). Counts **reservations**: diff two javacores |
| `1STHEAPTYPE` / `1STHEAPTOTAL` / `1STHEAPINUSE` | Heap totals |
| `1STSEGTYPE` | Internal memory segments (class, JIT code, data) |
| `1STGCHTYPE` | Recent GC history |
| `1XMTHDINFO` / `3XMTHREADINFO` | Threads (count growth = thread leak) |

## Production-readiness defaults (what this repo ships)

- `JAVA_DIAG_OPTS` on every container: dump directory on a PVC, heap dump on SIGQUIT, rotating verbose GC log.
- Heap sized by `MaxRAMPercentage`, direct memory capped, `requests == limits` for memory.
- Liveness probe independent of memory; generous timeout for dump pauses.
- `terminationMessagePolicy: FallbackToLogsOnError`.
- Prometheus metrics: JVM pools, GC, plus cgroup working set / limit and live set after GC.
- Pre-OOMKill guardian dump at 90 %.
