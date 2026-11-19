#!/usr/bin/env bash
# mat.sh — thin wrappers around Eclipse MAT batch mode (ParseHeapDump.sh).
#
#   mat.sh query   <dump> <outfile> <mat command...>     single query → plain text   (histogram, dominator_tree, oql "...", path2gc 0x.., ...)
#   mat.sh suspects <dump> <outdir>                       Leak Suspects report → HTML dir + extracted text
#   mat.sh compare <target> <baseline> <outfile>          histogram delta target − baseline → CSV
#
# The first call on a dump parses it and writes *.index files next to it (minutes for big dumps);
# every later call reuses those indexes and takes seconds.
set -euo pipefail
MAT="${MAT_HOME:-/opt/mat}/ParseHeapDump.sh"
LIMIT="${MAT_LIMIT:-15}"

base() { local d=${1%.gz}; echo "${d%.*}"; }   # MAT names reports after the dump minus .hprof.gz / .hprof / .phd

unzip_to() { rm -rf "$2"; mkdir -p "$2"; unzip -q -o "$1" -d "$2"; }

html_text() {  # crude but dependency-free HTML → text
  sed -e 's/<br[^>]*>/\n/g' -e 's/<\/\(p\|tr\|h[1-6]\|li\|div\)>/\n/g' -e 's/<[^>]*>/ /g' \
      -e 's/&nbsp;/ /g' -e 's/&quot;/"/g' -e "s/&#39;/'/g" -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&raquo;/»/g' -e 's/&amp;/\&/g' "$1" |
    tr -s ' \t' ' ' | sed 's/^ //' | grep -v '^\s*$'
}

cmd=${1:?command}; shift
case "$cmd" in
  query)
    dump=$1 out=$2; shift 2
    zip="$(base "$dump")_Query.zip"; rm -f "$zip"
    "$MAT" "$dump" -format=txt -limit="$LIMIT" "-command=$*" org.eclipse.mat.api:query > "$out.log" 2>&1 || true
    [ -f "$zip" ] || { echo "MAT query failed, see $out.log" >&2; tail -5 "$out.log" >&2; exit 1; }
    tmp=$(mktemp -d); unzip_to "$zip" "$tmp"
    { echo "# MAT> $*"; echo; find "$tmp" -name '*.txt' -exec cat {} \; ; } > "$out"
    rm -rf "$tmp" "$zip" "$out.log"
    ;;
  suspects)
    dump=$1 outdir=$2
    zip="$(base "$dump")_Leak_Suspects.zip"; rm -f "$zip"
    "$MAT" "$dump" org.eclipse.mat.api:suspects > "$outdir.log" 2>&1 || true
    [ -f "$zip" ] || { echo "MAT suspects failed, see $outdir.log" >&2; exit 1; }
    unzip_to "$zip" "$outdir"; rm -f "$zip" "$outdir.log"
    for p in "$outdir"/pages/*.html; do
      if grep -q 'Problem Suspect' "$p" && grep -q 'Description' "$p"; then
        html_text "$p" | sed -n '/^Description/,$p' | sed '/^Table Of Contents/,$d'; echo
      fi
    done > "$outdir/suspects.txt"
    ;;
  compare)
    target=$1 baseline=$2 out=$3
    zip="$(base "$target")_Comparison.zip"; rm -f "$zip"
    "$MAT" "$target" "-snapshot2=$baseline" -format=txt org.eclipse.mat.api:compare > "$out.log" 2>&1 || true
    [ -f "$zip" ] || { echo "MAT compare failed, see $out.log" >&2; exit 1; }
    tmp=$(mktemp -d); unzip_to "$zip" "$tmp"
    cp "$(find "$tmp" -name 'Histogram_comparison*.csv' | head -1)" "$out"
    rm -rf "$tmp" "$zip" "$out.log"
    ;;
  *) echo "unknown command $cmd" >&2; exit 2 ;;
esac
