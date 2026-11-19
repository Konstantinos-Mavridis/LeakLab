package io.leaklab.leak;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Native memory through the Foreign Function &amp; Memory API, final since Java 22: the Java 25 build.
 * The Java 17 and 21 builds compile {@code src/main/native-unsafe} instead: FFM is an incubator API on 17 and a
 * preview on 21.
 */
final class NativeAllocator {

    private NativeAllocator() {
    }

    static NativeMemoryVault.Block allocate(int bytes) {
        Arena arena = Arena.ofShared();
        touch(arena.allocate(bytes, 64));
        return arena::close;
    }

    static void churn(int bytes) {
        try (Arena arena = Arena.ofConfined()) {
            touch(arena.allocate(bytes, 64));
        }
    }

    private static void touch(MemorySegment segment) {
        segment.fill((byte) 0x5A); // commit every page: untouched pages do not count toward RSS
    }
}
