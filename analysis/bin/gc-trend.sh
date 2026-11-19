#!/usr/bin/env bash
# gc-trend.sh <gc log> [more logs of the same JVM...]
#
# Prints the heap occupancy right AFTER every global (full) collection — the live set. A leak is a monotonically
# rising series; a healthy service is flat. Reads either format:
#   OpenJ9   verbose GC XML (-Xverbosegclog)           GUI equivalent: IBM GCMV → "Used heap (after global collection)"
#   HotSpot  unified logging (-Xlog:gc*:file=...)      GUI equivalent: GCeasy, or JDK Mission Control for JFR
set -euo pipefail
[ $# -ge 1 ] || { echo "usage: $0 <gc log>..." >&2; exit 2; }

# Both parsers emit the same records:  M <max heap bytes> | G <time> <used after> <heap size> <pause ms> | S <pause ms>
openj9() {
  awk '
    function hex2dec(h,   i, d, c) { d = 0; h = tolower(h); sub(/^0x/, "", h)
      for (i = 1; i <= length(h); i++) { c = index("0123456789abcdef", substr(h, i, 1)) - 1; d = d * 16 + c }
      return d }
    /<attribute name="maxHeapSize"/ && !maxheap {
      match($0, /value="[^"]*"/); maxheap = hex2dec(substr($0, RSTART + 7, RLENGTH - 8)); print "M", maxheap; next
    }
    /<gc-end / {
      type = ""; ts = ""; dur = ""
      if (match($0, /type="[^"]*"/))       type = substr($0, RSTART + 6, RLENGTH - 7)
      if (match($0, /timestamp="[^"]*"/))  ts   = substr($0, RSTART + 11, RLENGTH - 12)
      if (match($0, /durationms="[^"]*"/)) dur  = substr($0, RSTART + 12, RLENGTH - 13)
      want = 1; next
    }
    want && /<mem-info / {
      want = 0
      match($0, /free="[0-9]*"/);  free  = substr($0, RSTART + 6, RLENGTH - 7)
      match($0, /total="[0-9]*"/); total = substr($0, RSTART + 7, RLENGTH - 8)
      if (type == "scavenge") { print "S", dur; next }
      if (type == "global") print "G", ts, total - free, total, dur
    }' "$@"
}

hotspot() {
  # [2026-10-17T15:20:01.123+0000][12.345s][info][gc] GC(12) Pause Full (Allocation Failure) 590M->588M(614M) 120.345ms
  # Full GCs (Serial/Parallel/G1 "Pause Full") are the live-set samples; G1 "Pause Remark" ends a concurrent mark, so
  # its after-value is also a good live-set sample. Young pauses only count as scavenges.
  awk '
    function bytes(v,   n, u) { n = v + 0; u = substr(v, length(v)); return n * (u == "G" ? 1073741824 : u == "M" ? 1048576 : u == "K" ? 1024 : 1) }
    /Heap Max Capacity:/ && !maxheap { match($0, /Heap Max Capacity: [0-9]+[KMG]/); maxheap = bytes(substr($0, RSTART + 19, RLENGTH - 19)); print "M", maxheap; next }
    /\]\[gc *\] GC\([0-9]+\) Pause / && /->/ {
      ts = substr($0, 2, index($0, "]") - 2)
      match($0, /[0-9]+[KMG]->[0-9]+[KMG]\([0-9]+[KMG]\)/); m = substr($0, RSTART, RLENGTH)
      split(m, p, /->|\(|\)/)
      match($0, /[0-9.]+ms$/); dur = substr($0, RSTART, RLENGTH - 2)
      if ($0 ~ /Pause (Full|Remark)/) print "G", ts, bytes(p[2]), bytes(p[3]), dur
      else print "S", dur
    }' "$@"
}

if grep -qm1 '<gc-end \|<verbosegc' "$1"; then openj9 "$@"; else hotspot "$@"; fi |
awk '
  $1 == "M" { maxheap = $2; next }
  $1 == "S" { scav++; scavms += $2; next }
  $1 == "G" { n++; t[n] = $2; u[n] = $3; c[n] = $4; d[n] = $5 }
  END {
    if (n == 0) { printf "no global/full collections found (%d young collections) — the old generation never filled\n", scav; exit 0 }
    printf "%-29s %14s %14s %10s\n", "global/full GC end", "used after GC", "heap size", "pause ms"
    step = (n > 30) ? int(n / 30) : 1           # print at most ~30 rows, always first and last
    for (i = 1; i <= n; i++)
      if (i == 1 || i == n || i % step == 0)
        printf "%-29s %11.1f MiB %11.1f MiB %10.1f\n", t[i], u[i] / 1048576, c[i] / 1048576, d[i]
    rises = 0; full = 0; peak = 0
    for (i = 1; i <= n; i++) {
      if (i > 1 && u[i] > u[i - 1]) rises++
      if (c[i] > 0 && u[i] >= 0.95 * c[i]) full++
      if (u[i] > peak) peak = u[i]
    }
    q = int(n / 4); if (q < 1) q = 1
    for (i = 1; i <= q; i++) { fs += u[i]; ls += u[n - q + i] }
    fs /= q; ls /= q
    printf "\nglobal GCs: %d   scavenges: %d (%.0f ms total)\n", n, scav, scavms
    printf "live set: first %.1f MiB → last %.1f MiB (Δ %+.1f MiB), peak %.1f MiB; rose in %d of %d intervals\n",
           u[1] / 1048576, u[n] / 1048576, (u[n] - u[1]) / 1048576, peak / 1048576, rises, n - 1
    printf "first-quarter mean %.1f MiB → last-quarter mean %.1f MiB; %d global GCs freed < 5%% of the heap\n",
           fs / 1048576, ls / 1048576, full
    if (maxheap) printf "max heap (-Xmx): %.1f MiB; live set peaked at %.0f%% of it\n", maxheap / 1048576, 100 * peak / maxheap
    growth = ls - fs; ref = maxheap ? maxheap : c[n]
    if (full >= 10)
      printf "VERDICT: heap exhausted — %d global GCs recovered almost nothing (GC death spiral before OutOfMemoryError)\n", full
    else if (n >= 4 && ls > fs * 1.2 && rises >= 0.5 * (n - 1) && growth >= 0.05 * ref)
      print "VERDICT: rising floor — consistent with a heap leak"
    else if (n >= 4 && ls > fs * 1.2 && rises >= 0.5 * (n - 1))
      printf "VERDICT: slight rise (+%.1f MiB, %.0f%% of max heap) — not conclusive; collect a longer window\n", growth / 1048576, 100 * growth / ref
    else
      print "VERDICT: no sustained growth of the live set"
  }'
