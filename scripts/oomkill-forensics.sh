#!/usr/bin/env bash
# Answers one question: did this JVM die from a Java OutOfMemoryError, or was it killed by the kernel?
#
#   scripts/oomkill-forensics.sh docker [container ...]          # default: all leaklab-* containers
#   scripts/oomkill-forensics.sh k8s <namespace> [label-selector] # works with oc too: KUBECTL=oc
#
# Signals, strongest first:
#   1. runtime flag      docker: State.OOMKilled=true      k8s: lastState.terminated.reason=OOMKilled
#   2. exit code         137 = 128 + SIGKILL(9). Necessary, not sufficient (liveness kill, docker kill, eviction)
#   3. absence of JVM    no "JVMDUMP055I ... java/lang/OutOfMemoryError" in the log, no new javacore/phd in /dumps
#   4. kernel log        "Memory cgroup out of memory: Killed process <pid> (java)" on the node
set -euo pipefail

KUBECTL=${KUBECTL:-kubectl}
bold() { printf '\033[1m%s\033[0m\n' "$*"; }
red() { printf '\033[31m%s\033[0m\n' "$*"; }
grn() { printf '\033[32m%s\033[0m\n' "$*"; }
ylw() { printf '\033[33m%s\033[0m\n' "$*"; }

# JVMDUMP055I (current OpenJ9) / JVMDUMP039I (older releases): "Processing dump event systhrow ... OutOfMemoryError"
# HotSpot with -XX:+HeapDumpOnOutOfMemoryError: "Dumping heap to …", then "Heap dump file created" (or "Unable to create")
jvm_oom_markers() { grep -E 'JVMDUMP0(39|55)I.*OutOfMemoryError|java.lang.OutOfMemoryError|JVMDUMP010I|Dumping heap to|Heap dump file created|Unable to create .*hprof' || true; }

docker_mode() {
  local containers=("$@")
  if [ ${#containers[@]} -eq 0 ]; then
    mapfile -t containers < <(docker ps -a --filter name=leaklab --format '{{.Names}}')
  fi
  for c in "${containers[@]}"; do
    bold "── $c"
    local fmt='{{.State.Status}}|{{.State.OOMKilled}}|{{.State.ExitCode}}|{{.State.FinishedAt}}|{{.HostConfig.Memory}}|{{.RestartCount}}'
    IFS='|' read -r status oom code finished limit restarts < <(docker inspect -f "$fmt" "$c")
    printf '   status=%s  OOMKilled=%s  exitCode=%s  restarts=%s  finishedAt=%s  memLimit=%s MiB\n' \
      "$status" "$oom" "$code" "$restarts" "$finished" "$((limit / 1048576))"
    local markers
    markers=$(docker logs "$c" 2>&1 | jvm_oom_markers | tail -n 5)
    if [ "$oom" = "true" ]; then
      red   "   VERDICT: kernel cgroup OOM kill. The JVM got SIGKILL — no OutOfMemoryError, no shutdown hooks, no -Xdump agents / HeapDumpOnOutOfMemoryError."
      [ -z "$markers" ] && ylw "   No JVMDUMP/OOM lines in the log: confirms the JVM never saw it coming."
    elif [ "$code" = "137" ]; then
      ylw   "   VERDICT: SIGKILL but not flagged OOM — docker kill / stop timeout / external killer. Check 'docker events'."
    elif [ -n "$markers" ]; then
      grn   "   VERDICT: Java-level OutOfMemoryError — the JVM was in control and wrote dumps:"
      printf '      %s\n' "$markers"
    elif [ "$status" = "running" ]; then
      grn   "   running"
    else
      printf '   exited normally or for a non-memory reason\n'
    fi
    printf '   last log lines:\n'; docker logs --tail 4 "$c" 2>&1 | sed 's/^/      /'
  done
  echo
  bold "Recent OOM events from the Docker daemon (last hour):"
  docker events --filter event=oom --since 1h --until "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --format '   {{.Time}}  {{.Actor.Attributes.name}}  {{.Action}}' || true
}

k8s_mode() {
  local ns=${1:?namespace required} sel=${2:-app.kubernetes.io/name=leaklab}
  bold "Pods in $ns ($sel)"
  $KUBECTL get pods -n "$ns" -l "$sel" -o custom-columns=\
'POD:.metadata.name,RESTARTS:.status.containerStatuses[0].restartCount,STATE:.status.containerStatuses[0].state.*.reason,LAST_REASON:.status.containerStatuses[0].lastState.terminated.reason,EXIT:.status.containerStatuses[0].lastState.terminated.exitCode,FINISHED:.status.containerStatuses[0].lastState.terminated.finishedAt,LIMIT:.spec.containers[0].resources.limits.memory'
  echo
  for pod in $($KUBECTL get pods -n "$ns" -l "$sel" -o name); do
    local reason code node
    reason=$($KUBECTL get -n "$ns" "$pod" -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}')
    code=$($KUBECTL get -n "$ns" "$pod" -o jsonpath='{.status.containerStatuses[0].lastState.terminated.exitCode}')
    node=$($KUBECTL get -n "$ns" "$pod" -o jsonpath='{.spec.nodeName}')
    bold "── $pod (node $node)"
    case "$reason" in
      OOMKilled) red "   VERDICT: kernel cgroup OOM kill of the previous container instance (exit $code)." ;;
      Error)     [ "$code" = "137" ] && ylw "   VERDICT: SIGKILL, not OOM — most often a failed liveness probe. Check events." \
                                     || printf '   previous instance exited with %s\n' "$code" ;;
      "")        grn "   no previous termination" ; continue ;;
      *)         printf '   previous termination: %s (exit %s)\n' "$reason" "$code" ;;
    esac
    local markers
    markers=$($KUBECTL logs -n "$ns" "$pod" --previous 2>/dev/null | jvm_oom_markers | tail -n 5)
    if [ -n "$markers" ]; then grn "   JVM OOM markers in previous log:"; printf '      %s\n' "$markers"
    else ylw "   no JVMDUMP / OutOfMemoryError in the previous log — the JVM never saw it coming"; fi
    printf '   termination message (FallbackToLogsOnError):\n'
    $KUBECTL get -n "$ns" "$pod" -o jsonpath='{.status.containerStatuses[0].lastState.terminated.message}' | tail -n 4 | sed 's/^/      /'
    echo
    printf '   events:\n'
    $KUBECTL get events -n "$ns" --field-selector "involvedObject.name=${pod#pod/}" \
      --sort-by=.lastTimestamp -o custom-columns='TIME:.lastTimestamp,REASON:.reason,MESSAGE:.message' | tail -n 6 | sed 's/^/      /'
    printf '   kernel log on the node (needs cluster-admin):\n'
    printf '      oc debug node/%s -- chroot /host journalctl -k --since -2h | grep -iE "oom-kill|Killed process|Memory cgroup out of memory"\n' "$node"
  done
}

case "${1:-docker}" in
  docker) shift || true; docker_mode "$@" ;;
  k8s|oc|openshift) shift; k8s_mode "$@" ;;
  *) echo "usage: $0 docker [container...] | k8s <namespace> [selector]" >&2; exit 2 ;;
esac
