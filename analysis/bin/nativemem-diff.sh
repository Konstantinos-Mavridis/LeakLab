#!/usr/bin/env bash
# nativemem-diff.sh <older report> <newer report>
#
# Diffs the native-memory accounting of two reports of the same JVM and lists the categories whose native memory
# changed, largest growth first. Reads either:
#   OpenJ9   javacore NATIVEMEMINFO tree   (counts reservations: absolute values mislead, the DELTA is the signal)
#   HotSpot  NMT summary (jcmd 1 VM.native_memory summary, or LeakLab's vmreport) — committed memory per category
set -euo pipefail
[ $# -eq 2 ] || { echo "usage: $0 <older report> <newer report>" >&2; exit 2; }

if grep -q '^Native Memory Tracking:' "$1"; then MODE=NMT; else MODE=NATIVEMEMINFO; fi

parse() { if [ "$MODE" = NMT ]; then parse_nmt "$1"; else parse_javacore "$1"; fi; }

parse_nmt() {
  # "-   Other (reserved=435000KB, committed=435000KB)" then "(malloc=435000KB #1740)" → committed bytes, allocations
  awk '
    function flush() { if (name != "") printf "%s\t%d\t%d\n", name, kb * 1024, allocs; name = "" }
    /^Total: reserved=/ { flush(); match($0, /committed=[0-9]+KB/); name = "Total"; kb = substr($0, RSTART + 10, RLENGTH - 12) + 0; allocs = 0; next }
    /^-  *[^(]+ \(reserved=[0-9]+KB, committed=[0-9]+KB/ {
      flush()
      n = $0; sub(/^-  */, "", n); sub(/ \(reserved=.*/, "", n); name = n
      match($0, /committed=[0-9]+KB/); kb = substr($0, RSTART + 10, RLENGTH - 12) + 0; allocs = 0; next
    }
    # "(malloc=493796KB #1963)"; JDK 25 adds the tag: "(malloc=493796KB tag=Other #1963) (at peak)"
    name != "" && /\(malloc=[0-9]+KB( tag=[^ ]+)? #[0-9]+/ && allocs == 0 { match($0, /#[0-9]+/); allocs = substr($0, RSTART + 1, RLENGTH - 1) + 0 }
    END { flush() }' "$1"
}

parse_javacore() {
  # emit "path<TAB>bytes<TAB>allocations" for every nMEMUSER line, path = ancestors joined by " > "
  awk '
    /^[0-9]MEMUSER/ && /bytes/ {
      lvl = substr($0, 1, 1) + 0
      line = $0
      sub(/^[0-9]MEMUSER[ |+-]*/, "", line)               # strip tag and tree drawing
      split(line, kv, ": ")
      name = kv[1]
      rest = kv[2]; gsub(/,/, "", rest)
      split(rest, nums, " ")
      bytes = nums[1]; allocs = nums[4]
      stack[lvl] = name
      path = stack[1]; for (i = 2; i <= lvl; i++) path = path " > " stack[i]
      printf "%s\t%s\t%s\n", path, bytes, allocs
    }' "$1"
}

join -t $'\t' -a1 -a2 -e 0 -o '0,1.2,1.3,2.2,2.3' \
  <(parse "$1" | sort -t $'\t' -k1,1) <(parse "$2" | sort -t $'\t' -k1,1) |
awk -F '\t' -v older="$(basename "$1")" -v newer="$(basename "$2")" -v mode="$MODE" '
  { d = $4 - $2; if (d != 0) { rows[++n] = $0; delta[n] = d } }
  END {
    printf "%s delta  %s  →  %s\n\n", (mode == "NMT" ? "NMT (committed)" : "NATIVEMEMINFO"), older, newer
    printf "%14s %12s  %s\n", "Δ bytes", "Δ allocs", "category"
    # simple selection sort by |delta| descending (n is small)
    for (i = 1; i <= n; i++) idx[i] = i
    for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++) {
      a = delta[idx[i]]; b = delta[idx[j]]
      if ((b < 0 ? -b : b) > (a < 0 ? -a : a)) { t = idx[i]; idx[i] = idx[j]; idx[j] = t }
    }
    shown = 0
    for (k = 1; k <= n && shown < 15; k++) {
      split(rows[idx[k]], f, "\t")
      printf "%+14.1f MiB %+10d  %s\n", (f[4] - f[2]) / 1048576, f[5] - f[3], f[1]
      shown++
    }
    # verdict: the deepest category carrying the largest growth
    best = 0; bestd = 0; bestdepth = 0
    for (i = 1; i <= n; i++) {
      split(rows[i], f, "\t"); depth = gsub(/ > /, " > ", f[1])
      if (mode == "NMT" && (f[1] == "Total" || f[1] == "Java Heap")) continue   # heap growth is not a native leak
      if (delta[i] > bestd * 0.98 && (delta[i] > bestd || depth > bestdepth)) { best = i; bestd = delta[i]; bestdepth = depth }
    }
    print ""
    if (bestd >= 32 * 1048576) {
      split(rows[best], f, "\t")
      printf "VERDICT: native growth of %.1f MiB in \"%s\" — investigate off-heap allocations there\n", bestd / 1048576, f[1]
    } else
      printf "VERDICT: no significant native growth (largest Δ %.1f MiB)\n", bestd / 1048576
  }'
