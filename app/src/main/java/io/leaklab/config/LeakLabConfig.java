package io.leaklab.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Runtime configuration. Every key can be overridden with an environment variable,
 * e.g. {@code leaklab.guardian.threshold} -> {@code LEAKLAB_GUARDIAN_THRESHOLD}.
 */
@ConfigMapping(prefix = "leaklab")
public interface LeakLabConfig {

    /** Human readable name of the scenario this pod/container represents (shown in the UI). */
    @WithDefault("local")
    String scenario();

    /** Where on-demand and guardian dumps are written. Mount a PVC / volume here. */
    @WithDefault("/dumps")
    String dumpDir();

    Guardian guardian();

    Load load();

    interface Load {

        /** Leak target selected at start-up. Scenario C (greedy) starts on DIRECT: its lesson lives off-heap. */
        @WithDefault("HEAP")
        io.leaklab.load.LoadEngine.Mode mode();

        /**
         * After an OutOfMemoryError: pause the engine (false, the lab default, so the JVM stays diagnosable) or keep
         * driving load (true), as a real service keeps receiving requests. Switchable in the UI at runtime.
         */
        @WithDefault("false")
        boolean keepRunningOnOom();
    }

    interface Guardian {

        /** Take a pre-emptive heap dump + javacore before the kernel OOM killer fires. */
        @WithDefault("true")
        boolean enabled();

        /** Fire when container working set / cgroup limit crosses this ratio. */
        @WithDefault("0.90")
        double threshold();

        /** Re-arm once the ratio drops back below this value. */
        @WithDefault("0.75")
        double rearm();

        /**
         * Headroom (MiB) the dumps themselves need: the guardian fires early enough to leave at least this much below
         * the limit. -1 = auto: 64 on HotSpot (a heap dump needs ~30 MiB extra, measured, while the leak keeps
         * growing between samples), 0 on OpenJ9 (a PHD needs next to nothing).
         */
        @WithDefault("-1")
        int reserveMb();
    }
}
