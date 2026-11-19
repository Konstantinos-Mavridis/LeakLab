# Runbook 06 — The same lab on HotSpot (Red Hat, Oracle, Eclipse Temurin) and on Java 17 and 21

Every scenario was re-run on three HotSpot distributions, the Red Hat build of OpenJDK (LeakLab's default JVM, in
version 21), Oracle's OpenJDK build and Eclipse Temurin, with the same application, container limits and `JAVA_OPTS` as
on IBM Semeru (the Eclipse OpenJ9 VM). Only the JVM and its diagnostic flags change. This runbook records what
happened, where HotSpot behaves differently from OpenJ9, and which of those differences change what you must configure
beforehand. Each JVM runs on Java 17, 21 and 25 LTS; the two sections after the artefacts compare the versions, and the
last one runs scenario G (80 %, G1) on all twelve variants.

| Env file | JVM | Stack · containers | Ports | Dumps |
|---|---|---|---|---|
| `redhat-21.env` (the default) | Red Hat build of OpenJDK 21.0.12 (HotSpot) | `leaklab-redhat-21` · `leaklab-<scenario>-redhat-21` | 6180–6183 | `./dumps/redhat-21/<scenario>` |
| `redhat-25.env` | Red Hat build of OpenJDK 25.0.4 (HotSpot) | `leaklab-redhat-25` · `leaklab-<scenario>-redhat-25` | 6080–6083 | `./dumps/redhat-25/<scenario>` |
| `redhat-17.env` | Red Hat build of OpenJDK 17.0.16 on UBI 8, image 1.21 (HotSpot) | `leaklab-redhat-17` · `leaklab-<scenario>-redhat-17` | 6780–6783 | `./dumps/redhat-17/<scenario>` |
| `semeru-25.env` | IBM Semeru 25.0.4 (OpenJ9) | `leaklab-semeru-25` · `leaklab-<scenario>-semeru-25` | 8080–8083 | `./dumps/semeru-25/<scenario>` |
| `semeru-21.env` | IBM Semeru 21.0.12 (OpenJ9) | `leaklab-semeru-21` · `leaklab-<scenario>-semeru-21` | 8180–8183 | `./dumps/semeru-21/<scenario>` |
| `semeru-17.env` | IBM Semeru 17.0.20 (OpenJ9) | `leaklab-semeru-17` · `leaklab-<scenario>-semeru-17` | 8780–8783 | `./dumps/semeru-17/<scenario>` |
| `oracle-25.env` | Oracle OpenJDK 25.0.2 (HotSpot) | `leaklab-oracle-25` · `leaklab-<scenario>-oracle-25` | 9080–9083 | `./dumps/oracle-25/<scenario>` |
| `oracle-21.env` | Oracle OpenJDK 21.0.2 (HotSpot) | `leaklab-oracle-21` · `leaklab-<scenario>-oracle-21` | 9180–9183 | `./dumps/oracle-21/<scenario>` |
| `oracle-17.env` | Oracle OpenJDK 17.0.2 (HotSpot) | `leaklab-oracle-17` · `leaklab-<scenario>-oracle-17` | 9780–9783 | `./dumps/oracle-17/<scenario>` |
| `temurin-25.env` | Eclipse Temurin 25.0.4 (HotSpot) | `leaklab-temurin-25` · `leaklab-<scenario>-temurin-25` | 7080–7083 | `./dumps/temurin-25/<scenario>` |
| `temurin-21.env` | Eclipse Temurin 21.0.12 (HotSpot) | `leaklab-temurin-21` · `leaklab-<scenario>-temurin-21` | 7180–7183 | `./dumps/temurin-21/<scenario>` |
| `temurin-17.env` | Eclipse Temurin 17.0.20 (HotSpot) | `leaklab-temurin-17` · `leaklab-<scenario>-temurin-17` | 7780–7783 | `./dumps/temurin-17/<scenario>` |

```bash
docker compose --profile all up --build                      # A–D on the default, Red Hat 21: http://localhost:6180–6183
docker compose --env-file temurin-25.env up --build sized    # http://localhost:7080 (7081 overcommit, 7082 greedy, 7083 native)
docker compose --profile analysis run --rm -e BASELINE=- analyzer /dumps/temurin-25/native
```

All twelve stacks can run side by side. The images all come from `app/Containerfile`: `JVM=hotspot|openj9` picks
the VM family (and its diagnostics), `JAVA_VERSION=17|21|25` the release the jar is compiled for, `HOTSPOT_IMAGE` or
`RUNTIME_IMAGE` the distribution. HotSpot images must be JDKs, for `jcmd`.

## Which image

| Image | Licence | Pull | Updates |
|---|---|---|---|
| `registry.access.redhat.com/ubi9/openjdk-21` / `-25` (**`redhat-21.env`**, the default, and `redhat-25.env`) | GPLv2 + CPE, on UBI 9 (freely redistributable) | anonymous | current updates from Red Hat (21.0.12 and 25.0.4 here); supported with a Red Hat subscription, certified for OpenShift. Runs as UID 185: the Containerfile switches to root to create `/dumps`, then to 1001 |
| `container-registry.oracle.com/java/openjdk:25` (**`oracle-25.env`**) | GPLv2 + CPE | anonymous | Oracle publishes OpenJDK builds only for 6 months: this one is frozen at **25.0.2** (January 2026) |
| `container-registry.oracle.com/java/openjdk:21` (**`oracle-21.env`**) | GPLv2 + CPE | anonymous | frozen at **21.0.2** (January 2024): two and a half years without security fixes. Fine for the lab only |
| `container-registry.oracle.com/java/openjdk:17` (**`oracle-17.env`**) | GPLv2 + CPE | anonymous | frozen at **17.0.2** (January 2022): almost five years without security fixes. Fine for the lab only |
| `container-registry.oracle.com/java/jdk:25` / `:21` / `:17` (Oracle JDK) | Oracle licence terms | needs `docker login container-registry.oracle.com` | current updates |
| `eclipse-temurin:25-jdk` / `:21-jdk` / `:17-jdk` (**`temurin-*.env`**) | GPLv2 + CPE | anonymous | current updates from Eclipse Adoptium (25.0.4, 21.0.12 and 17.0.20 here) |

Per version, they are the same HotSpot VM built from the same source. For production, use an image that still receives
updates: Red Hat's (LeakLab's default), Temurin, or Oracle JDK
(`LEAKLAB_HOTSPOT_IMAGE=container-registry.oracle.com/java/jdk:25` in `oracle-25.env`, after logging in).

## What HotSpot needs that OpenJ9 does not (all start-up flags, so a restart)

HotSpot writes **nothing** on `OutOfMemoryError`, logs no GC and tracks no native memory unless told to. The image sets:

```
-XX:+HeapDumpOnOutOfMemoryError                     .hprof on the first VM-thrown OOM
-XX:HeapDumpPath=/dumps/java_oom.<start time>.hprof   set by the entrypoint, see gotcha 2
-XX:NativeMemoryTracking=summary                    NMT, the counterpart of NATIVEMEMINFO (start-up only, 5–10 % overhead)
-Xlog:gc*:file=/dumps/gc.%t.log:time,uptime,level,tags:filecount=5,filesize=20m
```

## Results, side by side: Java 25 (verified 17–18 October 2026, Docker Desktop, cgroup v2)

Red Hat, Oracle and Temurin behaved the same in every scenario: same collector, same outcome, same artefacts, with
timings and sizes within normal run-to-run variation. They are one column below; numbers are the range seen across all
runs (three full verifications of Oracle and Temurin, one of Red Hat, 17–18 October 2026). Timings are from runs
where one JVM wrote at a time: with two writing 1 GB dumps at once, the same dumps took four to five times longer.

| Scenario | IBM Semeru 25.0.4 (OpenJ9) | HotSpot: Red Hat build of OpenJDK 25.0.4 / Oracle OpenJDK 25.0.2 / Eclipse Temurin 25.0.4 |
|---|---|---|
| GC chosen in a 1 GiB / 2 CPU container | gencon | **Serial** (below 1792 MiB HotSpot does not consider the machine "server class") |
| **A** heap leak → Java OOM | OOM at 596 of 614 MiB. Default agents write PHD (2.8 MB, 0.4 s), javacore, snap. JVM stays up | `java_oom.*.hprof` **614 MB in 13.5–55 s** (JVM paused throughout; Java 21 is much faster, see below). JVM stays up |
| A, second OOM in the same JVM | dumps again (up to 4 events) | **no dump**: `HeapDumpOnOutOfMemoryError` fires once per JVM |
| A, operator heap dumps D1/D2 | 2.7 MB each, 0.2 s | 76–122 MB each, 2–3 s (`live=true` runs a full GC first) |
| A, direct-buffer OOM (capped at 96 MiB) | no dump by default; the image's `events=throw` agent writes one | **no dump, and no flag exists** that changes this |
| A, SIGQUIT | javacore (+ PHD with `-Xdump:heap:events=user`) | thread dump to **stdout only**. `collect-evidence.sh` uses `jcmd GC.heap_dump -gz=1` instead (6–7.5 MB) |
| **B** `-Xmx1g` in 512 MiB → OOMKill | OOMKilled, exit 137, only the GC log | the same. The GC log ends with a young GC **started, never finished** |
| B with the guardian | fires at 91 %, javacore + PHD in 0.3 s, then killed | first Oracle runs: **killed mid-dump** (truncated `.hprof` + `.hprof.p0`). After the fix (gotcha 6): a full 345–376 MiB dump in 9–21 s, then killed |
| **C** greedy: the direct-buffer leak (its main experiment) | OOMKilled; guardian javacore shows *Direct Byte Buffers* 625–872 MB | OOMKilled; guardian VM report shows NMT **Other 750–815 MiB** committed. The heap dump was cut off on both (once the VM report too): the leak grew ~50 MiB/s |
| **C** `MaxRAMPercentage=95`, heap-leak variant | **OOMKilled**: 973 MiB heap + ~73 MiB non-heap > 1024 | **survived on all three**: Java OOM, 965 MB dump, container at 1016–1017 of 1024 MiB, `memory.events oom_kill 0` (in one run `max 27`: the limit was hit and the kernel reclaimed page cache from the dump being written instead of killing). In another, a diagnostic `docker exec` was the last straw (gotcha 10). Lean Serial-GC non-heap fits by ~9 MiB; one more thread pool could tip it. The JVM was unresponsive for 2–5 minutes while the guardian and OOM dumps were written |
| **D** native (FFM) leak | OOMKilled; heap flat; NATIVEMEMINFO Δ +435–444 MiB under `sun.misc.Unsafe > Other` | OOMKilled; heap flat; NMT Δ **Other +361–383 MiB in ~1460–1490 allocations** (× 256 KiB); guardian `.hprof` complete (76–113 MiB) |
| Analysis stage | MAT + DTFJ on PHD | MAT on `.hprof` and `.hprof.gz` directly (31–50 s per run). HPROF paths name the fields: `SESSIONS class CustomerSessionCache` |

The verdict banner predicted every OOMKill and every Java OOM on all twelve variants. HotSpot 25's survival of C's heap
variant is the edge case: heap and non-heap fit by ~9 MiB, so the verdict names the uncapped direct allowance instead
("The off-heap allowance doesn't fit in the room left"). That is right for the scenario's main experiment and
conservative for the heap variant, which a ~9 MiB margin let survive. A margin that thin is inside the estimate's
error: treat it as a red flag in its own right.
The distribution matters for licensing and updates, not for memory behaviour: what changes the outcome is the VM
(OpenJ9 or HotSpot), its collector and its defaults.

## HotSpot gotchas found while verifying

1. **Serial GC in small containers.** With < 2 CPUs or < 1792 MiB, HotSpot picks Serial GC. Full GCs are rare until
   the old generation fills, so the live-set signal arrives late. Choose a collector explicitly (`-XX:+UseG1GC`) if
   you rely on it.
2. **`java_pid1.hprof` is never overwritten.** HotSpot names the OOM dump after the PID, which is always 1 in a
   container, and refuses to overwrite it: `Unable to create /dumps/java_pid1.hprof: File exists`. After the first
   restart, every later OOM dump is lost. The image's entrypoint puts the start time into `-XX:HeapDumpPath`.
3. **First OOM only.** `HeapDumpOnOutOfMemoryError` writes one dump per JVM. Pair it with `-XX:+ExitOnOutOfMemoryError`
   in production, so the restarted JVM can dump again (with gotcha 2 fixed).
4. **Direct-buffer OOMs are never dumped.** "Cannot reserve … bytes of direct buffer memory" is thrown by Java code,
   and HotSpot has no equivalent of OpenJ9's `events=throw` agent. The app has to react, or someone takes a dump.
5. **HPROF is big and slow**: about the size of the live heap, because unlike PHD it contains array contents (field
   values and strings too, so treat it as sensitive data). The JVM is paused while it is written. `jcmd 1 GC.heap_dump
   -gz=1` measured 7.6 MB in 1.6 s instead of 385 MB in 8.8 s for the same heap (lab payloads compress unusually well).
   `GC.heap_dump` is not exported over JMX, so in-app dumps (`HotSpotDiagnosticMXBean.dumpHeap`) cannot compress.
   JDK 25 is slower still than JDK 21: it writes segment files and merges them at the end (see the Java 21 section).
6. **The dump itself needs memory**: about 30 MiB extra, measured on every variant. And `live=true` runs a full GC
   that, with `-Xmx` above the limit, can grow the heap. Both killed the guardian's container mid-dump. The guardian now
   (a) dumps without the full GC (MAT drops unreachable objects anyway) and (b) on HotSpot fires early enough to leave
   64 MiB below the limit (`LEAKLAB_GUARDIAN_RESERVE_MB`). A leak of ~50 MiB/s still outruns it: write the small
   report first, then the heap dump.
7. **SIGQUIT is not a capture trigger.** Use `jcmd` (a JDK image) from outside: `scripts/collect-evidence.sh` detects
   HotSpot and runs `GC.heap_dump -gz=1` plus a VM report.
8. **`jps`, `jstat` and `jcmd PerfCounter.print` don't see the JVM** when the UID has no `/etc/passwd` entry (UID 1001
   here, random UIDs on OpenShift): HotSpot skips its `hsperfdata` file (`user.name = ?`). Attach-based `jcmd`
   commands still work.
9. **NMT is start-up only.** Without `-XX:NativeMemoryTracking`, `VM.native_memory` reports nothing, and there is no
   way to switch it on later. JDK 25 prints `(malloc=…KB tag=Other #N)`; `nativemem-diff.sh` reads both formats.
10. **Diagnosing a container at its limit can kill it.** Anything you `docker exec` / `kubectl exec` runs inside the
    container's cgroup. Observed: Temurin survived C's heap variant at 1016 of 1024 MiB, and the `sh` that read
    `memory.events` was the allocation that triggered the OOM kill (`sh invoked oom-killer … Killed process (java)` in
    the kernel log). `jcmd` is worse: it starts a second JVM of ~30–40 MiB. Capture evidence *before* the limit
    (guardian, alerts), read cgroup files from the node, and expect an exec near the limit to be the last straw.

## LeakLab's HotSpot artefacts

| OpenJ9 | HotSpot | Produced by |
|---|---|---|
| `heapdump.*.phd` | `heapdump.*.hprof` (in-app), `heapdump.jcmd.*.hprof.gz` (script), `java_oom.*.hprof` (OOM) | UI / API, guardian, `collect-evidence.sh`, the JVM |
| `javacore.*.txt` | `vmreport.*.txt`: jcmd `VM.command_line`, `VM.flags`, `VM.native_memory summary`, `Thread.print` | the *Javacore* button (labelled *VM report* on HotSpot), guardian, script |
| `verbosegc.<start>.*.log` | `gc.<start>.log` | the JVM |

The analyzer reads both sets: `gc-trend.sh` parses unified GC logs ("Pause Full" and G1 "Pause Remark" are the
live-set samples), `nativemem-diff.sh` diffs NMT summaries, and reports are only diffed when they come from the same JVM
(`Process start ticks`, field 22 of `/proc/1/stat`).

## Java 21 versus Java 25 (verified 18 October 2026)

Every scenario was run again on Java 21 LTS: the Red Hat build of OpenJDK 21.0.12 (LeakLab's default JVM), IBM Semeru
21.0.12, Oracle OpenJDK 21.0.2 and Eclipse Temurin 21.0.12, with the same image layout, limits and `JAVA_OPTS`. The jar is compiled with `--release 21`. One class differs:
FFM (`java.lang.foreign`) is a preview API on Java 21, so the native leak allocates with `sun.misc.Unsafe` there
(`app/src/main/native-unsafe`) instead of an FFM `Arena` (`app/src/main/native-ffm`). FFM itself allocates through
Unsafe, so the evidence is the same: NATIVEMEMINFO `sun.misc.Unsafe > Other`, NMT `Other`.

| Scenario | OpenJ9: Semeru 21 vs 25 | HotSpot: Red Hat / Oracle / Temurin 21 vs 25 |
|---|---|---|
| Defaults | identical: the same `-Xdump:what` agents, unknown `-XX:` flags silently ignored | identical: Serial GC in 1 GiB / 2 CPUs, unknown flags rejected |
| Non-heap footprint at start-up (`sized`) | 71 MiB on both | **81–97 MiB on 21**, 57–59 MiB on 25 |
| **A** heap leak → Java OOM | the same: default agents, the `events=throw` agent catches the direct-buffer OOM | the same artefacts: one `java_oom.*.hprof`, no dump for the second OOM or the direct-buffer OOM. **Written in 3.1–9.3 s on 21, 13.5–55 s on 25** (634 vs 614 MB, one JVM at a time) |
| **B** overcommit | the same: OOMKilled with only GC logs; the guardian's javacore + PHD in 0.3 s | the same: OOMKilled; the guardian's full `.hprof` (287–341 MiB, 1.7–10.5 s) before the kill |
| **C** direct-buffer leak | the same: OOMKilled; *Direct Byte Buffers* 865 MB in the guardian javacore | the same: OOMKilled; NMT *Other* 775–795 MiB; on 21 the guardian's `.hprof` was complete (89–91 MiB) |
| **C** heap-leak variant | the same: OOMKilled | **OOMKilled on 21** (all three), where 25 survives by ~9 MiB: 941 MiB heap + 81–95 MiB non-heap reaches the limit |
| **D** native leak | the same: NATIVEMEMINFO Δ +442 MiB under `sun.misc.Unsafe > Other` (Unsafe on 21, FFM on 25: same category) | the same: NMT Δ *Other* +375–394 MiB |
| Analysis stage | the same suspects and verdicts | the same suspects and verdicts |

The C heap variant is the one outcome that changes, and it changes with the version, not the distribution: HotSpot 21
needs 25–30 MiB more outside the heap than HotSpot 25, most of it outside the categories NMT tracks, and that is more
than the margin HotSpot 25 survived by. The verdicts of Red Hat 21 and Temurin 21 were red before any load ("Heap
ceiling + native footprint exceed the limit"), Oracle 21's named the direct allowance (it fit by 2 MiB). Red Hat 21,
LeakLab's default, has the largest footprint of the three: 94–97 MiB. Either way, a configuration that only
just fits on one Java version is a configuration that fails on another: upgrade or downgrade the JVM and the margin
moves. Size with headroom, and re-check the verdict after every JVM change.

The other difference is how long HotSpot pauses for a heap dump. JDK 25 writes it in parallel segments
(`java_oom.*.hprof.p0`, `.p1`, …) and merges them into one file at the end; JDK 21 writes the file in one pass. In a
2-CPU container on Docker Desktop JDK 25 took 13.5–55 s where JDK 21 took 3–9 s for the same heap, and the JVM
answers nothing while it runs. A crashing JVM doesn't care, but a guardian dump of a live service does: on Java 25,
expect a pause of a minute or more for a heap under 1 GiB in a container this small, and give liveness probes room for it.

## Java 17 (verified 20 October 2026)

Every scenario was run once more on Java 17 LTS: IBM Semeru 17.0.20, Oracle OpenJDK 17.0.2 and Eclipse Temurin
17.0.20, and the Red Hat build of OpenJDK, first 17.0.20 on UBI 9 and, since 21 October, 17.0.16 on UBI 8 (image 1.21,
the image `redhat-17.env` now uses), with the same image layout, limits and `JAVA_OPTS`. Both Red Hat builds behaved
the same. The jar is compiled
with `--release 17` and uses the same `sun.misc.Unsafe` allocator as Java 21 (FFM is only an incubator API on 17).
Quarkus 3.40.1 runs unchanged; the one source change was to stop using `Math.clamp`, which is Java 21 API.

| Scenario | OpenJ9: Semeru 17 | HotSpot: Red Hat / Oracle / Temurin 17 |
|---|---|---|
| Defaults | the same as 21 and 25 | the same: Serial GC in 1 GiB / 2 CPUs, unknown flags rejected |
| Non-heap footprint at start-up (`sized`) | 70 MiB | 78–83 MiB: between Java 25 (57–59) and Java 21 (81–97) |
| **A** heap leak → Java OOM | the same: default agents, the `events=throw` agent catches the direct-buffer OOM | the same artefacts; the OOM `.hprof` (634 MB) in **3.5–8.8 s**, one pass as on 21 |
| **B** overcommit | the same; the guardian's javacore + PHD in 0.3 s | the same; the guardian's full `.hprof` (280–406 MiB, 1.8–2.5 s) before the kill, except once in five runs of the UBI 9 Red Hat build: killed before the first file was written |
| **C** direct-buffer leak | the same: OOMKilled; *Direct Byte Buffers* 893 MB in the guardian javacore | the same: OOMKilled; NMT *Other* 775–815 MiB; the guardian's `.hprof` complete (87 MiB) |
| **C** heap-leak variant | OOMKilled | **OOMKilled**, as on 21: 941 MiB heap + 77–82 MiB non-heap is within 1–6 MiB of the limit before any load. The guardian captured it on Oracle, Temurin and Red Hat on UBI 8 (681 MiB in 17.6 s), not on the UBI 9 Red Hat build |
| **D** native leak | the same: NATIVEMEMINFO Δ +451 MiB under `sun.misc.Unsafe > Other` | the same: NMT Δ *Other* +388–404 MiB |
| Analysis stage | the same suspects and verdicts | the same suspects and verdicts |

Java 17 sits with Java 21, not 25: HotSpot 17 needs 20–25 MiB more outside the heap than HotSpot 25, enough to turn
C's heap variant into an OOMKill, and it writes heap dumps in one pass, so its dump pauses are short. Its verdict for C
names the direct allowance ("The off-heap allowance doesn't fit in the room left"), because heap and non-heap fit by a
few MiB before load; under load that margin is gone. Oracle's 17 image is 17.0.2 from January 2022, and LeakLab's Red Hat 17 image is pinned at 17.0.16 (July 2025):
for anything but the lab use Temurin's, or Red Hat's without the pinned tag, which are current.

## Scenario G: 80 %, G1, G1ReservePercent=10 (verified 21 October 2026)

`-XX:MaxRAMPercentage=80 -XX:+UseG1GC -XX:G1ReservePercent=10` in a 1 GiB container, with direct memory left
uncapped: a common production setting. `G1ReservePercent` keeps 10 % of the heap free as evacuation space; it is part
of the 820 MiB heap and leaves no extra room outside it. On OpenJ9 the two G1 flags are unknown `-XX` options and
silently ignored, so there G is 80 % on gencon. Run on all twelve variants:

| Variant | Non-heap at start | Heap leak | Direct-buffer leak (direct limit = max heap ≈ 820 MiB) |
|---|---|---|---|
| Red Hat 17 (UBI 8) | 87 MiB | Java OOM after 27 s; `.hprof` 467 MB in 6.3 s | **OOMKilled** after 19 s; the guardian's evidence first |
| Red Hat 21 | 106 MiB | Java OOM after 63 s; 868 MB in 43 s | Java OOM ("Cannot reserve … direct buffer memory") |
| Red Hat 25 | 93 MiB | Java OOM after 65 s; 867 MB in 44 s | Java OOM |
| Semeru 17 / 21 / 25 | 70–73 MiB | Java OOM after 44–45 s; default dump agents | Java OOM |
| Oracle 17 | 93 MiB | Java OOM after 22 s; 467 MB in 2.4 s | **hung**: neither killed nor answering after 2 min, at 1013 of 1024 MiB |
| Oracle 21 | 93 MiB | Java OOM after 24 s; 868 MB in 4.6 s | Java OOM |
| Oracle 25 | 105 MiB | Java OOM after 39 s; 868 MB in 20 s | **hung**, at 1022 of 1024 MiB |
| Temurin 17 | 48 MiB | Java OOM after 25 s; 442 MB in 2.2 s | **OOMKilled** after 19 s |
| Temurin 21 | 91 MiB | Java OOM after 32 s; 868 MB in 12 s | Java OOM |
| Temurin 25 | 102 MiB | Java OOM after 54 s; 868 MB in 34 s | **OOMKilled** after 29 s |

The heap leak is the safe case everywhere: the heap fills, the JVM throws its own `OutOfMemoryError`, the dumps are
written and the container survives. On HotSpot the working set stayed at 590–615 MiB after the OOM: G1 commits all 820
MiB of heap but never touched much of it. The direct-buffer leak is not safe. Direct memory defaults to a cap the size
of the max heap, so 820 MiB of heap plus up to 820 MiB of direct buffers plus 90–105 MiB of JVM overhead is promised
in 1024 MiB; which limit is hit first, the JVM's direct cap or the container's, decides between a Java OOM at the very
edge (seven variants), an OOMKill (three) or a JVM that stalls at the limit without dying (two). The guardian
captured evidence in every direct run, and the verdict was red from the start ("The off-heap allowance doesn't fit in
the room left"). The fix is one flag: `-XX:MaxDirectMemorySize=64m` (or what the service measurably needs). It brings the
projection to 923–989 MiB (TIGHT), and the direct-buffer leak then ended as a Java OOM ("Cannot reserve … direct
buffer memory", dumpable on OpenJ9) on every variant tried: Red Hat 17, Temurin 25 and Oracle 25, the three that had
been killed or hung, and Semeru 25.