#!/usr/bin/env bash
# Capture a javacore + heap dump from a running LeakLab JVM and copy the dump directory out.
#
#   scripts/collect-evidence.sh docker leaklab-sized-redhat-21  # containers are leaklab-<scenario>-<jvm>-<version>
#   scripts/collect-evidence.sh k8s leaklab deploy/leaklab-sized       # KUBECTL=oc for OpenShift
#
# Trigger, OpenJ9: SIGQUIT to PID 1. That always writes a javacore; the image adds -Xdump:heap:events=user so it
# also writes a PHD heap dump. No jcmd needed in the JRE image.
# Trigger, HotSpot: SIGQUIT only prints a thread dump to stdout (nothing lands in /dumps), so use jcmd instead
# (the HotSpot images are JDKs): GC.heap_dump → .hprof, VM.native_memory summary → NMT, Thread.print.
set -euo pipefail

KUBECTL=${KUBECTL:-kubectl}
OUT=${OUT:-./evidence}
mode=${1:?docker|k8s}

# HotSpot capture through jcmd; $1 = command prefix that runs a command inside the container
hotspot_capture() {
  local stamp; stamp=$(date -u +%Y%m%d.%H%M%S)
  echo "▸ HotSpot: jcmd GC.heap_dump + VM report (SIGQUIT would only print a thread dump to stdout)"
  # MSYS_NO_PATHCONV: Git Bash on Windows would otherwise rewrite the in-container path into a Windows path
  # -gz=1: measured 7.6 MB in 1.6 s instead of 385 MB in 8.8 s for the same heap (shorter pause, less page cache); MAT
  # reads .hprof.gz directly.
  MSYS_NO_PATHCONV=1 "$@" jcmd 1 GC.heap_dump -gz=1 "/dumps/heapdump.jcmd.$stamp.hprof.gz" >/dev/null
  # "Process start ticks" (field 22 of /proc/1/stat) identifies the JVM instance, so the analyzer only diffs reports of
  # one JVM. (jcmd PerfCounter.print / jstat / jps would need hsperfdata, which HotSpot skips for a UID with no name.)
  "$@" sh -c "{ echo 'HotSpot VM report (jcmd)'; echo \"Process start ticks: \$(cut -d' ' -f22 /proc/1/stat)\";
               for c in VM.command_line VM.flags 'VM.native_memory summary' Thread.print; do
                 echo; echo \"===== jcmd 1 \$c =====\"; jcmd 1 \$c | tail -n +2; done; } > /dumps/vmreport.jcmd.$stamp.txt"
}

is_hotspot() { "$@" sh -c 'command -v jcmd >/dev/null && ! java -version 2>&1 | grep -q OpenJ9'; }

case "$mode" in
  docker)
    c=${2:?container name}
    # Everything exec'd below runs inside the container's cgroup. Near the limit it can trigger the very OOM kill you are
    # investigating (observed with a plain sh; jcmd starts a whole second JVM). Collect before the limit when you can.
    if is_hotspot docker exec "$c"; then
      hotspot_capture docker exec "$c"
    else
      echo "▸ SIGQUIT → $c"
      docker kill -s QUIT "$c" >/dev/null     # the runtime delivers the signal: no shell needed in the image
      sleep 5
    fi
    mkdir -p "$OUT/$c"
    docker cp "$c:/dumps/." "$OUT/$c/"
    ;;
  k8s|oc|openshift)
    ns=${2:?namespace} target=${3:?pod/name or deploy/name}
    pod=$($KUBECTL exec -n "$ns" "$target" -- hostname)   # a pod's hostname is its name
    if is_hotspot $KUBECTL exec -n "$ns" "$pod" --; then
      hotspot_capture $KUBECTL exec -n "$ns" "$pod" --
    else
      echo "▸ SIGQUIT → pod/$pod"
      $KUBECTL exec -n "$ns" "$pod" -- sh -c 'kill -QUIT 1'
      sleep 5
    fi
    $KUBECTL exec -n "$ns" "$pod" -- sh -c 'ls -lh /dumps'
    mkdir -p "$OUT/$pod"
    # kubectl cp needs `tar` in the image (present in the Noble image; UBI minimal may lack it —
    # then use port-forward + the UI's download links: kubectl port-forward -n $ns pod/$pod 8080).
    $KUBECTL cp -n "$ns" "$pod:/dumps" "$OUT/$pod"
    ;;
  *) echo "usage: $0 docker <container> | k8s <namespace> <pod/x|deploy/x>" >&2; exit 2 ;;
esac

echo "▸ evidence in $OUT:"
find "$OUT" -type f -newermt '-10 minutes' -printf '   %TT  %10s  %p\n' 2>/dev/null || ls -lR "$OUT"
cat <<'EOF'

Next — run the analysis stage on this folder (docs/04-analysis-stage.md):
  docker compose --profile analysis run --rm -v "$PWD/evidence/<name>:/evidence:ro" analyzer /evidence

Or by hand:
  *.phd / *.hprof  → Eclipse MAT (+ IBM DTFJ for PHD/core): Leak Suspects → Dominator Tree → Path to GC Roots
  javacore*.txt    → 1MEMUSER / NATIVEMEMINFO (where native memory went), 1STGCHTYPE (GC history), threads
  vmreport*.txt    → HotSpot: VM.native_memory summary (diff two of them: nativemem-diff.sh), VM.flags, threads
  verbosegc*.log   → IBM GCMV: plot "used heap after global GC" — a rising floor is the leak
  gc.*.log         → HotSpot unified GC log: "Pause Full … X->Y(Z)" — Y rising across full GCs is the leak
EOF
