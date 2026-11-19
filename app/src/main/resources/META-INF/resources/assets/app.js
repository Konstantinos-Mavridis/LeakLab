/* LeakLab control room — dependency-free (works air-gapped on OpenShift). */
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const C = {
    heap: '#38bdf8', heapFree: 'rgba(56,189,248,0.20)', nonheap: '#a78bfa', direct: '#fbbf24',
    native: '#f472b6', other: '#64748b', live: '#34d399', limit: '#f43f5e', xmx: '#e2e8f0',
    ws: 'rgba(226,232,240,0.75)', grid: 'rgba(148,163,184,0.09)', axis: '#6b778f',
    minor: '#38bdf8', major: '#f472b6',
  };
  const LAYERS = [
    { key: 'heapUsed', label: 'Heap used', color: C.heap },
    { key: 'heapFree', label: 'Heap committed, free', color: C.heapFree },
    { key: 'nonHeap', label: 'JVM non-heap', color: C.nonheap },
    { key: 'direct', label: 'Direct buffers', color: C.direct },
    { key: 'native', label: 'Native (FFM)', color: C.native },
    { key: 'other', label: 'Other native (RSS rest)', color: C.other },
  ];
  const WINDOW_S = 300;
  const MAJOR_RX = /global|old|marksweep|full|major/i;

  const state = {
    hist: [],
    last: null,
    prevCollectors: null,
    startTime: null,
    connected: false,
    everConnected: false,
    hover: null,
    touchedAt: 0,
    lastJournalSeq: 0,
    ui: { running: false, mode: 'HEAP', ratePerSecond: 40, payloadKb: 256, retentionPercent: 25, keepRunningOnOom: false },
  };

  // ---------------------------------------------------------------- formatting
  function fmtB(b, digits = 1) {
    if (b == null || b < 0 || !isFinite(b)) return '—';
    const u = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
    let i = 0;
    while (Math.abs(b) >= 1024 && i < u.length - 1) { b /= 1024; i++; }
    if (i <= 1) return Math.round(b) + ' ' + u[i];
    return (Math.abs(b) >= 100 ? b.toFixed(0) : b.toFixed(digits)) + ' ' + u[i];
  }
  const fmtRate = (bps) => (bps <= 0 ? '0 B/s' : fmtB(bps) + '/s');
  const pct = (x) => (x == null || x < 0 ? '—' : (x * 100).toFixed(x < 0.1 ? 1 : 0) + '%');
  function fmtDur(s) {
    if (!isFinite(s) || s < 0) return '∞';
    if (s < 90) return Math.round(s) + ' s';
    if (s < 5400) return (s / 60).toFixed(1) + ' min';
    return (s / 3600).toFixed(1) + ' h';
  }
  const clock = (t) => new Date(t).toLocaleTimeString([], { hour12: false });
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // ---------------------------------------------------------------- slider scales (log for rate & payload)
  const scales = {
    rate: { toVal: (v) => (v <= 0 ? 0 : Math.round(Math.pow(2000, v / 1000))), toPos: (r) => (r <= 0 ? 0 : Math.round(Math.log(r) / Math.log(2000) * 1000)) },
    payload: { toVal: (v) => Math.max(1, Math.round(Math.pow(4096, v / 1000))), toPos: (p) => Math.round(Math.log(Math.max(1, p)) / Math.log(4096) * 1000) },
  };

  // ---------------------------------------------------------------- toast & http
  let toastTimer;
  function toast(msg, err = false) {
    const t = $('toast');
    t.textContent = msg;
    t.className = 'toast show' + (err ? ' err' : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (t.className = 'toast'), 4200);
  }
  async function api(method, path, body) {
    const res = await fetch(path, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    const data = text ? JSON.parse(text) : null;
    if (!res.ok) throw new Error((data && data.error) || res.status + ' ' + res.statusText);
    return data;
  }

  // ---------------------------------------------------------------- canvas helpers
  function prep(cv) {
    const dpr = window.devicePixelRatio || 1;
    const r = cv.getBoundingClientRect();
    const w = Math.max(10, r.width), h = Math.max(10, r.height);
    if (cv.width !== Math.round(w * dpr) || cv.height !== Math.round(h * dpr)) {
      cv.width = Math.round(w * dpr);
      cv.height = Math.round(h * dpr);
    }
    const ctx = cv.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    return { ctx, w, h };
  }
  function niceMax(v) {
    if (v <= 0) return 1;
    const mib = 1048576;
    const steps = [16, 32, 64, 128, 256, 512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288, 16384].map((x) => x * mib);
    return steps.find((s) => s >= v) || v;
  }
  let hatch;
  function hatchPattern(ctx) {
    if (hatch) return hatch;
    const p = document.createElement('canvas');
    p.width = p.height = 10;
    const c = p.getContext('2d');
    c.strokeStyle = 'rgba(244,63,94,0.28)';
    c.lineWidth = 1.5;
    c.beginPath(); c.moveTo(0, 10); c.lineTo(10, 0); c.stroke();
    hatch = ctx.createPattern(p, 'repeat');
    return hatch;
  }

  // ---------------------------------------------------------------- data model
  function point(s) {
    const m = s.memory;
    const heapUsed = m.heap.used;
    const heapFree = Math.max(0, m.heap.committed - m.heap.used);
    const nonHeap = m.nonHeap.used; // OpenJ9 "committed" includes the reserved-but-untouched JIT code cache
    const direct = Math.max(0, m.offHeap.directUsed);
    const nat = Math.max(0, m.offHeap.nativeBytes);
    const rss = m.process.rss;
    const accounted = heapUsed + heapFree + nonHeap + direct + nat;
    const other = rss > 0 ? Math.max(0, rss - accounted) : 0;

    let minorMs = 0, majorMs = 0;
    if (state.prevCollectors) {
      for (const c of m.collectors) {
        const prev = state.prevCollectors.find((p) => p.name === c.name);
        const d = prev ? Math.max(0, c.timeMs - prev.timeMs) : 0;
        if (MAJOR_RX.test(c.name)) majorMs += d; else minorMs += d;
      }
    }
    state.prevCollectors = m.collectors;

    return {
      t: s.timestamp, heapUsed, heapFree, nonHeap, direct, native: nat, other, rss,
      ws: m.container.workingSet, limit: m.container.limit, xmx: m.heap.max,
      live: m.heap.liveAfterGc, nativeFootprint: m.verdict.nativeFootprint, minorMs, majorMs,
      // Raw footprint for slopes: the verdict's value holds its peak for a minute (no flicker), so it only rises.
      nativeRaw: rss > 0 ? Math.max(m.nonHeap.used + m.offHeap.directUsed + m.offHeap.nativeBytes, rss - m.heap.committed) : null,
    };
  }

  function slope(points, key, seconds = 90) {
    const end = points.length ? points[points.length - 1].t : 0;
    const xs = [], ys = [];
    for (const p of points) {
      if (p.t >= end - seconds * 1000 && p[key] != null && p[key] >= 0) { xs.push(p.t / 1000); ys.push(p[key]); }
    }
    if (xs.length < 8) return null;
    const n = xs.length, mx = xs.reduce((a, b) => a + b) / n, my = ys.reduce((a, b) => a + b) / n;
    let num = 0, den = 0;
    for (let i = 0; i < n; i++) { num += (xs[i] - mx) * (ys[i] - my); den += (xs[i] - mx) ** 2; }
    return den ? num / den : null; // bytes per second
  }

  // ---------------------------------------------------------------- render: header, verdict, signal
  function renderHeader(s) {
    $('chip-scenario').textContent = 'scenario · ' + s.scenario;
    $('chip-runtime').textContent = `${s.runtime.runtime.replace(' Runtime Environment', '')} ${s.runtime.javaVersion} (${s.runtime.vendor}) · ${s.runtime.flavor} · ${s.runtime.gcPolicy}`;
    $('chip-runtime').title = s.runtime.vm + ' ' + s.runtime.vmVersion;
    // The native leak allocates through FFM on Java 25, through sun.misc.Unsafe on Java 17 and 21 (FFM is not final there)
    const via = parseInt(s.runtime.javaVersion, 10) >= 22 ? 'FFM' : 'Unsafe';
    const nativeLayer = LAYERS.find((x) => x.key === 'native');
    if (nativeLayer.label !== `Native (${via})`) {
      nativeLayer.label = `Native (${via})`;
      document.querySelector('[data-mode="NATIVE"] small').textContent = via === 'FFM' ? 'FFM arena' : 'Unsafe malloc';
      renderLegend();
    }
    const c = s.memory.container;
    $('chip-cgroup').textContent = c.cgroup === 'none' ? 'no cgroup (bare host)' : `cgroup ${c.cgroup} · limit ${c.limit > 0 ? fmtB(c.limit) : 'unlimited'} · ${s.runtime.processors} CPU`;
    $('chip-cgroup').title = 'host ' + s.runtime.hostname + ' · pid ' + s.runtime.pid;
  }

  function renderVerdict(s) {
    const v = s.memory.verdict;
    const el = $('verdict');
    el.dataset.level = v.level;
    $('verdict-level').textContent = v.level.replace(/_/g, ' ').toLowerCase();
    $('verdict-headline').textContent = v.headline;
    $('verdict-detail').textContent = v.detail;
    $('vm-xmx').textContent = fmtB(v.heapCeiling);
    $('vm-native').textContent = fmtB(v.nativeFootprint);
    $('vm-direct').textContent = fmtB(v.directAllowance);
    $('vm-peak').textContent = fmtB(v.projectedPeak);
    $('vm-limit').textContent = v.containerLimit > 0 ? fmtB(v.containerLimit) : 'none';
  }

  function renderSignal() {
    const h = state.hist;
    const liveS = slope(h, 'live');
    const natS = slope(h, 'nativeRaw');
    const wsS = slope(h, 'ws');
    const p = h[h.length - 1];
    const mib = 1048576;
    // Measured on an idle container: HotSpot's footprint grows ~23 MiB/min for the first 70–90 s (JIT, class loading),
    // OpenJ9's drifts ~3 MiB/min. So no verdict in the first 2 minutes of JVM uptime, and off-heap needs > 8 MiB/min.
    const warming = p && state.startTime > 0 && p.t - state.startTime < 120000;
    let html;
    if (warming || h.length < 30) {
      html = warming
        ? `JVM warming up…<small>JIT and class loading grow the footprint in the first 2 minutes; signals in ≈ ${fmtDur((120000 - (p.t - state.startTime)) / 1000)}.</small>`
        : 'Collecting baseline…<small>Signals need ~30 s of history.</small>';
    } else if (liveS != null && liveS * 60 > 2 * mib) {
      html = `Heap leak signature<small>Live set after GC rising ${fmtB(liveS * 60)}/min — objects survive every collection. Take two heap dumps and diff them.</small>`;
    } else if (natS != null && natS * 60 > 8 * mib) {
      html = `Off-heap leak signature<small>Non-heap footprint rising ${fmtB(natS * 60)}/min while the heap floor is flat. A heap dump will look clean — use ${state.flavor === 'OpenJ9' ? 'javacore NATIVEMEMINFO' : 'the VM report (NMT)'}.</small>`;
    } else {
      html = 'Stable<small>Live set and native footprint are flat: churn is being collected.</small>';
    }
    if (!warming && h.length >= 30 && p && p.limit > 0 && wsS != null && wsS > 64 * 1024) {
      html += `<small>Container hits its limit in ≈ <b>${fmtDur((p.limit - p.ws) / wsS)}</b> at this rate.</small>`;
    }
    $('signal-text').innerHTML = html;
  }

  // ---------------------------------------------------------------- KPIs
  function tagClass(el, cls, text) {
    el.className = 'tag' + (cls ? ' ' + cls : '');
    el.textContent = text;
  }
  function spark(svg, values, color) {
    const vals = values.filter((v) => v != null && v >= 0);
    if (vals.length < 2) { svg.innerHTML = ''; return; }
    const min = Math.min(...vals), max = Math.max(...vals), span = max - min || 1;
    const step = 200 / (vals.length - 1);
    const pts = vals.map((v, i) => `${(i * step).toFixed(1)},${(33 - ((v - min) / span) * 30).toFixed(1)}`);
    svg.innerHTML = `<defs><linearGradient id="g${svg.id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${color}" stop-opacity=".35"/><stop offset="1" stop-color="${color}" stop-opacity="0"/></linearGradient></defs>
      <path d="M0,36 L${pts.join(' L')} L200,36 Z" fill="url(#g${svg.id})"/>
      <polyline points="${pts.join(' ')}" fill="none" stroke="${color}" stroke-width="1.6" vector-effect="non-scaling-stroke" stroke-linejoin="round"/>`;
  }

  function renderKpis(s) {
    const m = s.memory, c = m.container, g = s.guardian;
    // working set
    if (c.limit > 0) {
      const r = c.workingSet / c.limit;
      $('kpi-ws-value').innerHTML = `${fmtB(c.workingSet)}<small>${pct(r)}</small>`;
      $('kpi-ws-bar').style.width = Math.min(100, r * 100) + '%';
      $('kpi-ws-bar').style.setProperty('--full', (100 / Math.max(r, 0.01)) + '%');
      $('kpi-ws-guard').style.left = g.threshold * 100 + '%';
      $('kpi-ws-guard').title = 'guardian threshold';
      $('kpi-ws-foot').textContent = `of ${fmtB(c.limit)} cgroup limit · peak ${fmtB(c.peak)}`;
      tagClass($('kpi-ws-tag'), r > 0.9 ? 'crit' : r > 0.75 ? 'warn' : 'ok', r > 0.9 ? 'kill zone' : r > 0.75 ? 'pressure' : 'nominal');
    } else {
      $('kpi-ws-value').innerHTML = c.workingSet > 0 ? fmtB(c.workingSet) : '—';
      $('kpi-ws-bar').style.width = '0';
      $('kpi-ws-foot').textContent = c.cgroup === 'none' ? 'not running in a container' : 'no memory limit set';
      tagClass($('kpi-ws-tag'), 'warn', 'unbounded');
    }
    // heap
    const hr = m.heap.used / m.heap.max;
    $('kpi-heap-value').innerHTML = `${fmtB(m.heap.used)}<small>${pct(hr)}</small>`;
    $('kpi-heap-bar').style.width = Math.min(100, hr * 100) + '%';
    $('kpi-heap-foot').textContent = `of ${fmtB(m.heap.max)} -Xmx · committed ${fmtB(m.heap.committed)}`;
    tagClass($('kpi-heap-tag'), hr > 0.9 ? 'crit' : hr > 0.7 ? 'warn' : 'ok', s.runtime.flavor);
    // live set
    const ls = slope(state.hist, 'live');
    $('kpi-live-value').innerHTML = fmtB(m.heap.liveAfterGc) + (ls != null ? `<small>${ls >= 0 ? '+' : '−'}${fmtB(Math.abs(ls * 60))}/min</small>` : '');
    spark($('kpi-live-spark'), state.hist.slice(-120).map((p) => p.live), C.live);
    tagClass($('kpi-live-tag'), ls != null && ls * 60 > 2097152 ? 'crit' : 'ok', ls != null && ls * 60 > 2097152 ? 'rising' : 'flat');
    // retained
    const L = m.leak;
    const total = L.sessionBytes + L.directBytes + L.nativeBytes;
    $('kpi-ret-value').innerHTML = fmtB(total) + `<small>${(L.sessions + L.directBuffers + L.nativeBlocks).toLocaleString()} objs</small>`;
    const split = total || 1;
    $('kpi-ret-split').innerHTML = [[L.sessionBytes, C.heap], [L.directBytes, C.direct], [L.nativeBytes, C.native]]
      .map(([b, col]) => `<span style="width:${(b / split) * 100}%;background:${col}"></span>`).join('');
    $('kpi-ret-foot').textContent = `${L.sessions.toLocaleString()} SessionContext · ${L.directBuffers} direct · ${L.nativeBlocks} native`;
    tagClass($('kpi-ret-tag'), total > 0 ? 'warn' : 'ok', s.loadStats.requestsPerSecond.toFixed(0) + ' req/s');
    // rss
    const rss = m.process.rss;
    $('kpi-rss-value').innerHTML = rss > 0 ? `${fmtB(rss)}<small>hwm ${fmtB(m.process.rssPeak)}</small>` : '—';
    spark($('kpi-rss-spark'), state.hist.slice(-120).map((p) => p.rss), C.native);
    $('kpi-rss-foot').textContent = rss > 0 ? `non-heap footprint ${fmtB(m.verdict.nativeFootprint)} · ${m.process.threads} threads` : `/proc unavailable · ${m.process.threads} threads`;
    tagClass($('kpi-rss-tag'), '', m.process.cpuLoad >= 0 ? 'cpu ' + pct(m.process.cpuLoad) : 'cpu —');
  }

  // ---------------------------------------------------------------- timeline chart
  const tl = { pad: { l: 64, r: 16, t: 14, b: 26 }, geom: null };

  function drawTimeline() {
    const cv = $('timeline');
    const { ctx, w, h } = prep(cv);
    const pts = state.hist;
    if (!pts.length) return;
    const { l, r, t, b } = tl.pad;
    const pw = w - l - r, ph = h - t - b;
    const tEnd = pts[pts.length - 1].t, tStart = tEnd - WINDOW_S * 1000;
    let top = 0;
    for (const p of pts) {
      const stack = p.heapUsed + p.heapFree + p.nonHeap + p.direct + p.native + p.other;
      top = Math.max(top, stack, p.limit, p.xmx, p.ws);
    }
    const yMax = niceMax(top * 1.06);
    const X = (tt) => l + ((tt - tStart) / (WINDOW_S * 1000)) * pw;
    const Y = (v) => t + ph - (v / yMax) * ph;
    tl.geom = { X, Y, l, r, t, b, pw, ph, tStart, w, h };

    // grid + y labels
    ctx.font = '11px ui-monospace, Consolas, monospace';
    ctx.textAlign = 'right'; ctx.textBaseline = 'middle';
    for (let i = 0; i <= 4; i++) {
      const v = (yMax / 4) * i, y = Y(v);
      ctx.strokeStyle = C.grid; ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(l, y + 0.5); ctx.lineTo(w - r, y + 0.5); ctx.stroke();
      ctx.fillStyle = C.axis; ctx.fillText(fmtB(v, 0), l - 8, y);
    }
    ctx.textAlign = 'center'; ctx.textBaseline = 'top';
    for (let s = WINDOW_S; s >= 0; s -= 60) {
      const x = X(tEnd - s * 1000);
      ctx.fillStyle = C.axis; ctx.fillText(s === 0 ? 'now' : `−${s / 60}m`, x, h - b + 8);
    }

    // kill zone above the limit
    const last = pts[pts.length - 1];
    if (last.limit > 0 && last.limit < yMax) {
      ctx.fillStyle = hatchPattern(ctx);
      ctx.fillRect(l, t, pw, Y(last.limit) - t);
      ctx.fillStyle = 'rgba(244,63,94,0.05)';
      ctx.fillRect(l, t, pw, Y(last.limit) - t);
    }

    // stacked layers
    const lower = new Array(pts.length).fill(0);
    for (const layer of LAYERS) {
      const upper = pts.map((p, i) => lower[i] + (p[layer.key] || 0));
      if (upper.some((u, i) => u !== lower[i])) {
        ctx.beginPath();
        pts.forEach((p, i) => (i ? ctx.lineTo(X(p.t), Y(upper[i])) : ctx.moveTo(X(p.t), Y(upper[i]))));
        for (let i = pts.length - 1; i >= 0; i--) ctx.lineTo(X(pts[i].t), Y(lower[i]));
        ctx.closePath();
        ctx.fillStyle = layer.color;
        ctx.globalAlpha = layer.key === 'heapFree' ? 1 : 0.78;
        ctx.fill();
        ctx.globalAlpha = 1;
        if (layer.key !== 'heapFree') {
          ctx.beginPath();
          pts.forEach((p, i) => (i ? ctx.lineTo(X(p.t), Y(upper[i])) : ctx.moveTo(X(p.t), Y(upper[i]))));
          ctx.strokeStyle = layer.color; ctx.lineWidth = 1.2; ctx.stroke();
        }
      }
      upper.forEach((u, i) => (lower[i] = u));
    }

    // working set (kernel's number)
    if (pts.some((p) => p.ws > 0)) {
      line(ctx, pts, (p) => p.ws, X, Y, C.ws, 1, [3, 3]);
    }
    // live set after GC — step line with GC dots
    ctx.beginPath();
    let started = false, prevY = null;
    for (const p of pts) {
      if (p.live == null || p.live < 0) continue;
      const x = X(p.t), y = Y(p.live);
      if (!started) { ctx.moveTo(x, y); started = true; } else { ctx.lineTo(x, prevY); ctx.lineTo(x, y); }
      prevY = y;
    }
    ctx.strokeStyle = C.live; ctx.lineWidth = 2; ctx.shadowColor = C.live; ctx.shadowBlur = 8; ctx.stroke(); ctx.shadowBlur = 0;

    // xmx & limit
    hline(ctx, last.xmx, 'heap ceiling -Xmx ' + fmtB(last.xmx), C.xmx, [6, 4], 'left');
    if (last.limit > 0) hline(ctx, last.limit, 'cgroup limit ' + fmtB(last.limit), C.limit, [], 'right');

    function hline(c, v, label, color, dash, align) {
      if (v <= 0 || v > yMax) return;
      const y = Y(v);
      c.save(); c.setLineDash(dash); c.strokeStyle = color; c.lineWidth = 1.5;
      c.beginPath(); c.moveTo(l, y); c.lineTo(w - r, y); c.stroke(); c.restore();
      c.font = '600 11px ui-sans-serif, system-ui, sans-serif';
      c.textBaseline = 'bottom'; c.textAlign = align;
      const tx = align === 'left' ? l + 8 : w - r - 8;
      const tw = c.measureText(label).width;
      c.fillStyle = 'rgba(7,10,18,0.75)';
      c.fillRect(align === 'left' ? tx - 4 : tx - tw - 4, y - 17, tw + 8, 15);
      c.fillStyle = color; c.fillText(label, tx, y - 3);
    }

    // hover crosshair
    if (state.hover != null && pts[state.hover]) {
      const p = pts[state.hover], x = X(p.t);
      ctx.strokeStyle = 'rgba(226,232,240,0.35)'; ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(x + 0.5, t); ctx.lineTo(x + 0.5, t + ph); ctx.stroke();
    }
  }

  function line(ctx, pts, get, X, Y, color, width, dash) {
    ctx.save();
    ctx.setLineDash(dash || []);
    ctx.beginPath();
    let first = true;
    for (const p of pts) {
      const v = get(p);
      if (v == null || v < 0) continue;
      if (first) { ctx.moveTo(X(p.t), Y(v)); first = false; } else ctx.lineTo(X(p.t), Y(v));
    }
    ctx.strokeStyle = color; ctx.lineWidth = width; ctx.stroke();
    ctx.restore();
  }

  function renderLegend() {
    const items = [
      ...LAYERS.map((x) => `<span><i style="background:${x.color}"></i>${x.label}</span>`),
      `<span><i class="line" style="background:${C.live}"></i>Live set after GC</span>`,
      `<span><i class="line" style="background:${C.ws}"></i>Working set</span>`,
      `<span><i class="dash"></i>-Xmx</span>`,
      `<span><i class="line" style="background:${C.limit}"></i>Limit</span>`,
    ];
    $('legend').innerHTML = items.join('');
  }

  function onTimelineMove(ev) {
    const g = tl.geom;
    if (!g || !state.hist.length) return;
    const rect = $('timeline').getBoundingClientRect();
    const mx = ev.clientX - rect.left;
    if (mx < g.l || mx > g.w - g.r) { hideTip(); return; }
    const tAt = g.tStart + ((mx - g.l) / g.pw) * WINDOW_S * 1000;
    let best = 0, bd = Infinity;
    state.hist.forEach((p, i) => { const d = Math.abs(p.t - tAt); if (d < bd) { bd = d; best = i; } });
    state.hover = best;
    drawTimeline();
    const p = state.hist[best];
    const rows = [
      ['Heap used', p.heapUsed, C.heap], ['Heap free (committed)', p.heapFree, C.heapFree], ['Non-heap', p.nonHeap, C.nonheap],
      ['Direct', p.direct, C.direct], [LAYERS.find((x) => x.key === 'native').label, p.native, C.native], ['Other native', p.other, C.other],
      ['Live after GC', p.live, C.live], ['Working set', p.ws, C.ws], ['RSS', p.rss, '#94a3b8'],
    ];
    const tip = $('tt');
    tip.innerHTML = `<div class="t-time">${clock(p.t)}</div>` + rows.map(([k, v, col]) =>
      `<div class="t-row"><span><i style="background:${col}"></i>${k}</span><b>${fmtB(v)}</b></div>`).join('');
    const x = g.X(p.t);
    tip.style.left = (x > g.w * 0.6 ? x - tip.offsetWidth - 14 : x + 14) + 'px';
    tip.style.top = '18px';
    tip.style.opacity = 1;
  }
  function hideTip() { state.hover = null; $('tt').style.opacity = 0; drawTimeline(); }

  // ---------------------------------------------------------------- GC chart
  function drawGc() {
    const cv = $('gcchart');
    const { ctx, w, h } = prep(cv);
    const pts = state.hist;
    if (!pts.length) return;
    const l = 64, r = 16, t = 8, b = 18, pw = w - l - r, ph = h - t - b;
    const maxMs = Math.max(50, ...pts.map((p) => p.minorMs + p.majorMs)) * 1.15;
    const tEnd = pts[pts.length - 1].t, tStart = tEnd - WINDOW_S * 1000;
    const bw = Math.max(1, pw / WINDOW_S - 1);
    ctx.font = '11px ui-monospace, Consolas, monospace'; ctx.textAlign = 'right'; ctx.textBaseline = 'middle';
    [0, 0.5, 1].forEach((f) => {
      const y = t + ph - f * ph;
      ctx.strokeStyle = C.grid; ctx.beginPath(); ctx.moveTo(l, y + 0.5); ctx.lineTo(w - r, y + 0.5); ctx.stroke();
      ctx.fillStyle = C.axis; ctx.fillText(Math.round(maxMs * f) + ' ms', l - 8, y);
    });
    for (const p of pts) {
      const x = l + ((p.t - tStart) / (WINDOW_S * 1000)) * pw - bw / 2;
      const hMinor = (p.minorMs / maxMs) * ph, hMajor = (p.majorMs / maxMs) * ph;
      if (hMinor > 0) { ctx.fillStyle = C.minor; ctx.fillRect(x, t + ph - hMinor, bw, hMinor); }
      if (hMajor > 0) { ctx.fillStyle = C.major; ctx.fillRect(x, t + ph - hMinor - hMajor, bw, hMajor); }
    }
    const win = pts.slice(-60);
    const minor = win.reduce((a, p) => a + p.minorMs, 0), major = win.reduce((a, p) => a + p.majorMs, 0);
    const span = Math.max(1, win.length) * 1000;
    $('gc-stats').innerHTML = `<span><i style="color:${C.minor}">■</i> minor <b>${minor} ms</b></span>` +
      `<span><i style="color:${C.major}">■</i> global/major <b>${major} ms</b></span>` +
      `<span>overhead <b>${((minor + major) / span * 100).toFixed(1)}%</b> (60 s)</span>`;
  }

  // ---------------------------------------------------------------- anatomy
  function renderAnatomy(s) {
    const m = s.memory, p = state.hist[state.hist.length - 1];
    const limit = m.container.limit;
    const segs = LAYERS.map((x) => ({ ...x, v: p[x.key] || 0 }));
    const used = segs.reduce((a, x) => a + x.v, 0);
    const scale = Math.max(limit > 0 ? limit : 0, m.heap.max + m.verdict.nativeFootprint, used, m.container.workingSet) * 1.04;
    const W = (v) => (v / scale) * 100;
    let left = 0, html = '';
    for (const x of segs) {
      html += `<div class="seg" title="${x.label}: ${fmtB(x.v)}" style="left:${left}%;width:${W(x.v)}%;background:${x.color}"></div>`;
      left += W(x.v);
    }
    if (limit > 0 && limit < scale) {
      html += `<div class="limit-zone" title="Beyond the cgroup limit: the kernel OOM killer acts here" style="left:${W(limit)}%;right:0"></div>`;
      html += `<div class="marker limit" style="left:${W(limit)}%"><span>limit ${fmtB(limit)}</span></div>`;
    }
    html += `<div class="marker xmx bottom" style="left:${Math.min(100, W(m.heap.max))}%"><span>-Xmx ${fmtB(m.heap.max)}</span></div>`;
    $('anatomy-track').innerHTML = html;
    $('anatomy-scale').innerHTML = `<span>0</span><span>${fmtB(scale / 2)}</span><span>${fmtB(scale)}</span>`;
    $('anatomy-legend').innerHTML = segs.map((x) => `<div><i style="background:${x.color}"></i>${x.label}<b>${fmtB(x.v)}</b></div>`).join('')
      + `<div><i style="background:${C.limit}"></i>Headroom to limit<b>${limit > 0 ? fmtB(limit - m.container.workingSet) : '—'}</b></div>`;
  }

  // ---------------------------------------------------------------- deck
  function syncDeck(settings, force) {
    if (!force && Date.now() - state.touchedAt < 2500) return;
    state.ui = { ...settings };
    $('rate').value = scales.rate.toPos(settings.ratePerSecond);
    $('payload').value = scales.payload.toPos(settings.payloadKb);
    $('retention').value = settings.retentionPercent;
    paintDeck();
  }

  function paintDeck() {
    const u = state.ui;
    $('rate-out').textContent = u.ratePerSecond.toLocaleString() + ' req/s';
    $('payload-out').textContent = u.payloadKb >= 1024 ? (u.payloadKb / 1024).toFixed(1) + ' MiB' : u.payloadKb + ' KiB';
    $('retention-out').textContent = u.retentionPercent + '%';
    for (const id of ['rate', 'payload', 'retention']) {
      const el = $(id);
      el.style.setProperty('--p', ((el.value - el.min) / (el.max - el.min)) * 100 + '%');
    }
    document.querySelectorAll('#mode button').forEach((b) => b.setAttribute('aria-checked', String(b.dataset.mode === u.mode)));
    document.querySelectorAll('#on-oom button').forEach((b) => b.setAttribute('aria-checked', String((b.dataset.oom === 'keep') === !!u.keepRunningOnOom)));
    const eng = $('engage');
    eng.setAttribute('aria-pressed', String(u.running));
    eng.querySelector('.lbl').textContent = u.running ? 'Running' : 'Engage';

    const bytes = u.payloadKb * 1024 * u.ratePerSecond;
    const leak = bytes * (u.retentionPercent / 100);
    $('velocity').textContent = fmtRate(u.running ? leak : 0);
    $('churn').textContent = fmtRate(u.running ? bytes - leak : 0);
    const s = state.last;
    let ttl = '—';
    if (s && u.running && leak > 0) {
      const c = s.memory.container;
      const room = c.limit > 0 ? c.limit - c.workingSet : (u.mode === 'HEAP' ? s.memory.heap.max - s.memory.heap.liveAfterGc : -1);
      ttl = room > 0 ? fmtDur(room / leak) : 'now';
    }
    $('ttl').textContent = ttl;
  }

  let pushTimer;
  function pushSettings() {
    state.touchedAt = Date.now();
    paintDeck();
    clearTimeout(pushTimer);
    pushTimer = setTimeout(async () => {
      try { syncDeck(await api('PUT', 'api/load', state.ui), true); } catch (e) { toast('Could not apply: ' + e.message, true); }
    }, 140);
  }

  const PRESETS = {
    healthy: { ratePerSecond: 200, payloadKb: 256, retentionPercent: 0 },
    slow: { ratePerSecond: 20, payloadKb: 128, retentionPercent: 10 },
    aggressive: { ratePerSecond: 100, payloadKb: 512, retentionPercent: 40 },
    native: { mode: 'NATIVE', ratePerSecond: 50, payloadKb: 256, retentionPercent: 30 },
  };

  function wireDeck() {
    $('rate').addEventListener('input', (e) => { state.ui.ratePerSecond = scales.rate.toVal(+e.target.value); pushSettings(); });
    $('payload').addEventListener('input', (e) => { state.ui.payloadKb = scales.payload.toVal(+e.target.value); pushSettings(); });
    $('retention').addEventListener('input', (e) => { state.ui.retentionPercent = +e.target.value; pushSettings(); });
    document.querySelectorAll('#mode button').forEach((b) => b.addEventListener('click', () => { state.ui.mode = b.dataset.mode; pushSettings(); }));
    document.querySelectorAll('#on-oom button').forEach((b) => b.addEventListener('click', () => { state.ui.keepRunningOnOom = b.dataset.oom === 'keep'; pushSettings(); }));
    $('engage').addEventListener('click', () => { state.ui.running = !state.ui.running; pushSettings(); });
    document.querySelectorAll('[data-preset]').forEach((b) => b.addEventListener('click', () => {
      Object.assign(state.ui, PRESETS[b.dataset.preset], { running: true });
      syncDeck(state.ui, true);
      pushSettings();
      toast('Preset · ' + b.textContent);
    }));
    document.querySelectorAll('[data-burst]').forEach((b) => b.addEventListener('click', () => busy(b, async () => {
      const r = await api('POST', 'api/load/burst', { mode: state.ui.mode, megabytes: +b.dataset.burst });
      toast(`Spike: retained ${r.megabytes} MiB in ${state.ui.mode.toLowerCase()} memory`);
    })));
    $('gc').addEventListener('click', (e) => busy(e.currentTarget, async () => {
      const r = await api('POST', 'api/memory/gc');
      toast(`GC: ${fmtB(r.heapBefore)} → ${fmtB(r.heapAfter)} in ${r.durationMs} ms — what survived is reachable`);
    }));
    $('release').addEventListener('click', (e) => busy(e.currentTarget, async () => {
      const r = await api('POST', 'api/memory/release');
      toast(`Released heap ${fmtB(r.heapBytes)} · direct ${fmtB(r.directBytes)} · native ${fmtB(r.nativeBytes)}`);
    }));
    document.querySelectorAll('[data-dump]').forEach((b) => b.addEventListener('click', () => {
      const kind = b.dataset.dump;
      if (kind === 'system' && !confirm('A system core is roughly the size of the process RSS and pauses the JVM while it is written. Continue?')) return;
      busy(b, async () => {
        const f = await api('POST', 'api/dumps/' + kind);
        const label = kind === 'javacore' && state.flavor !== 'OpenJ9' ? 'VM report' : `${kind} dump`;
        toast(`${label} written · ${f.name} · ${fmtB(f.sizeBytes)}`);
        refreshFiles();
      });
    }));
  }

  async function busy(btn, fn) {
    btn.classList.add('busy');
    try { await fn(); } catch (e) { toast(e.message, true); } finally { btn.classList.remove('busy'); }
  }

  // ---------------------------------------------------------------- diagnostics panel
  function renderDiag(s) {
    $('dump-dir').innerHTML = `Written to <code>${esc(s.dumpDirectory)}</code>`;
    state.flavor = s.runtime.flavor;
    state.startTime = s.runtime.startTime;
    $('heap-format').textContent = s.runtime.flavor === 'OpenJ9' ? 'PHD · MAT + DTFJ' : 'HPROF · MAT';
    if (s.runtime.flavor !== 'OpenJ9' && !$('system-core').disabled) {
      $('javacore-label').innerHTML = '<b>VM report</b><small>threads · NMT</small>';
      // com.ibm.jvm.Dump is OpenJ9's: HotSpot has no in-process system dump, and the server would refuse the call
      const sys = $('system-core');
      sys.disabled = true;
      sys.innerHTML = '<b>System core</b><small>OpenJ9 only</small>';
      sys.title = 'Not available on HotSpot: system cores are written through OpenJ9\'s com.ibm.jvm.Dump. '
        + 'Here, take a heap dump and a VM report, or a core of the process from the node with gcore.';
    }
    const g = s.guardian;
    const pr = g.pressure;
    $('guardian-bar').style.width = pr > 0 ? Math.min(100, pr * 100) + '%' : '0';
    $('guardian-bar').style.setProperty('--full', (100 / Math.max(pr, 0.01)) + '%');
    $('guardian-mark').style.left = g.threshold * 100 + '%';
    if (!g.enabled) {
      tagClass($('guardian-tag'), 'warn', 'disabled');
      $('guardian-text').textContent = s.scenario === 'overcommit'
        ? 'Disabled for this scenario: the container dies silently, which is the point of the demo.'
        : 'Disabled (LEAKLAB_GUARDIAN_ENABLED=false): an OOMKill will leave no evidence behind.';
    } else if (pr < 0) {
      tagClass($('guardian-tag'), '', 'idle');
      $('guardian-text').textContent = 'No cgroup limit detected — nothing to guard against.';
    } else {
      tagClass($('guardian-tag'), g.armed ? 'ok' : 'crit', g.armed ? 'armed' : 'fired');
      $('guardian-text').textContent = `Captures ${state.flavor === 'OpenJ9' ? 'javacore' : 'VM report'} + heap dump at ${pct(g.threshold)} of the limit (re-arms below ${pct(g.rearm)}). `
        + (g.firedCount ? `Fired ${g.firedCount}× · last ${clock(g.lastFiredAt)}.` : 'Not fired yet.');
    }
  }

  async function refreshFiles() {
    try {
      const files = await api('GET', 'api/dumps');
      const ul = $('files');
      if (!files.length) { ul.innerHTML = '<li class="empty">No artefacts yet. ' + (state.flavor === 'OpenJ9' ? 'OpenJ9 also writes here automatically on OutOfMemoryError.' : 'HotSpot writes an .hprof here on the first OutOfMemoryError (HeapDumpOnOutOfMemoryError).') + '</li>'; return; }
      ul.innerHTML = files.map((f) => `<li>
        <span class="kind ${esc(f.kind)}">${esc(f.kind)}</span>
        <div style="min-width:0"><div class="fname" title="${esc(f.name)}">${esc(f.name)}</div><div class="fmeta">${fmtB(f.sizeBytes)} · ${clock(f.modifiedAt)}</div></div>
        <a href="api/dumps/${encodeURIComponent(f.name)}/download" download>Download</a></li>`).join('');
    } catch { /* offline */ }
  }

  // ---------------------------------------------------------------- pools, args, kernel, journal
  function renderPools(s) {
    const rows = [...s.memory.pools].sort((a, b) => (a.type === b.type ? b.committed - a.committed : a.type === 'HEAP' ? -1 : 1));
    $('pools-sub').textContent = `${rows.length} pools · ${s.runtime.flavor === 'OpenJ9' ? 'OpenJ9 names: nursery-allocate/survivor, tenured-SOA/LOA, class storage, JIT caches' : 'HotSpot pools'}`;
    $('pools').innerHTML = rows.map((p) => {
      const cap = p.max > 0 ? p.max : p.committed || 1;
      const color = p.type === 'HEAP' ? C.heap : C.nonheap;
      return `<tr><td title="${esc(p.name)}">${esc(p.name)}<span class="ptype">${p.type === 'HEAP' ? 'heap' : 'non-heap'}</span></td>
        <td><div class="pbar"><i style="width:${Math.min(100, (p.committed / cap) * 100)}%;background:${color};opacity:.28"></i><i style="width:${Math.min(100, (p.used / cap) * 100)}%;background:${color}"></i></div></td>
        <td class="num">${fmtB(p.used)}</td><td class="num">${p.afterGc >= 0 ? fmtB(p.afterGc) : '—'}</td></tr>`;
    }).join('');
  }

  function renderArgs(s) {
    const hot = /^-(Xmx|Xms|XX:(MaxRAMPercentage|InitialRAMPercentage|MaxDirectMemorySize|\+?-?UseContainerSupport|\+ExitOnOutOfMemoryError|\+HeapDumpOnOutOfMemoryError)|Xdump|Xgcpolicy|Xverbosegclog|Xshareclasses)/;
    $('args').innerHTML = s.runtime.jvmArguments.map((a) => `<code class="${hot.test(a) ? 'hot' : ''}">${esc(a)}</code>`).join('') || '<code>(none)</code>';
  }

  const KERNEL_HELP = {
    max: 'usage hit memory.max; kernel forced reclaim',
    high: 'throttled above memory.high',
    oom: 'reclaim failed: OOM condition',
    oom_kill: 'processes SIGKILLed in this cgroup',
    oom_group_kill: 'whole-cgroup kills',
    low: 'reclaimed despite memory.low',
  };
  function renderKernel(s) {
    const c = s.memory.container;
    const ev = c.events || {};
    const keys = ['max', 'oom', 'oom_kill', 'high'].filter((k) => k in ev);
    const cells = keys.map((k) => `<div class="kstat ${ev[k] > 0 && k.startsWith('oom') ? 'hot' : ''}"><span>${k}</span><b>${ev[k].toLocaleString()}</b><small>${KERNEL_HELP[k]}</small></div>`);
    cells.push(`<div class="kstat"><span>peak</span><b>${fmtB(c.peak)}</b><small>memory.peak high-water mark</small></div>`);
    if (!keys.length) {
      cells.unshift(`<div class="kstat"><span>cgroup</span><b>${c.cgroup}</b><small>${c.cgroup === 'none' ? 'not containerised — run it in Docker/OpenShift' : 'events not exposed'}</small></div>`);
    }
    $('kernel').innerHTML = cells.join('');
  }

  function renderJournal(s) {
    const entries = s.journal || [];
    $('journal').innerHTML = entries.slice().reverse().map((e) =>
      `<li class="${e.level}"><time>${clock(e.timestamp)}</time><i></i><span>${esc(e.message)}</span></li>`).join('');
    for (const e of entries) {
      if (e.seq > state.lastJournalSeq && state.lastJournalSeq > 0 && e.level === 'CRITICAL') toast(e.message, true);
    }
    if (entries.length) state.lastJournalSeq = Math.max(state.lastJournalSeq, entries[entries.length - 1].seq);
  }

  // ---------------------------------------------------------------- stream
  function onSnapshot(s) {
    if (state.startTime && s.runtime.startTime !== state.startTime) {
      state.hist = []; state.prevCollectors = null; state.lastJournalSeq = 0;
      toast('A new JVM answered (pid ' + s.runtime.pid + ') — the previous process is gone. Check its exit reason.', true);
    }
    state.startTime = s.runtime.startTime;
    state.last = s;
    state.hist.push(point(s));
    const cutoff = s.timestamp - WINDOW_S * 1000 - 2000;
    while (state.hist.length && state.hist[0].t < cutoff) state.hist.shift();

    renderHeader(s);
    renderVerdict(s);
    renderSignal();
    renderKpis(s);
    drawTimeline();
    drawGc();
    renderAnatomy(s);
    syncDeck(s.load, false);
    paintDeck();
    renderDiag(s);
    renderPools(s);
    renderKernel(s);
    renderJournal(s);
    if (!renderArgs.done) { renderArgs(s); renderArgs.done = true; }
  }

  function setLive(on, text) {
    const chip = $('chip-live');
    chip.className = 'chip chip-live ' + (on ? 'on' : 'off');
    chip.querySelector('span').textContent = text;
  }

  function lostContact() {
    const s = state.last;
    if (!s || !state.everConnected) return;
    const c = s.memory.container;
    const p = c.limit > 0 ? c.workingSet / c.limit : -1;
    const oomSeen = (s.journal || []).some((e) => e.message.includes('OutOfMemoryError'));
    const el = $('verdict');
    el.dataset.level = 'OOMKILL_BY_DESIGN';
    $('verdict-level').textContent = 'connection lost';
    $('verdict-headline').textContent = 'Lost contact with the JVM';
    $('verdict-detail').textContent = (p > 0.8 && !oomSeen)
      ? `Last seen at ${pct(p)} of the container limit, and no OutOfMemoryError was ever logged. That is the external signature of a kernel OOMKill (SIGKILL, exit 137): confirm with "docker inspect -f '{{.State.OOMKilled}}'" or "oc get pod -o jsonpath='{..lastState.terminated.reason}'".`
      : 'The telemetry stream dropped. The process may have been restarted, killed or the route is down. Reconnecting…';
  }

  function connect() {
    const es = new EventSource('api/telemetry/stream');
    es.onopen = () => { state.connected = true; state.everConnected = true; setLive(true, 'live · 1 Hz'); };
    es.onmessage = (e) => { try { onSnapshot(JSON.parse(e.data)); } catch (err) { console.error(err); } };
    es.onerror = () => {
      if (state.connected) lostContact();
      state.connected = false;
      setLive(false, 'reconnecting');
    };
  }

  // ---------------------------------------------------------------- boot
  renderLegend();
  wireDeck();
  paintDeck();
  $('timeline').addEventListener('mousemove', onTimelineMove);
  $('timeline').addEventListener('mouseleave', hideTip);
  window.addEventListener('resize', () => { drawTimeline(); drawGc(); });
  api('GET', 'api/load').then((s) => syncDeck(s, true)).catch(() => {});
  refreshFiles();
  setInterval(refreshFiles, 5000);
  connect();
})();
