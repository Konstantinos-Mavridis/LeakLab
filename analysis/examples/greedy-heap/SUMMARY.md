# LeakLab analysis — greedy-heap (20261017T122137Z)

| | |
|---|---|
| Target dump | `heapdump.greedy.20261017.121347.1.phd` |
| GC log | `verbosegc.20261017.121158.1.001.log` |
| Suspect class | `io.leaklab.leak.SessionContext` |

## Findings

**Heap context** — heap in the dump ≈ 827 MiB of a 973 MiB maximum (85%).

**GC trend** — live set: first 30.5 MiB → last 892.2 MiB (Δ +861.8 MiB), peak 892.2 MiB; rose in 15 of 15 intervals

> VERDICT: rising floor — consistent with a heap leak

**MAT leak suspect** — The class io.leaklab.leak.CustomerSessionCache , loaded by <system class loader> , occupies 808,913,136 (93.25%) bytes. The top consumers of its minimum retained heap are byte[] (4,632 instances totaling 808,575,560), java.lang.Object[] (1,542 instances totaling 86,352), and java.lang.String (3,090 instances totaling 74,160). The memory is accumulated in one instance of java.util.concurrent.ConcurrentHashMap$Node[] , loaded by <system class loader> , which occupies 808,912,760 (93.25%) bytes.

**Biggest dominators:**

```
Class Name                                                                    | Shallow Heap | Retained Heap | Percentage
--------------------------------------------------------------------------------------------------------------------------
class io.leaklab.leak.CustomerSessionCache @ 0xc37f2e18                       |          224 |   808,913,136 |     93.25%
io.leaklab.load.LoadEngine_ClientProxy @ 0xc6846d70                           |           72 |    25,165,984 |      2.90%
io.leaklab.load.LoadEngine @ 0xc370ba98                                       |           64 |    25,165,976 |      2.90%
```

**Why it stays alive** (path to GC roots of one instance):

```
Class Name                                                                       | Shallow Heap | Retained Heap
----------------------------------------------------------------------------------------------------------------
io.leaklab.leak.SessionContext @ 0xc33f55f8                                      |           32 |       524,480
|-  java.util.concurrent.ConcurrentHashMap$Node @ 0xc33f5548                     |           24 |       524,576
|  |- [348] java.util.concurrent.ConcurrentHashMap$Node[4096] @ 0xfefa3680       |       16,392 |   808,912,760
|  |  |-  java.util.concurrent.ConcurrentHashMap @ 0xc6849ee0                    |           64 |   808,912,824
|  |  |  |-  class io.leaklab.leak.CustomerSessionCache @ 0xc37f2e18 System Class|          224 |   808,913,136
|  |  |  '- Total: 1 entry                                                       |              |              
|  |  '- Total: 1 entry                                                          |              |              
|  '- Total: 1 entry                                                             |              |              
```

**Direct buffer wrappers on the heap** — 74 objects, 5,328 bytes of heap (the native memory they own is not in the heap dump)

**Native memory snapshot** (single javacore; heap and JIT are reservations):

```
JRE: 1,610,708,880 bytes / 9045 allocations
Java Heap: 1,020,063,744 bytes / 1 allocation
Threads: 19,307,128 bytes / 200 allocations
JIT Code Cache: 268,435,456 bytes / 1 allocation
Class Libraries: 1,602,872 bytes / 80 allocations
VM Class Libraries: 1,602,872 bytes / 80 allocations
sun.misc.Unsafe: 1,499,184 bytes / 48 allocations
Direct Byte Buffers: 1,378,832 bytes / 31 allocations
sun.misc.Unsafe > Other: 120,352 bytes / 17 allocations
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
