# Runbook 05 — Forensics readiness: what must exist *before* the incident

Memory forensics only works with evidence that was set up to exist before the failure. Most of the items below are
JVM flags, pod-spec settings or image contents, and they only take effect after a **restart**. During an incident,
that restart wipes the very state you wanted to study. Treat this list as part of the platform standard (golden
image and deployment template), not as an incident checklist.

The PDF field guide ([LeakLab-Field-Guide.pdf](https://github.com/Konstantinos-Mavridis/LeakLab/releases/latest/download/LeakLab-Field-Guide.pdf), attached to every release) has a readiness page at the end of every
scenario chapter, with per-item reasoning and plain-language explanations.

## What you get with no configuration at all (verified)

This was run on the IBM Semeru 25 (OpenJ9 0.61) image with LeakLab's diagnostic options removed
(`JAVA_DIAG_OPTS=`), under load. Semeru 17 and 21 have the same defaults (the same `-Xdump:what` agents, the same
silent handling of unknown flags), and so do HotSpot 17 and 21 compared with HotSpot 25:

| Question | Default behaviour | Evidence |
|---|---|---|
| GC log? | **None.** Not to a file, not to stdout/stderr (same on HotSpot) | 115 scavenges + 2 global GCs ran, and 0 GC lines appeared in the container log |
| Dump on `OutOfMemoryError`? | **Yes, when the VM throws it** (heap): system core (first OOM), heapdump, javacore, snap (first 4) | `java -Xdump:what` lists `systhrow … java/lang/OutOfMemoryError` agents |
| Dump on a direct-buffer `OutOfMemoryError`? | **No.** "Cannot reserve … direct buffer memory" is thrown by Java code (`java.nio.Bits`), a `throw` event, not `systhrow`. Same on HotSpot with `HeapDumpOnOutOfMemoryError` | `MaxDirectMemorySize=96m`, direct leak: the error was logged, 0 `JVMDUMP` lines (both JVMs tested) |
| What does `kill -QUIT 1` produce? | **javacore only**, no heap dump | `-Xdump:java:events=…+user…`; no heap agent on `user` |
| Where do dumps go? | The JVM's **working directory**, here `/deployments` in the container's writable layer, which is lost when the container is replaced | `JVMDUMP010I Java dump written to /deployments/javacore.….txt` |
| Native memory accounting? | **Yes**, NATIVEMEMINFO is in every javacore | |
| Unknown `-XX:` flag? | **Silently ignored** (a typo means default sizing) | `-XX:MaxRamPercentage=60` starts normally |
| HotSpot | No OOM dump, no GC log, no NMT unless configured; SIGQUIT only prints threads to stdout | Red Hat build of OpenJDK 21.0.12 (LeakLab's default) / 17.0.16 / 25.0.4, Oracle OpenJDK 21.0.2 / 17.0.2 / 25.0.2 and Eclipse Temurin 21.0.12 / 17.0.20 / 25.0.4, same lab (Runbook 06) |

**When it must be in place**

| Tag | Meaning |
|---|---|
| 🧱 build | Baked into the container image (packages, permissions, entrypoint, instrumentation) |
| 🔁 restart | JVM flag or pod spec: takes effect only after a restart |
| ⚡ runtime | Possible on the running JVM, no restart |
| 🏗️ platform | Outside the app (metrics stack, node logging, policies); must exist beforehand |
| 🔍 after | Only needed for the analysis |

## Matrix (● essential · ○ useful · Default = unconfigured OpenJ9 on Kubernetes)

| Item | Default | Flag / config / tool | When | A sized | B overcommit | C greedy | D native |
|---|---|---|---|:-:|:-:|:-:|:-:|
| Writable dump dir (group 0) | **off** | `chmod g=u /dumps` | 🧱 | ● | ● | ● | ● |
| Copy-out tools and a shell | varies | `tar`, `procps`; a shell for `kubectl exec … kill -QUIT 1` | 🧱 | ● | ○ | ○ | ● |
| JVM is PID 1 | varies | `exec java …` in the entrypoint | 🧱 | ● | ○ | ● | ● |
| Metrics instrumentation | **off** | Micrometer: heap, GC, direct buffers | 🧱 | ● | ○ | ● | ● |
| Dump directory on a volume | **off** | `-Xdump:directory=/dumps` + PVC (default: working dir, container layer) | 🔁 | ● | ○ | ● | ● |
| Volume writable by the runtime UID | **off** | `securityContext.fsGroup` (automatic on OpenShift `restricted-v2`); Docker on Linux: chown the host dir | 🔁 | ● | ○ | ● | ● |
| Writable `/tmp` under a read-only root FS | on | `emptyDir` at `/tmp` (attach for `jcmd`, default dump location) | 🔁 | ● | ○ | ○ | ● |
| Heap dump on SIGQUIT | **off** | `-Xdump:heap:events=user` (default SIGQUIT = javacore only) | 🔁 | ● | ○ | ○ | ○ |
| Dump on a library-thrown OOM (direct buffers) | **off** | `-Xdump:java+heap:events=throw,filter=java/lang/OutOfMemoryError,msg_filter=*direct*,range=1..1` (default agents only fire on `systhrow`; HotSpot's `HeapDumpOnOutOfMemoryError` ignores it too) | 🔁 | ● | ○ | ○ | ○ |
| GC log, unique per start | **off** | `-Xverbosegclog:/dumps/verbosegc.%Y%m%d.%H%M%S.%pid.%seq.log,5,20000` | 🔁 | ● | ● | ● | ○ |
| Allocation call paths | **off** | `-Xdump:stack:events=allocation,filter=#1m`; HotSpot JFR (`jcmd JFR.start` at runtime) | 🔁 | ● | ○ | ○ | ○ |
| Heap inside the limit | varies | `-XX:MaxRAMPercentage` from a measured non-heap footprint | 🔁 | ● | ● | ● | ○ |
| Direct memory capped | **off** | `-XX:MaxDirectMemorySize` (default ≈ max heap) | 🔁 | ○ | ○ | ● | ○ |
| Fail fast on flag typos | **off** | `-XX:-IgnoreUnrecognizedVMOptions -XX:-IgnoreUnrecognizedXXColonOptions` | 🔁 | ○ | ● | ● | ○ |
| Exit after OOM | **off** | `-XX:+ExitOnOutOfMemoryError` | 🔁 | ● | ○ | ○ | ○ |
| Pre-kill capture | **off** | guardian / sidecar / alert automation at ~90 % working set | 🔁 | ○ | ● | ● | ● |
| Probe tolerance, termination message | **off** | liveness timeouts (K8s default 1 s × 3); `terminationMessagePolicy: FallbackToLogsOnError` (default `File`) | 🔁 | ● | ● | ● | ● |
| HotSpot only | **off** | `-XX:+HeapDumpOnOutOfMemoryError`, `-XX:NativeMemoryTracking=summary` | 🔁 | ● | ○ | ○ | ● |
| Dump on OutOfMemoryError (OpenJ9) | on | default agents: heapdump, javacore, snap, core | ⚡ | ● | ○ | ○ | ○ |
| javacore (threads, cgroup, NATIVEMEMINFO) | on | `kill -QUIT 1`; Docker: `docker kill -s QUIT <c>` (no shell needed) | ⚡ | ● | ● | ● | ● |
| Effective options and heap ceiling | on | javacore `1CICMDLINE`, `2CIENVVAR`, `3CICGRPINFO`; `java $JAVA_OPTS -verbose:sizes -version` | ⚡ | ○ | ● | ● | ○ |
| OS view of memory | on | `/proc/1/status`, `/proc/1/smaps_rollup`, `kubectl debug --target` | ⚡ | ○ | ○ | ● | ● |
| Metrics scraped, history and alerts | varies | OpenShift: `ServiceMonitor` + user-workload monitoring (annotations ignored); working set / limit, live-set slope, OOMKilled reason | 🏗️ | ● | ● | ● | ● |
| Central log retention | **off** | OpenShift Logging / Loki / EFK (kubelet keeps ~10 MiB and one previous instance) | 🏗️ | ● | ● | ● | ○ |
| Diagnostics that don't allocate in the container | **off** | anything exec'd runs in the container's cgroup: near the limit, a `sh` or `jcmd` can trigger the kill (observed). Capture before the limit; read cgroup files from the node | 🏗️ | ○ | ● | ● | ● |
| Node kernel log and access | on | journald retention, `oc debug node` rights | 🏗️ | ○ | ● | ● | ● |
| Core dumps permitted | varies | node `core_pattern`, `ulimit -c`, disk | 🏗️ | ○ | ○ | ○ | ● |
| Dump data-handling policy | n/a | classification, encryption, access, retention | 🏗️ | ● | ○ | ● | ● |
| Analysis workbench | n/a | MAT + IBM DTFJ, `gc-trend.sh`, `nativemem-diff.sh`, forensics script | 🔍 | ● | ● | ● | ● |

## What each scenario specifically depends on

- **A, heap leak (Java OOM).** OpenJ9 writes OOM dumps **by default**, but only usefully if `/dumps` is a writable,
  persistent volume. The two-dump technique needs an on-demand trigger (`-Xdump:heap:events=user`, `jcmd` in a JDK
  image, or app code calling `com.ibm.jvm.Dump`). The GC log must already be running when the leak starts. HotSpot
  needs `-XX:+HeapDumpOnOutOfMemoryError` set explicitly. A **capped direct-buffer leak** is caught as an
  `OutOfMemoryError` but leaves no dump on either JVM unless OpenJ9 has an `events=throw` agent for it.
- **B, explicit overcommit: `-Xmx` above the limit (OOMKill).** The JVM can't help after a SIGKILL. What remains is the GC log on a
  volume (with a timestamp in its name, because `%pid` is always 1 and a restart would overwrite it), the runtime's
  termination record (Kubernetes keeps only the *last* one; Docker resets `State.OOMKilled` on auto-restart), platform
  metrics, and the node kernel log. Pre-kill capture is the only way to get heap evidence.
- **C, implicit overcommit: the heap fits but direct memory is uncapped, or the percentage leaves no room (OOMKill).**
  As B, plus visibility of the *effective*
  options: the Semeru base image sets its own `JAVA_TOOL_OPTIONS`, and OpenJ9 **silently ignores** unknown `-XX:`
  flags. Tested: `-XX:MaxRamPercentage=60` (typo) starts with default sizing. Only both
  `-XX:-IgnoreUnrecognizedVMOptions` and `-XX:-IgnoreUnrecognizedXXColonOptions` make it abort. Track direct memory as its
  own metric, and alert on the cgroup working set, not RSS (RSS read 1,056 MiB in a 1,024 MiB container). Capping
  direct memory (`-XX:MaxDirectMemorySize`) turns the kill into a Java OOM, which is Scenario A's direct-buffer variant.
- **D, native leak (OOMKill).** Everything cappable is capped here, and it still dies. NATIVEMEMINFO is in **every** javacore with no flag, and `kill -QUIT 1` works at any
  time. Take two javacores while there is still headroom. Beyond the JVM's accounting you need OS tools (`smaps_rollup`
  always; `pmap` needs `procps` or a debug container), jemalloc (image plus `LD_PRELOAD`, so a restart), and permission
  for system cores (node `core_pattern`; they failed under Docker Desktop with `JVMDUMP012E`). HotSpot's equivalent,
  NMT, is a start-up flag with 5–10 % overhead.

## On HotSpot (Red Hat, Oracle, Eclipse Temurin): what changes (verified, see [Runbook 06](06-hotspot-jvms.md))

| Item | Default on HotSpot | Flag / config / tool | When |
|---|---|---|---|
| Heap dump on `OutOfMemoryError` | **off** | `-XX:+HeapDumpOnOutOfMemoryError`. Fires on the **first** OOM of a JVM only, never for direct-buffer OOMs | 🔁 |
| Unique OOM dump name per start | **off**: `java_pid1.hprof`, never overwritten ("File exists") | `-XX:HeapDumpPath=/dumps/java_oom.$(date …).hprof` in the entrypoint | 🧱 |
| Exit after the OOM dump | **off** | `-XX:+ExitOnOutOfMemoryError`, so the next JVM can dump again | 🔁 |
| Native memory accounting | **off** (no NATIVEMEMINFO equivalent) | `-XX:NativeMemoryTracking=summary`, then `jcmd 1 VM.native_memory summary` | 🔁 |
| GC log | **off** | `-Xlog:gc*:file=/dumps/gc.%t.log:…:filecount=5,filesize=20m` (`%t` = start time) | 🔁, or ⚡ `jcmd 1 VM.log` |
| On-demand heap dump | SIGQUIT = thread dump on stdout only | `jcmd 1 GC.heap_dump -gz=1 …` from outside, so a **JDK** image | 🧱 |
| Headroom for the dump itself | — | ~30 MiB extra while dumping; dump without a full GC near the limit | 🔁 |
| `jps` / `jstat` visibility | **off** for a UID without a name | an `/etc/passwd` entry for the runtime UID (`hsperfdata`) | 🧱 |
| Collector choice | Serial below 2 CPUs / 1792 MiB | `-XX:+UseG1GC` (or another) explicitly | 🔁 |

## Changes you can make without a restart

- Take javacores (`kill -QUIT 1`): threads, GC history, cgroup view, NATIVEMEMINFO, effective options.
- Take heap dumps, if a trigger was prepared: SIGQUIT agent, `jcmd` (JDK image), or an app endpoint. OpenJ9 can also
  add dump agents at runtime with `com.ibm.jvm.Dump.setDumpOptions(…)`, but only if the application exposes it.
- HotSpot only: start GC logging with `jcmd 1 VM.log output=… what=gc`.
- Read `/proc/1/*`, run a second short-lived `java -verbose:sizes` (only while there is memory headroom), or attach
  an ephemeral debug container.
- Everything in the analysis stage (`docker compose --profile analysis …`).

Everything else (heap sizing, direct cap, GC log, dump directory, probes, termination policy, image contents,
allocator settings) means a **restart**, so it has to be in place before the incident.
