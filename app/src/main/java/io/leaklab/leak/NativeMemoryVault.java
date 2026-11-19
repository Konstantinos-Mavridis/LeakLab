package io.leaklab.leak;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Off-heap leak #2: raw native memory allocated outside the Java heap and never freed: through the
 * Foreign Function &amp; Memory API on Java 25, through {@code sun.misc.Unsafe} on Java 17 and 21 (see
 * {@code NativeAllocator}). This models a misbehaving JNI library, a native compression codec, or a
 * mis-used allocator.
 *
 * <p>No JVM limit applies: neither {@code -Xmx} nor {@code -XX:MaxDirectMemorySize}. The JVM
 * never throws, the heap looks clean, and the container working set climbs until the kernel's
 * cgroup OOM killer sends SIGKILL (exit code 137). The heap dump will <em>not</em> show this
 * leak; process RSS vs. heap committed will, and the javacore {@code NATIVEMEMINFO} section
 * attributes the growth.
 */
public final class NativeMemoryVault {

    /** One retained allocation; {@link #free()} gives it back. */
    @FunctionalInterface
    interface Block {
        void free();
    }

    private static final Deque<Block> BLOCKS = new ConcurrentLinkedDeque<>();
    private static final AtomicLong BYTES = new AtomicLong();

    private NativeMemoryVault() {
    }

    public static void retain(int bytes) {
        BLOCKS.add(NativeAllocator.allocate(bytes));
        BYTES.addAndGet(bytes);
    }

    public static void churn(int bytes) {
        NativeAllocator.churn(bytes);
    }

    public static int count() {
        return BLOCKS.size();
    }

    public static long bytes() {
        return BYTES.get();
    }

    public static long clear() {
        long freed = BYTES.getAndSet(0);
        Block block;
        while ((block = BLOCKS.poll()) != null) {
            block.free();
        }
        return freed;
    }
}
