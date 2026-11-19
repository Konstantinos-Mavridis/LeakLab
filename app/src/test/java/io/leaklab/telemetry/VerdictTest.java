package io.leaklab.telemetry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The configuration verdict for the scenario shapes, including the manual default, with footprints measured in the
 * lab. Pure arithmetic: no container or JVM needed.
 */
class VerdictTest {

    private static final long MIB = 1L << 20;

    private static MemoryProbe.Verdict verdict(long limitMib, long heapMaxMib, long nonHeapMib, long directCapMib) {
        MemoryProbe.Heap heap = new MemoryProbe.Heap(60 * MIB, 80 * MIB, heapMaxMib * MIB, 55 * MIB, 55 * MIB);
        MemoryProbe.Container container = new MemoryProbe.Container("v2", limitMib * MIB, 0, 150 * MIB, 0, Map.of());
        long allowance = MemoryProbe.directAllowance(directCapMib > 0 ? directCapMib * MIB : -1, heap.max(), 0);
        return MemoryProbe.verdict(heap, container, nonHeapMib * MIB, allowance, directCapMib > 0);
    }

    @Test
    void sizedFitsIncludingTheDirectCap() {            // A: 60 % of 1 GiB, MaxDirectMemorySize=96m
        assertEquals("SIZED", verdict(1024, 614, 92, 96).level());
    }

    @Test
    void explicitOvercommitIsByDesign() {              // B: -Xmx1g in 512 MiB
        assertEquals("OOMKILL_BY_DESIGN", verdict(512, 1024, 80, 0).level());
    }

    @Test
    void percentageTrapIsCaughtEvenWhenTheHeapFits() { // C on HotSpot: 942 + 75 = 1017 < 1024, but direct ≈ heap more
        MemoryProbe.Verdict v = verdict(1024, 942, 75, 0);
        assertEquals("OOMKILL_LIKELY", v.level());
        assertEquals("The off-heap allowance doesn't fit in the room left", v.headline());
    }

    @Test
    void percentageTrapOnOpenJ9FailsAlreadyOnTheHeap() { // C on OpenJ9: 973 + 73 > 1024 before any direct growth
        assertEquals("Heap ceiling + native footprint exceed the limit", verdict(1024, 973, 73, 0).headline());
    }

    @Test
    void nativeScenarioIsSizedBecauseEverythingCappableIsCapped() { // D: 50 %, MaxDirectMemorySize=64m
        assertEquals("SIZED", verdict(768, 384, 80, 64).level());
    }

    @Test
    void uncappedDirectMemoryTurnsTheNativeScenarioRed() {          // D without the direct cap
        assertEquals("OOMKILL_LIKELY", verdict(768, 384, 80, 0).level());
    }

    @Test
    void g1ScenarioFitsOnTheHeapButNotWithTheDirectAllowance() { // G on Red Hat 17: 80 %, G1, 87 MiB non-heap
        MemoryProbe.Verdict v = verdict(1024, 820, 87, 0);
        assertEquals("OOMKILL_LIKELY", v.level());
        assertEquals("The off-heap allowance doesn't fit in the room left", v.headline());
    }

    @Test
    void manualDefaultIsRedBecauseDirectMemoryIsUncapped() {         // M: 75 % of 1 GiB and nothing else
        assertEquals("OOMKILL_LIKELY", verdict(1024, 768, 71, 0).level());
    }

    @Test
    void manualDefaultWithADirectCapIsTight() {                      // M + MaxDirectMemorySize=128m: 967 of 1024 MiB
        assertEquals("TIGHT", verdict(1024, 768, 71, 128).level());
    }

    @Test
    void allowanceShrinksAsDirectBuffersAreUsed() {
        assertEquals(36 * MIB, MemoryProbe.directAllowance(96 * MIB, 614 * MIB, 60 * MIB));
        assertEquals(0, MemoryProbe.directAllowance(96 * MIB, 614 * MIB, 200 * MIB));
        assertEquals(614 * MIB, MemoryProbe.directAllowance(-1, 614 * MIB, 0));
    }
}
