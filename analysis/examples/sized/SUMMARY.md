# LeakLab analysis — sized (20261017T122300Z)

| | |
|---|---|
| Target dump | `heapdump.sized.20261017.104316.1.phd` |
| Baseline dump | `heapdump.sized.20261017.104116.1.phd` |
| GC log | `verbosegc.1.003.log` |
| Suspect class | `io.leaklab.leak.SessionContext` |

## Findings

**Heap context** — heap in the dump ≈ 298 MiB of a 614 MiB maximum (49%).

**GC trend** — live set: first 30.6 MiB → last 299.9 MiB (Δ +269.3 MiB), peak 614.2 MiB; rose in 31 of 47 intervals

> VERDICT: heap exhausted — 22 global GCs recovered almost nothing (GC death spiral before OutOfMemoryError)

**MAT leak suspect** — The class io.leaklab.leak.CustomerSessionCache , loaded by <system class loader> , occupies 254,830,240 (81.44%) bytes. The top consumers of its minimum retained heap are byte[] (2,919 instances totaling 254,619,624), java.lang.Object[] (971 instances totaling 54,376), and java.lang.String (1,948 instances totaling 46,752). The memory is accumulated in one instance of java.util.concurrent.ConcurrentHashMap$Node[] , loaded by <system class loader> , which occupies 254,829,864 (81.44%) bytes.

**What grew** (target − baseline):

```
Class (target − baseline)                               Δ objects Δ shallow bytes
byte[]                                                         +2961       +254665512
java.lang.Object[]                                              +975           +54552
java.lang.String                                               +1988           +47712
io.leaklab.leak.SessionContext                                  +971           +31072
java.util.concurrent.ConcurrentHashMap$Node                     +980           +23520
java.time.Instant                                               +971           +23304
java.util.ImmutableCollections$MapN                             +971           +23304
```

**Biggest dominators:**

```
Class Name                                                                    | Shallow Heap | Retained Heap | Percentage
--------------------------------------------------------------------------------------------------------------------------
class io.leaklab.leak.CustomerSessionCache @ 0xd9e49a70                       |          224 |   254,830,240 |     81.44%
io.leaklab.load.LoadEngine @ 0xd9cf00f8                                       |           64 |    25,166,048 |      8.04%
io.leaklab.load.LoadEngine_ClientProxy @ 0xd9e7caf0                           |           72 |    25,165,984 |      8.04%
```

**Why it stays alive** (path to GC roots of one instance):

```
Class Name                                                                       | Shallow Heap | Retained Heap
----------------------------------------------------------------------------------------------------------------
io.leaklab.leak.SessionContext @ 0xd9d81940                                      |           32 |       262,336
|-  java.util.concurrent.ConcurrentHashMap$Node @ 0xd9d7c3a0                     |           24 |       524,864
|  |- [44] java.util.concurrent.ConcurrentHashMap$Node[2048] @ 0xd9b7e9f8        |        8,200 |   254,829,864
|  |  |-  java.util.concurrent.ConcurrentHashMap @ 0xd9f15260                    |           64 |   254,829,928
|  |  |  |-  class io.leaklab.leak.CustomerSessionCache @ 0xd9e49a70 System Class|          224 |   254,830,240
|  |  |  '- Total: 1 entry                                                       |              |              
|  |  '- Total: 1 entry                                                          |              |              
|  '- Total: 1 entry                                                             |              |              
```

**Direct buffer wrappers on the heap** — 53 objects, 3,816 bytes of heap (the native memory they own is not in the heap dump)

**Native memory delta** (javacore NATIVEMEMINFO):

```
      Δ bytes    Δ allocs  category
          +1.8 MiB      +1672  JRE
          +1.0 MiB        +28  JRE > JIT
          +1.0 MiB        +28  JRE > JIT > Other
          +0.7 MiB      +1657  JRE > VM
          +0.5 MiB         +5  JRE > VM > Threads
```

> VERDICT: no significant native growth (largest Δ 1.8 MiB)

## Files

- `01-inventory.txt`
- `02-gc-trend.txt`
- `03-leak-suspects`
- `04-histogram.txt`
- `05-histogram-delta.csv`
- `05-histogram-delta.txt`
- `06-dominator-tree.txt`
- `07-immediate-dominators.txt`
- `08-oql-suspect-instances.txt`
- `09-path-to-gc-roots.txt`
- `10-direct-buffers.txt`
- `10-native-delta.txt`
- `SUMMARY.md`

Open `03-leak-suspects/index.html` in a browser for MAT's full interactive-style report.
