# Builds docs/LeakLab-Field-Guide.pdf from docs/guide/src/*.html + guide.css
# Requires Chrome or Edge (headless print-to-PDF, Chromium 131+ for page-margin boxes).
#   powershell -ExecutionPolicy Bypass -File docs/guide/build.ps1
#
# The PDF is not committed (.gitignore): it is published as an asset of every GitHub release, always under the same
# file name, because the README links to releases/latest/download/LeakLab-Field-Guide.pdf. After building and pushing,
# from the repository root (gh creates the tag on the remote's default branch if it does not exist yet):
#   gh release create v1.0 docs/LeakLab-Field-Guide.pdf --title "LeakLab 1.0" --notes "Field Guide, Edition 1.0"
#   gh release upload v1.0 docs/LeakLab-Field-Guide.pdf --clobber      # replace the PDF on an existing release
#
# Two passes, for two-sided printing: every chapter (part page or appendix) must open on a right-hand, odd page, and
# the back cover must be the outside of the last sheet: a left-hand, even page.
# Chromium treats CSS `break-before: right` as a plain page break, so pass 1 prints the guide, reads the page each
# chapter starts on from the PDF's named destinations (one per element id), and inserts an empty page before every
# chapter that would land on an even page. Pass 2 prints the final PDF and verifies it.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildDir = Join-Path $here 'build'
New-Item -ItemType Directory -Force $buildDir | Out-Null

$css = [IO.File]::ReadAllText((Join-Path $here 'guide.css'))
$body = (Get-ChildItem (Join-Path $here 'src') -Filter '*.html' | Sort-Object Name |
        ForEach-Object { [IO.File]::ReadAllText($_.FullName) }) -join "`n"
$chapterPattern = '<section class="(?:part|chapter)[^"]*" id="([^"]+)"'
$chapters = [regex]::Matches($body, $chapterPattern) | ForEach-Object { $_.Groups[1].Value }

$browser = @(
  "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
  "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
  "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $browser) { throw 'Chrome or Edge not found' }

$pdf = Join-Path (Split-Path -Parent $here) 'LeakLab-Field-Guide.pdf'
$htmlPath = Join-Path $buildDir 'field-guide.html'
$profileDir = Join-Path $buildDir 'chrome-profile'

function Write-Pdf([string]$bodyHtml, [string]$target) {
  $html = @"
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>LeakLab Field Guide &#8211; JVM memory, containers and forensics</title>
<meta name="author" content="Konstantinos Mavridis">
<style>$css</style>
</head>
<body>
$bodyHtml
</body>
</html>
"@
  [IO.File]::WriteAllText($htmlPath, $html, [Text.UTF8Encoding]::new($false))
  for ($i = 0; (Test-Path $target) -and $i -lt 10; $i++) {   # a viewer may hold the old file for a moment
    try { Remove-Item $target -Force } catch { Start-Sleep -Milliseconds 500 }
  }
  if (Test-Path $target) { throw "$target is locked (open in a viewer?)" }
  $chromeArgs = @('--headless=new', '--disable-gpu', '--no-first-run', "--user-data-dir=`"$profileDir`"",
                  '--no-pdf-header-footer', '--generate-pdf-document-outline', '--run-all-compositor-stages-before-draw',
                  '--virtual-time-budget=5000', "--print-to-pdf=`"$target`"", ([Uri]$htmlPath).AbsoluteUri)
  Start-Process -FilePath $browser -ArgumentList $chromeArgs -Wait -WindowStyle Hidden `
    -RedirectStandardError (Join-Path $buildDir 'chrome.log')
  if (-not (Test-Path $target)) { throw "PDF was not produced" }
}

# Page number (1-based) of every named destination. Chromium writes the page tree and the destinations uncompressed:
#   /Kids [2 0 R 52 0 R ...]      /scenario-a [282 0 R /XYZ 0 841.92 0]
function Get-DestinationPages([string]$path) {
  $raw = [Text.Encoding]::GetEncoding(28591).GetString([IO.File]::ReadAllBytes($path))
  $offsets = @{}
  foreach ($m in [regex]::Matches($raw, '(?<![0-9])(\d+) 0 obj')) { $offsets[$m.Groups[1].Value] = $m.Index }
  function Get-Dict([string]$n) {
    $start = $offsets[$n]; $end = $raw.IndexOf('endobj', $start)
    $text = $raw.Substring($start, $end - $start)
    $s = $text.IndexOf('stream'); if ($s -ge 0) { $text.Substring(0, $s) } else { $text }
  }
  $order = [Collections.Generic.List[string]]::new()
  function Walk([string]$n) {
    $d = Get-Dict $n
    if ($d -match '/Type\s*/Pages\b') {
      $kids = [regex]::Match($d, '/Kids\s*\[([^\]]*)\]').Groups[1].Value
      foreach ($k in [regex]::Matches($kids, '(\d+)\s+0\s+R')) { Walk $k.Groups[1].Value }
    } else { $order.Add($n) }
  }
  $root = [regex]::Match($raw, '/Root\s+(\d+)\s+0\s+R').Groups[1].Value
  Walk ([regex]::Match((Get-Dict $root), '/Pages\s+(\d+)\s+0\s+R').Groups[1].Value)
  $pageOf = @{}; for ($i = 0; $i -lt $order.Count; $i++) { $pageOf[$order[$i]] = $i + 1 }
  $pages = @{}
  foreach ($m in [regex]::Matches($raw, '/([A-Za-z0-9_.-]+)\s*\[(\d+)\s+0\s+R\s*/XYZ')) {
    $pages[$m.Groups[1].Value] = $pageOf[$m.Groups[2].Value]
  }
  $pages
}

# ---- pass 1: where do the chapters land?
Write-Pdf $body $pdf
$pages = Get-DestinationPages $pdf
$blanks = @(); $offset = 0
foreach ($id in $chapters) {
  if (-not $pages.ContainsKey($id)) { Write-Warning "no destination for #$id"; continue }
  if ((($pages[$id] + $offset) % 2) -eq 0) { $blanks += $id; $offset++ }
}
if ($body -match 'id="back-cover"' -and -not $pages.ContainsKey('back-cover')) { throw 'no PDF destination for #back-cover (is it linked?)' }
if ($pages.ContainsKey('back-cover') -and (($pages['back-cover'] + $offset) % 2) -eq 1) { $blanks += 'back-cover'; $offset++ }

# ---- pass 2: an empty page before every chapter that would open on the left
if ($blanks.Count -gt 0) {
  foreach ($id in $blanks) {
    $body = [regex]::Replace($body, "(<section class=""[^""]*"" id=""$([regex]::Escape($id))"")",
                             '<div class="blank-page">This page is intentionally left blank.</div>$1')
  }
  Write-Pdf $body $pdf
  $pages = Get-DestinationPages $pdf
}
$left = $chapters | Where-Object { $pages.ContainsKey($_) -and ($pages[$_] % 2) -eq 0 }
if ($left) { Write-Warning ("chapters still on a left-hand page: " + ($left -join ', ')) }
if ($pages.ContainsKey('back-cover') -and ($pages['back-cover'] % 2) -eq 1) { Write-Warning "the back cover is on a right-hand page" }

"{0}  ({1:N0} KB) - {2} chapters on right-hand pages - {3} blank pages inserted ({4})" -f `
  $pdf, ((Get-Item $pdf).Length / 1KB), ($chapters.Count - @($left).Count), $blanks.Count, ($blanks -join ', ')
