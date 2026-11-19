package io.leaklab.leak;

import java.lang.reflect.Field;
import sun.misc.Unsafe;

/**
 * Native memory through {@code sun.misc.Unsafe}: the Java 17 and 21 builds, where FFM is not final yet (an incubator
 * API on 17, a preview on 21).
 * The evidence is the same as with FFM, which allocates through Unsafe internally: NMT counts it under "Other",
 * OpenJ9's NATIVEMEMINFO under "sun.misc.Unsafe → Other". The Java 25 build compiles {@code src/main/native-ffm}.
 */
final class NativeAllocator {

    private static final Unsafe UNSAFE = unsafe();

    private NativeAllocator() {
    }

    static NativeMemoryVault.Block allocate(int bytes) {
        long address = UNSAFE.allocateMemory(bytes);
        UNSAFE.setMemory(address, bytes, (byte) 0x5A); // commit every page: untouched pages do not count toward RSS
        return () -> UNSAFE.freeMemory(address);
    }

    static void churn(int bytes) {
        allocate(bytes).free();
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
