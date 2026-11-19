package io.leaklab.leak;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The native vault on whichever allocator this build compiled: sun.misc.Unsafe on Java 17 and 21 (21 is the default),
 * FFM on Java 25. Run the suite with -Dmaven.compiler.release=17 / =25 on a matching JDK to cover all three.
 */
class NativeMemoryVaultTest {

    private static final int MIB = 1 << 20;

    @Test
    void retainedBlocksAreCountedAndFreed() {
        NativeMemoryVault.clear();
        for (int i = 0; i < 3; i++) {
            NativeMemoryVault.retain(MIB);
        }
        NativeMemoryVault.churn(MIB);                   // freed at once: neither counted nor retained

        assertEquals(3, NativeMemoryVault.count());
        assertEquals(3L * MIB, NativeMemoryVault.bytes());
        assertEquals(3L * MIB, NativeMemoryVault.clear());
        assertEquals(0, NativeMemoryVault.count());
        assertEquals(0L, NativeMemoryVault.bytes());
    }
}
