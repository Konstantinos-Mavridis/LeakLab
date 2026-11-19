package io.leaklab.telemetry;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.util.List;
import java.util.stream.Collectors;

/** Static facts about the JVM and its launch configuration — shown in the UI header. */
public record RuntimeInfo(String runtime, String vm, String vmVersion, String javaVersion, String flavor,
                          String gcPolicy, List<String> jvmArguments, int processors, long pid,
                          String hostname, long startTime, String vendor) {

    public static RuntimeInfo detect() {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        String vm = System.getProperty("java.vm.name", "?");
        boolean openJ9 = vm.toLowerCase().contains("openj9");
        List<String> args = rt.getInputArguments();
        String gc = openJ9
                ? args.stream().filter(a -> a.startsWith("-Xgcpolicy:")).map(a -> a.substring(11)).findFirst().orElse("gencon (default)")
                : ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).collect(Collectors.joining(" + "));
        return new RuntimeInfo(
                System.getProperty("java.runtime.name", "?"),
                vm,
                System.getProperty("java.vm.version", "?"),
                System.getProperty("java.runtime.version", System.getProperty("java.version")),
                openJ9 ? "OpenJ9" : "HotSpot",
                gc,
                args,
                Runtime.getRuntime().availableProcessors(),
                ProcessHandle.current().pid(),
                resolveHostname(),
                rt.getStartTime(),
                // tells Oracle ("Oracle Corporation") from Temurin ("Eclipse Adoptium"): both are "OpenJDK" + HotSpot
                System.getProperty("java.vendor", "?"));
    }

    private static String resolveHostname() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
