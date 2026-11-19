# LeakLab analysis — greedy-direct (20261017T122110Z)

| | |
|---|---|
| Target dump | `heapdump.greedy.20261017.121625.1.phd` |
| GC log | `verbosegc.20261017.121453.1.001.log` |
| Suspect class | `io.leaklab.load.LoadEngine` |

## Findings

**Heap context** — heap in the dump ≈ 56 MiB of a 973 MiB maximum (6%).

> The heap is mostly empty: the top suspect may be legitimate. If the process was still growing, look outside the heap (step 10).

**GC trend** — live set: first 30.4 MiB → last 55.8 MiB (Δ +25.4 MiB), peak 55.8 MiB; rose in 2 of 2 intervals

> VERDICT: no sustained growth of the live set

**MAT leak suspect** — One instance of io.leaklab.load.LoadEngine_ClientProxy loaded by <system class loader> occupies 25,165,984 (42.95%) bytes. The top consumers of its minimum retained heap are byte[] (1 instance totaling 25,165,832), io.leaklab.load.LoadEngine_ClientProxy (1 instance totaling 72), and java.util.concurrent.atomic.AtomicLong (3 instances totaling 48). The memory is accumulated in one instance of byte[] , loaded by <system class loader> , which occupies 25,165,832 (42.95%) bytes.

**Biggest dominators:**

```
Class Name                                                                    | Shallow Heap | Retained Heap | Percentage
--------------------------------------------------------------------------------------------------------------------------
io.leaklab.load.LoadEngine_ClientProxy @ 0xc689dad0                           |           72 |    25,165,984 |     42.95%
io.leaklab.load.LoadEngine @ 0xc688b128                                       |           64 |    25,165,976 |     42.95%
io.quarkus.bootstrap.runner.RunnerClassLoader @ 0xc344e9d8                    |          136 |       377,040 |      0.64%
```

**Why it stays alive** (path to GC roots of one instance):

```
Class Name                                                            | Shallow Heap | Retained Heap
-----------------------------------------------------------------------------------------------------
io.leaklab.load.LoadEngine @ 0xc688b128                               |           64 |    25,165,976
|-  io.leaklab.load.LoadEngine$$Lambda.0x00000000e926eb88 @ 0xc68a02b8|           16 |            16
|  |-  java.lang.Thread$FieldHolder @ 0xc689ecc0                      |           40 |            56
|  |  |-  java.lang.Thread @ 0xc689dd30 Unknown                       |          120 |           368
|  |  '- Total: 1 entry                                               |              |              
|  '- Total: 1 entry                                                  |              |              
|-  io.quarkus.arc.impl.ContextInstanceHandleImpl @ 0xc68d9768        |           32 |            64
'- Total: 2 entries                                                   |              |              
```

**Direct buffer wrappers on the heap** — 1,664 objects, 119,808 bytes of heap (the native memory they own is not in the heap dump)

**Native memory snapshot** (single javacore; heap and JIT are reservations):

```
JRE: 2,426,118,520 bytes / 10471 allocations
Java Heap: 1,020,063,744 bytes / 1 allocation
Threads: 19,275,976 bytes / 198 allocations
JIT Code Cache: 268,435,456 bytes / 1 allocation
Class Libraries: 835,337,656 bytes / 1669 allocations
VM Class Libraries: 835,337,656 bytes / 1669 allocations
sun.misc.Unsafe: 835,233,968 bytes / 1637 allocations
Direct Byte Buffers: 835,123,952 bytes / 1621 allocations
sun.misc.Unsafe > Other: 110,016 bytes / 16 allocations
```

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
- `10-native-snapshot.txt`
- `SUMMARY.md`

Open `03-leak-suspects/index.html` in a browser for MAT's full interactive-style report.
