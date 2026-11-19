package io.leaklab.leak;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Off-heap leak #1: direct {@link ByteBuffer}s that are never released.
 *
 * <p>Direct memory lives outside the Java heap, so it is invisible to heap occupancy charts —
 * but it is accounted against {@code -XX:MaxDirectMemorySize}. With that cap set, the JVM throws
 * {@code OutOfMemoryError: Cannot reserve … bytes of direct buffer memory} and stays alive. That error is
 * thrown by {@code java.nio.Bits}, not by the VM, so neither OpenJ9's default dump agents nor HotSpot's
 * HeapDumpOnOutOfMemoryError react to it: the image adds an {@code events=throw} agent for it. Without a
 * sensible cap the default allows as much direct memory as the max heap — and the container is
 * OOMKilled long before that.
 *
 * <p>In a heap dump only the tiny {@code java.nio.DirectByteBuffer} wrapper objects appear
 * (shallow ~64 bytes). Look at their {@code capacity} field, or run OQL:
 * <pre>SELECT SUM(b.capacity) FROM java.nio.DirectByteBuffer b</pre>
 */
public final class DirectBufferVault {

    private static final Queue<ByteBuffer> BUFFERS = new ConcurrentLinkedQueue<>();
    private static final AtomicLong BYTES = new AtomicLong();

    private DirectBufferVault() {
    }

    public static void retain(int bytes) {
        BUFFERS.add(allocate(bytes));
        BYTES.addAndGet(bytes);
    }

    /** Allocate and drop: freed later by the buffer's Cleaner once GC notices it is unreachable. */
    public static void churn(int bytes) {
        allocate(bytes);
    }

    private static ByteBuffer allocate(int bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes);
        for (int i = 0; i < bytes; i += 4096) {
            buffer.put(i, (byte) 1);
        }
        return buffer;
    }

    public static int count() {
        return BUFFERS.size();
    }

    public static long bytes() {
        return BYTES.get();
    }

    /** Drops references; the native memory is returned after the next GC runs the Cleaners. */
    public static long clear() {
        long freed = BYTES.getAndSet(0);
        BUFFERS.clear();
        return freed;
    }
}
