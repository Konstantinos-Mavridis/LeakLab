# LeakLab analysis — native (20261017T122203Z)

| | |
|---|---|
| Target dump | `heapdump.native.20261017.104248.1.phd` |
| GC log | `verbosegc.1.003.log` |
| Suspect class | `io.leaklab.load.LoadEngine` |

## Findings

**Heap context** — heap in the dump ≈ 56 MiB of a 384 MiB maximum (15%).

> The heap is mostly empty: the top suspect may be legitimate. If the process was still growing, look outside the heap (step 10).

**GC trend** — live set: first 30.5 MiB → last 55.9 MiB (Δ +25.4 MiB), peak 55.9 MiB; rose in 6 of 7 intervals

> VERDICT: slight rise (+13.3 MiB, 3% of max heap) — not conclusive; collect a longer window

**MAT leak suspect** — One instance of io.leaklab.load.LoadEngine_ClientProxy loaded by <system class loader> occupies 25,165,984 (42.80%) bytes. The top consumers of its minimum retained heap are byte[] (1 instance totaling 25,165,832), io.leaklab.load.LoadEngine_ClientProxy (1 instance totaling 72), and java.util.concurrent.atomic.AtomicLong (3 instances totaling 48). The memory is accumulated in one instance of byte[] , loaded by <system class loader> , which occupies 25,165,832 (42.80%) bytes.

**Biggest dominators:**

```
Class Name                                                                    | Shallow Heap | Retained Heap | Percentage
--------------------------------------------------------------------------------------------------------------------------
io.leaklab.load.LoadEngine_ClientProxy @ 0xe83bd330                           |           72 |    25,165,984 |     42.80%
io.leaklab.load.LoadEngine @ 0xe836a2e8                                       |           64 |    25,165,976 |     42.80%
io.quarkus.bootstrap.runner.RunnerClassLoader @ 0xe80c8a10                    |          136 |       377,736 |      0.64%
```

**Why it stays alive** (path to GC roots of one instance):

```
Class Name                                                            | Shallow Heap | Retained Heap
-----------------------------------------------------------------------------------------------------
io.leaklab.load.LoadEngine @ 0xe836a2e8                               |           64 |    25,165,976
|-  io.leaklab.load.LoadEngine$$Lambda.0x00000000bd33b838 @ 0xe83befe8|           16 |            16
|  |-  java.lang.Thread$FieldHolder @ 0xe83be098                      |           40 |            56
|  |  |-  java.lang.Thread @ 0xe83bd900 Unknown                       |          120 |           368
|  |  '- Total: 1 entry                                               |              |              
|  '- Total: 1 entry                                                  |              |              
|-  io.quarkus.arc.impl.ContextInstanceHandleImpl @ 0xe8369e20        |           32 |            64
'- Total: 2 entries                                                   |              |              
```

**Direct buffer wrappers on the heap** — 54 objects, 3,888 bytes of heap (the native memory they own is not in the heap dump)

**Native memory delta** (javacore NATIVEMEMINFO):

```
      Δ bytes    Δ allocs  category
        +625.8 MiB       +971  JRE > Class Libraries
        +625.8 MiB       +971  JRE > Class Libraries > VM Class Libraries
        +625.8 MiB       +971  JRE > Class Libraries > VM Class Libraries > sun.misc.Unsafe
        +625.6 MiB       +969  JRE > Class Libraries > VM Class Libraries > sun.misc.Unsafe > Other
        +612.2 MiB      +6919  JRE
```

> VERDICT: native growth of 625.6 MiB in "JRE > Class Libraries > VM Class Libraries > sun.misc.Unsafe > Other" — investigate off-heap allocations there

## Files

- `01-inventory.txt`
- `02-gc-trend.txt`
- `03-leak-suspects`
- `04-histogram.txt`
- `06-dominator-tree.txt`
- `07-immediate-dominators.txt`
- `08-oql-suspect-instances.txt`
- `09-path-to-gc-roots.txt`
- `10-direct-buffers.txt`
- `10-native-delta.txt`
- `SUMMARY.md`

Open `03-leak-suspects/index.html` in a browser for MAT's full interactive-style report.
