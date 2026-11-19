#!/usr/bin/env bash
# analyze.sh [dump-dir]
#
# The LeakLab analysis stage: runs the full investigation on a directory of OpenJ9/HotSpot artefacts and
# writes a numbered, explained report. Each step answers one question; together they go from
# "is it a leak?" to "this static field in this class holds it".
#
#   1  inventory              what evidence do we have?
#   2  GC trend               is the live set growing?                          (verbose GC log)
#   3  leak suspects          what does MAT think is wrong?                     (MAT suspects report)
#   4  histogram              what is the heap made of?                         (MAT histogram)
#   5  histogram delta        what GREW between two dumps?                      (MAT compare)
#   6  dominator tree         which single objects keep the most memory alive?  (MAT dominator_tree)
#   7  immediate dominators   who owns the big byte[]s?                          (MAT immediate_dominators)
#   8  suspect instances      what do the leaking objects look like?            (MAT OQL)
#   9  path to GC roots       why can't the GC free them?                        (MAT path2gc)
#  10  native delta           is anything growing outside the heap?             (javacore NATIVEMEMINFO · HotSpot NMT)
#
# Environment:  TARGET / BASELINE  heap dump file names (default: newest two; BASELINE=- for none)
#               REPORTS            output root (default /reports)
#               SUSPECT_CLASS      skip auto-detection                            MAT_LIMIT (rows, default 15)
#               MIN_GROWTH         objects a class must gain between the dumps to count as the suspect (default 100)
set -euo pipefail
shopt -s nullglob

SRC=${1:-/dumps}
# Report name from the path under /dumps: /dumps/semeru-21/sized → semeru-21-sized (any other path: its last folder)
rel=${SRC#/dumps/}; [ "$rel" = "$SRC" ] && rel=$(basename "$SRC")
NAME=${SCENARIO:-${rel//\//-}}
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT=${REPORTS:-/reports}/$NAME-$STAMP
WORK=/work/$NAME
mkdir -p "$OUT" "$WORK"

C='\033[1;36m'; G='\033[32m'; Y='\033[33m'; N='\033[0m'
step() { printf "\n${C}▶ %s${N}\n" "$*"; }
note() { printf "  %s\n" "$*"; }
newest() { [ $# -gt 0 ] || return 0; ls -1t -- "$@" 2>/dev/null || true; }   # no args (nothing matched): list nothing, not $PWD

# ---------------------------------------------------------------------------------------------- 1
step "1/10 Inventory of $SRC"
mapfile -t HEAPS < <(newest "$SRC"/*.phd "$SRC"/*.hprof "$SRC"/*.hprof.gz)
mapfile -t CORES < <(newest "$SRC"/javacore*.txt "$SRC"/vmreport*.txt)   # OpenJ9 javacores · HotSpot VM reports (NMT)
mapfile -t GCLOGS < <(newest "$SRC"/verbosegc*.log "$SRC"/gc*.log)
{
  echo "Evidence in $SRC  (newest first)"
  echo
  echo "Heap dumps (${#HEAPS[@]}):";  for f in "${HEAPS[@]}";  do ls -l --time-style=+%FT%T "$f" | awk '{printf "  %12s  %s  %s\n", $5, $6, $7}'; done
  echo "Javacores (${#CORES[@]}):";   for f in "${CORES[@]}";  do ls -l --time-style=+%FT%T "$f" | awk '{printf "  %12s  %s  %s\n", $5, $6, $7}'; done
  echo "GC logs (${#GCLOGS[@]}):";    for f in "${GCLOGS[@]}"; do ls -l --time-style=+%FT%T "$f" | awk '{printf "  %12s  %s  %s\n", $5, $6, $7}'; done
} | tee "$OUT/01-inventory.txt"

TARGET=${TARGET:+$SRC/$TARGET}; TARGET=${TARGET:-${HEAPS[0]:-}}
if [ "${BASELINE:-}" = "-" ]; then BASELINE=""      # explicitly: no baseline (e.g. the only dump of this JVM)
else BASELINE=${BASELINE:+$SRC/$BASELINE}; BASELINE=${BASELINE:-${HEAPS[1]:-}}; fi
[ -n "$TARGET" ] && note "target dump:   $(basename "$TARGET")"
[ -n "$BASELINE" ] && note "baseline dump: $(basename "$BASELINE")"

# ---------------------------------------------------------------------------------------------- 2
step "2/10 GC trend — is the live set growing?"
# Use the log of the JVM that wrote the target dump: the newest log that STARTED before the dump. Both images put the
# start time in the name (verbosegc.20261017.141731… · gc.2026-10-17_15-26-43…); without it, fall back to the newest.
GCLOG=${GCLOGS[0]:-}
if [ -n "$GCLOG" ] && [ -n "${TARGET:-}" ]; then
  dumped=$(date -u -d "@$(stat -c %Y "$TARGET")" +%Y%m%d%H%M%S)
  for f in "${GCLOGS[@]}"; do
    started=$(basename "$f" | tr -cd '0-9' | cut -c1-14)
    if [ ${#started} -eq 14 ] && [ "$started" -le "$dumped" ]; then GCLOG=$f; break; fi
  done
fi
if [ -n "$GCLOG" ]; then
  note "GC log: $(basename "$GCLOG")"
  gc-trend.sh "$GCLOG" | tee "$OUT/02-gc-trend.txt" | tail -4
else note "no verbose GC log found"; fi

[ -n "$TARGET" ] || { note "no heap dump — stopping after the GC trend"; exit 0; }
T="$WORK/$(basename "$TARGET")"; cp -f "$TARGET" "$T"
if [ -n "$BASELINE" ]; then B="$WORK/$(basename "$BASELINE")"; cp -f "$BASELINE" "$B"; fi

# ---------------------------------------------------------------------------------------------- 3
step "3/10 Leak suspects (parses the dump on first use — this is the slow step)"
mat.sh suspects "$T" "$OUT/03-leak-suspects"
head -c 900 "$OUT/03-leak-suspects/suspects.txt"; echo

# ---------------------------------------------------------------------------------------------- 4
step "4/10 Histogram — what is the heap made of?"
mat.sh query "$T" "$OUT/04-histogram.txt" histogram
sed -n '3,10p' "$OUT/04-histogram.txt"

# ---------------------------------------------------------------------------------------------- 5
SUSPECT=${SUSPECT_CLASS:-}
if [ -n "${B:-}" ]; then
  step "5/10 Histogram delta — what grew between $(basename "$BASELINE") and $(basename "$TARGET")?"
  mat.sh compare "$T" "$B" "$OUT/05-histogram-delta.csv"
  awk -F, 'NR==1 {printf "%-55s %12s %16s\n", "Class (target − baseline)", "Δ objects", "Δ shallow bytes"; next}
           NR<=16 && $1!="" {printf "%-55s %+12d %+16d\n", $1, $2, $3}' "$OUT/05-histogram-delta.csv" | tee "$OUT/05-histogram-delta.txt"
  gap=$(( $(stat -c %Y "$TARGET") - $(stat -c %Y "$BASELINE") ))
  [ "$gap" -ge 60 ] || printf "  ${Y}the dumps are only %ss apart: the delta is mostly noise. Pass the leak pair explicitly (TARGET=… BASELINE=…).${N}\n" "$gap"
  # first application class that grew by at least MIN_GROWTH objects (a few objects of churn is noise, not a leak);
  # skip primitive/Object arrays and JDK packages. No such class → step 7's owner of the byte[]s is used instead.
  [ -n "$SUSPECT" ] || SUSPECT=$(awk -F, -v min="${MIN_GROWTH:-100}" 'NR>1 && $2>=min && $1 !~ /^(byte|char|short|int|long|float|double|boolean)\[\]/ \
                                           && $1 !~ /^java\.lang\.Object\[\]/ && $1 !~ /^(java|javax|jdk|sun|com\.sun)\./ {print $1; exit}' \
                                    "$OUT/05-histogram-delta.csv")
  [ -n "$SUSPECT" ] && [ -z "${SUSPECT_CLASS:-}" ] && GREW=1
else
  step "5/10 Histogram delta — skipped (needs two dumps of the same JVM)"
fi

# ---------------------------------------------------------------------------------------------- 6
step "6/10 Dominator tree — which objects keep the most memory alive?"
mat.sh query "$T" "$OUT/06-dominator-tree.txt" dominator_tree
sed -n '3,8p' "$OUT/06-dominator-tree.txt"

# ---------------------------------------------------------------------------------------------- 7
step "7/10 Immediate dominators of byte[] — who owns the bytes?"
mat.sh query "$T" "$OUT/07-immediate-dominators.txt" 'immediate_dominators byte[]'
sed -n '3,7p' "$OUT/07-immediate-dominators.txt"
[ -n "$SUSPECT" ] || SUSPECT=$(awk -F'|' 'NR>4 && $1 !~ /ROOT|^-/ {gsub(/ +$/, "", $1); print $1; exit}' "$OUT/07-immediate-dominators.txt")

# ---------------------------------------------------------------------------------------------- 8
step "8/10 Suspect instances (OQL) — class: ${SUSPECT:-?}"
if [ -n "$SUSPECT" ]; then
  mat.sh query "$T" "$OUT/08-oql-suspect-instances.txt" "oql \"SELECT s, s.@usedHeapSize, s.@retainedHeapSize FROM $SUSPECT s\""
  sed -n '3,8p' "$OUT/08-oql-suspect-instances.txt"
  OBJ=$(grep -o 'id=0x[0-9a-f]*' "$OUT/08-oql-suspect-instances.txt" | head -1 | cut -d= -f2)
fi

# ---------------------------------------------------------------------------------------------- 9
step "9/10 Path to GC roots — why can't the GC free it?"
if [ -n "${OBJ:-}" ]; then
  mat.sh query "$T" "$OUT/09-path-to-gc-roots.txt" "path2gc $OBJ -excludes java.lang.ref.Reference:referent java.lang.ref.Finalizer:unfinalized"
  sed -n '3,12p' "$OUT/09-path-to-gc-roots.txt"
else note "no suspect instance to trace"; fi

# ---------------------------------------------------------------------------------------------- 10
step "10/10 Native memory — is anything growing outside the heap?"
# Direct buffers are native memory, but each one has a small DirectByteBuffer wrapper on the heap: count them.
mat.sh query "$T" "$OUT/10-direct-buffers.txt" 'histogram java.nio.DirectByteBuffer' || true
[ -f "$OUT/10-direct-buffers.txt" ] && grep -m1 '^java.nio.DirectByteBuffer' "$OUT/10-direct-buffers.txt" | sed 's/^/  heap wrappers: /' || true
same=()
if [ ${#CORES[@]} -ge 2 ]; then
  # only compare javacores written by the same JVM (same start time); newest JVM first
  start_of() { grep -m1 -E '^(1CISTARTTIME|Process start ticks:)' "$1" | cut -d: -f2- ; }   # OpenJ9 · HotSpot vmreport
  latest_start=$(start_of "${CORES[0]}")
  same=(); for f in "${CORES[@]}"; do [ "$(start_of "$f")" = "$latest_start" ] && same+=("$f"); done
  if [ ${#same[@]} -ge 2 ]; then
    nativemem-diff.sh "${same[-1]}" "${same[0]}" | tee "$OUT/10-native-delta.txt" | sed -n '1,8p;/^VERDICT/p'
  else note "only one native-memory report from the current JVM — take another (OpenJ9: kill -QUIT 1 · HotSpot: VM report / jcmd 1 VM.native_memory summary) a few minutes later"; fi
fi
if [ ${#same[@]} -lt 2 ] && [ ${#CORES[@]} -ge 1 ]; then
  # Single javacore: no delta, but the allocation categories (not the reservations) are still meaningful.
  if grep -q '^Native Memory Tracking:' "${CORES[0]}"; then
  {
    echo "NMT snapshot of $(basename "${CORES[0]}") — single report, committed memory per category."
    echo "Java Heap is the committed heap; Other holds direct buffers and Unsafe/FFM allocations."
    echo
    grep -E '^(Total: |-  )' "${CORES[0]}" | sed -e 's/^-  *//' -e 's/ (reserved=[0-9]*KB, committed=/  committed=/' -e 's/)$//' || true
  } > "$OUT/10-native-snapshot.txt"
  elif ! grep -q 'NATIVEMEMINFO\|MEMUSER' "${CORES[0]}"; then
    echo "$(basename "${CORES[0]}") has no native-memory section (HotSpot started without -XX:NativeMemoryTracking?)" > "$OUT/10-native-snapshot.txt"
  else
  {
    echo "NATIVEMEMINFO snapshot of $(basename "${CORES[0]}") — single javacore, absolute values."
    echo "Java Heap and JIT Code Cache are RESERVATIONS; Direct Byte Buffers and sun.misc.Unsafe are real allocations."
    echo
    grep -E 'MEMUSER.*(JRE:|Java Heap|JIT Code Cache|Threads:|Class Libraries|sun.misc.Unsafe|Direct Byte Buffers)' "${CORES[0]}" |
      sed -e 's/^[0-9]MEMUSER *//' -e 's/[|+]//g' -e 's/--//' -e 's/^ *//' || true
    { grep -A4 'sun.misc.Unsafe' "${CORES[0]}" | grep -m1 '^5MEMUSER.*Other:' | sed -e 's/^5MEMUSER[ |+-]*/sun.misc.Unsafe > /'; } || true
  } > "$OUT/10-native-snapshot.txt"
  fi
  sed -n '4,13p' "$OUT/10-native-snapshot.txt"
fi

# ---------------------------------------------------------------------------------------------- summary
first_line() { [ -f "$1" ] && sed -n "$2" "$1" || true; }
{
  echo "# LeakLab analysis — $NAME ($STAMP)"
  echo
  echo "| | |"; echo "|---|---|"
  echo "| Target dump | \`$(basename "$TARGET")\` |"
  [ -n "${B:-}" ] && echo "| Baseline dump | \`$(basename "$BASELINE")\` |"
  [ -n "${GCLOG:-}" ] && echo "| GC log | \`$(basename "$GCLOG")\` |"
  echo "| Suspect class | \`${SUSPECT:-n/a}\` |"
  echo
  echo "## Findings"
  echo
  # Heap context: MAT percentages are relative to the heap IN THE DUMP, not to -Xmx. A "42 %" suspect in a
  # mostly empty heap is not a leak — the problem may be outside the heap entirely (check step 10).
  top=$(sed -n '5p' "$OUT/06-dominator-tree.txt")   # first data row (after "# MAT>", blank, header, rule)
  ret=$(echo "$top" | awk -F'|' '{gsub(/[ ,]/, "", $3); print $3}'); pct=$(echo "$top" | awk -F'|' '{gsub(/[ %]/, "", $4); print $4}')
  maxheap=$(grep -m1 -o '^max heap (-Xmx): [0-9.]*' "$OUT/02-gc-trend.txt" 2>/dev/null | awk '{print $4}' || true)  # no GC log: no file
  if [ -n "$ret" ] && [ -n "$pct" ]; then
    # (no caveat when step 5 already showed an application class growing between the dumps: that IS the leak)
    awk -v r="$ret" -v p="$pct" -v m="${maxheap:-0}" -v grew="${GREW:-0}" 'BEGIN {
      h = r / (p / 100) / 1048576
      printf "**Heap context** — heap in the dump ≈ %.0f MiB", h
      if (m > 0) printf " of a %.0f MiB maximum (%.0f%%)", m, 100 * h / m
      print "."
      if (m > 0 && h < 0.3 * m && !grew) print "\n> The heap is mostly empty: the top suspect may be legitimate. If the process was still growing, look outside the heap (step 10)."
    }'
    echo
  fi
  [ -f "$OUT/02-gc-trend.txt" ] && { echo "**GC trend** — $(grep '^live set' "$OUT/02-gc-trend.txt")"; echo; echo "> $(grep '^VERDICT' "$OUT/02-gc-trend.txt")"; echo; }
  echo "**MAT leak suspect** — $(grep -m1 -o 'The class .*bytes\.' "$OUT/03-leak-suspects/suspects.txt" || grep -m1 -o 'One instance of .*bytes\.' "$OUT/03-leak-suspects/suspects.txt" || echo 'none reported')"
  echo
  [ -f "$OUT/05-histogram-delta.txt" ] && { echo '**What grew** (target − baseline):'; echo; echo '```'; sed -n '1,8p' "$OUT/05-histogram-delta.txt"; echo '```'; echo; }
  echo '**Biggest dominators:**'; echo; echo '```'; sed -n '3,7p' "$OUT/06-dominator-tree.txt"; echo '```'; echo
  [ -f "$OUT/09-path-to-gc-roots.txt" ] && { echo '**Why it stays alive** (path to GC roots of one instance):'; echo; echo '```'; sed -n '3,12p' "$OUT/09-path-to-gc-roots.txt"; echo '```'; echo; }
  [ -f "$OUT/10-direct-buffers.txt" ] && grep -q '^java.nio.DirectByteBuffer' "$OUT/10-direct-buffers.txt" &&
    { echo "**Direct buffer wrappers on the heap** — $(grep -m1 '^java.nio.DirectByteBuffer' "$OUT/10-direct-buffers.txt" | awk -F'|' '{gsub(/ /,"",$2); gsub(/ /,"",$3); print $2 " objects, " $3 " bytes of heap (the native memory they own is not in the heap dump)"}')"; echo; }
  [ -f "$OUT/10-native-snapshot.txt" ] && { echo '**Native memory snapshot** (single report; first line says how to read it):'; echo; echo '```'; sed -n '4,13p' "$OUT/10-native-snapshot.txt"; echo '```'; echo; }
  [ -f "$OUT/10-native-delta.txt" ] && { echo '**Native memory delta** (javacore NATIVEMEMINFO or HotSpot NMT):'; echo; echo '```'; sed -n '3,8p' "$OUT/10-native-delta.txt"; echo '```'; echo; echo "> $(grep '^VERDICT' "$OUT/10-native-delta.txt")"; echo; }
  echo "## Files"; echo
  for f in "$OUT"/*; do echo "- \`$(basename "$f")\`"; done
  echo; echo "Open \`03-leak-suspects/index.html\` in a browser for MAT's full interactive-style report."
} > "$OUT/SUMMARY.md"

step "Done → $OUT"
ls -1 "$OUT"
