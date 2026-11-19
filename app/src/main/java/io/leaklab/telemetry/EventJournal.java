package io.leaklab.telemetry;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Small in-memory operations journal streamed to the control room UI (and mirrored to the log). */
@ApplicationScoped
public class EventJournal {

    private static final Logger LOG = Logger.getLogger("io.leaklab.journal");
    private static final int CAPACITY = 200;

    public enum Level { INFO, WARN, CRITICAL }

    public record Entry(long seq, long timestamp, Level level, String message) {
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();

    public void info(String message) {
        append(Level.INFO, message);
    }

    public void warn(String message) {
        append(Level.WARN, message);
    }

    public void critical(String message) {
        append(Level.CRITICAL, message);
    }

    private synchronized void append(Level level, String message) {
        entries.addLast(new Entry(sequence.incrementAndGet(), System.currentTimeMillis(), level, message));
        while (entries.size() > CAPACITY) {
            entries.removeFirst();
        }
        switch (level) {
            case INFO -> LOG.info(message);
            case WARN -> LOG.warn(message);
            case CRITICAL -> LOG.error(message);
        }
    }

    public synchronized List<Entry> latest(int max) {
        List<Entry> all = new ArrayList<>(entries);
        return all.subList(Math.max(0, all.size() - max), all.size());
    }
}
