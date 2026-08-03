/*
 * DROP·AIM — TRAINING SIMULATOR
 * =============================
 * Synthesises the camera feed and the telemetry so an operator can practise the
 * complete engagement without an aircraft.
 *
 * Design rule: the simulator only replaces the two INPUTS (video frames and
 * telemetry). Target marking, the Lucas-Kanade tracker, the RK4 aim solution,
 * the offset/registration and the drop log are the app's own, unmodified code —
 * so what the trainee learns is the real system, not a mock-up.
 *
 * Camera model: gimbal-stabilised nadir view. World->screen is a rotate+scale
 * about the drone position with pixels-per-metre = VC.width/(2*alt)*zoom, i.e.
 * exactly the projection computeAim() assumes, so the overlay lines up.
 *
 * Truth vs. dialled: the fall is integrated with slightly different drag and a
 * gusting wind than the operator has entered, so good technique produces good —
 * but not perfect — results, and sloppy technique visibly misses.
 */
(function () {
'use strict';

const S = window.SIM = {
  on: false,
  scene: 'dummy',
  ground: null,          // offscreen canvas holding the terrain
  mpp: 0.1,              // metres per ground-image pixel
  // Start already holding over the target area — at 22x zoom and 150 m the
  // field of view is only ~14 m across, so the aircraft is positioned as it
  // would be on task, with the target in shot and a few metres of offset to fly.
  drone: { n: 4, e: -3, alt: 150, hdg: 0 },
  target: { n: 0, e: 0 },
  wind: { spd: 4, dir: 200 },
  shake: 0.5,
  t: 0,
  raf: null,
  keys: {},
  lastDrop: null,
};

const D2R = Math.PI / 180;
const E = id => document.getElementById(id);

// ── terrain generation ────────────────────────────────────────────
// Feature sizes are specified in METRES and converted with mpp, so the terrain
// stays realistic at the ~60 px/m the camera actually resolves (22x @ 150 m).
function makeGround(size, mpp, withTarget) {
  const c = document.createElement('canvas');
  c.width = c.height = size;
  const g = c.getContext('2d');
  const m = v => v / mpp;                        // metres -> px

  // base soil
  g.fillStyle = '#8a7f6d'; g.fillRect(0, 0, size, size);

  // large tonal patches (2–12 m across)
  for (let i = 0; i < 140; i++) {
    const x = Math.random() * size, y = Math.random() * size, r = m(2 + Math.random() * 10);
    const sh = 105 + Math.random() * 55;
    g.fillStyle = `rgba(${sh},${sh - 8},${sh - 24},0.07)`;
    g.beginPath(); g.arc(x, y, r, 0, 7); g.fill();
  }
  // vehicle tracks — strong linear features the tracker locks onto
  for (let i = 0; i < 6; i++) {
    const y0 = Math.random() * size, a = (Math.random() - 0.5) * 0.7;
    g.strokeStyle = 'rgba(80,72,60,0.35)'; g.lineWidth = m(0.25 + Math.random() * 0.2);
    g.beginPath(); g.moveTo(-50, y0); g.lineTo(size + 50, y0 + Math.tan(a) * size); g.stroke();
    g.strokeStyle = 'rgba(190,180,160,0.22)'; g.lineWidth = m(0.08);
    g.beginPath(); g.moveTo(-50, y0 + m(0.35)); g.lineTo(size + 50, y0 + m(0.35) + Math.tan(a) * size); g.stroke();
  }
  // scrub bushes (0.3–1.2 m)
  for (let i = 0; i < 700; i++) {
    const x = Math.random() * size, y = Math.random() * size, r = m(0.10 + Math.random() * 0.28);
    g.fillStyle = `rgba(72,78,52,${0.22 + Math.random() * 0.30})`;
    g.beginPath(); g.arc(x, y, r, 0, 7); g.fill();
  }
  // gravel / stones (3–15 cm) — the high-frequency detail optical flow needs
  const stones = Math.round(size * size / 900);
  for (let i = 0; i < stones; i++) {
    const x = Math.random() * size, y = Math.random() * size, r = m(0.015 + Math.random() * 0.06);
    g.fillStyle = Math.random() < 0.5 ? `rgba(60,56,44,${0.25 + Math.random() * 0.4})`
                                      : `rgba(205,198,178,${0.2 + Math.random() * 0.4})`;
    g.beginPath(); g.arc(x, y, Math.max(0.6, r), 0, 7); g.fill();
  }
  // fine grain
  const img = g.getImageData(0, 0, size, size), d = img.data;
  for (let i = 0; i < d.length; i += 4) {
    const n = (Math.random() - 0.5) * 22;
    d[i] += n; d[i + 1] += n; d[i + 2] += n;
  }
  g.putImageData(img, 0, 0);

  if (withTarget) paintTarget(g, size / 2, size / 2, mpp);
  return c;
}

// Painted range target centred on the world origin. Rings are 1/2/5 m so the
// whole aiming mark sits inside the ~14 m field of view at 22x / 150 m.
function paintTarget(g, cx, cy, mpp) {
  const m = r => r / mpp;                      // metres -> px
  g.save();
  g.lineWidth = Math.max(1.5, m(0.12));
  [5, 2, 1].forEach((r, i) => {
    g.strokeStyle = i % 2 ? 'rgba(240,240,240,0.9)' : 'rgba(215,55,45,0.85)';
    g.beginPath(); g.arc(cx, cy, m(r), 0, 7); g.stroke();
  });
  g.fillStyle = 'rgba(215,55,45,0.9)';
  g.beginPath(); g.arc(cx, cy, m(0.35), 0, 7); g.fill();
  g.strokeStyle = 'rgba(240,240,240,0.9)'; g.lineWidth = Math.max(1.5, m(0.1));
  g.beginPath();
  g.moveTo(cx - m(7), cy); g.lineTo(cx + m(7), cy);
  g.moveTo(cx, cy - m(7)); g.lineTo(cx, cy + m(7)); g.stroke();
  g.restore();
}

// ── scene loading ─────────────────────────────────────────────────
function loadDummy() {
  // 2560 px at 2.5 cm/px = 64 m of ground at 40 px/m — close to what the camera
  // actually resolves at 22x / 150 m (~63 px/m), so the feed looks sharp.
  S.mpp = 0.025;
  S.ground = makeGround(2560, S.mpp, true);
  S.target = { n: 0, e: 0 };
}

function loadImage(file, groundWidthM) {
  const url = URL.createObjectURL(file);
  const im = new Image();
  im.onload = () => {
    const c = document.createElement('canvas');
    c.width = im.width; c.height = im.height;
    const g = c.getContext('2d');
    g.drawImage(im, 0, 0);
    S.mpp = groundWidthM / im.width;
    // Put a subtle aim-point marker at the centre so the trainee has something
    // to engage; the imagery itself supplies the tracking features.
    paintTarget(g, im.width / 2, im.height / 2, S.mpp);
    S.ground = c;
    S.target = { n: 0, e: 0 };
    URL.revokeObjectURL(url);
    status('Satellite scene loaded — ' + im.width + '×' + im.height + ' px, ' +
           S.mpp.toFixed(3) + ' m/px', 'ok');
  };
  im.onerror = () => status('Could not read that image.', 'err');
  im.src = url;
}

// ── render one frame into the app's offscreen canvas ──────────────
function renderScene() {
  if (!S.ground) return;
  if (VC.width !== 854 || VC.height !== 480) { VC.width = 854; VC.height = 480; }
  const W = VC.width, H = VC.height;

  // hover shake: small translation + heading wobble, scaled by the Shake slider
  const k = S.shake;
  S.t += 1 / 15;
  const shN = k * (0.22 * Math.sin(S.t * 1.7) + 0.10 * Math.sin(S.t * 4.3 + 1));
  const shE = k * (0.22 * Math.cos(S.t * 1.3) + 0.10 * Math.sin(S.t * 3.1 + 2));
  const shH = k * (0.5 * Math.sin(S.t * 0.9) + 0.25 * Math.sin(S.t * 2.6));

  const Dn = S.drone.n + shN, De = S.drone.e + shE;
  const h = (S.drone.hdg + shH) * D2R;
  const zoomEl = E('zoom');
  const zoom = zoomEl ? parseFloat(zoomEl.value) : 22;
  const P = W / (2 * S.drone.alt) * zoom;        // px per metre — matches computeAim
  const mpp = S.mpp, gW = S.ground.width, gH = S.ground.height;

  // ground image px -> world metres (north up, origin at image centre)
  const a0 = 0 - Dn + (gH / 2) * mpp;
  const b0 = 0 - De - (gW / 2) * mpp;
  const cs = Math.cos(h), sn = Math.sin(h);

  vx.setTransform(1, 0, 0, 1, 0, 0);
  vx.fillStyle = '#000'; vx.fillRect(0, 0, W, H);
  vx.setTransform(
    P * mpp * cs, -P * mpp * sn,
    P * mpp * sn,  P * mpp * cs,
    W / 2 + P * (-a0 * sn + b0 * cs),
    H / 2 - P * ( a0 * cs + b0 * sn)
  );
  vx.imageSmoothingEnabled = true;
  vx.drawImage(S.ground, 0, 0);
  vx.setTransform(1, 0, 0, 1, 0, 0);

  // light sensor grain so the feed does not look synthetic
  vx.globalAlpha = 0.05;
  for (let i = 0; i < 120; i++) {
    vx.fillStyle = Math.random() < 0.5 ? '#fff' : '#000';
    vx.fillRect(Math.random() * W, Math.random() * H, 2, 2);
  }
  vx.globalAlpha = 1;
}

// ── simulated telemetry ───────────────────────────────────────────
function pushTelemetry() {
  const gust = 1 + 0.18 * Math.sin(S.t * 0.7) + 0.08 * Math.sin(S.t * 2.9);
  telemetry.altAGL = S.drone.alt;
  telemetry.altMSL = S.drone.alt + 430;
  telemetry.heading = (S.drone.hdg + 360) % 360;
  telemetry.yaw = telemetry.heading;
  telemetry.roll = 1.8 * Math.sin(S.t * 1.9) * S.shake;
  telemetry.pitch = -2.2 + 1.4 * Math.sin(S.t * 1.4) * S.shake;
  telemetry.vx = 0.05 * Math.sin(S.t * 1.1);
  telemetry.vy = 0.05 * Math.cos(S.t * 0.9);
  telemetry.vz = 0;
  telemetry.groundspeed = Math.hypot(telemetry.vx, telemetry.vy);
  telemetry.windSpeed = S.wind.spd * gust;
  telemetry.windDir = S.wind.dir;
  telemetry.mode = 'LOITER';
  telemetry.videoOk = true; telemetry.mavlinkOk = true;
  try { updateHUD(); updateStatus(); } catch (e) {}
}

// ── main loop (15 fps, like the real feed) ────────────────────────
function tick() {
  if (!S.on) return;
  flyFromKeys();
  renderScene();
  pushTelemetry();
  try { processFrame(); } catch (e) { console.error('[SIM]', e); }
  S.raf = setTimeout(tick, 1000 / 15);
}

// ── flying ────────────────────────────────────────────────────────
function flyFromKeys() {
  const step = 0.35;                              // metres per frame
  const h = S.drone.hdg * D2R;
  let f = 0, r = 0;
  if (S.keys.ArrowUp) f += step;
  if (S.keys.ArrowDown) f -= step;
  if (S.keys.ArrowLeft) r -= step;
  if (S.keys.ArrowRight) r += step;
  if (f || r) {
    S.drone.n += f * Math.cos(h) + r * Math.cos(h + Math.PI / 2);
    S.drone.e += f * Math.sin(h) + r * Math.sin(h + Math.PI / 2);
  }
}
function nudge(fwd, right) {
  const h = S.drone.hdg * D2R, s = 1.0;
  S.drone.n += s * (fwd * Math.cos(h) + right * Math.cos(h + Math.PI / 2));
  S.drone.e += s * (fwd * Math.sin(h) + right * Math.sin(h + Math.PI / 2));
}

// ── the drop ──────────────────────────────────────────────────────
// Integrates the fall with TRUTH parameters (not the operator's dialled ones)
// so the result rewards correct setup without ever being exactly perfect.
function simulateDrop() {
  if (!S.on) return;
  const alt = S.drone.alt;
  const mass = parseFloat(V('pm'));
  const cdDial = getEffCd(), areaDial = parseFloat(V('pa'));

  // truth: drag within ~12% of dialled, wind gusting ~15% and veering a few degrees
  const cdT = cdDial * (0.88 + Math.random() * 0.24);
  const areaT = areaDial * (0.92 + Math.random() * 0.16);
  const wsT = S.wind.spd * (0.85 + Math.random() * 0.30);
  const wdT = S.wind.dir + (Math.random() - 0.5) * 10;

  const { drift, tof } = rk4Drop(alt, mass, cdT, areaT, 0, wsT);

  // downwind displacement + the drone's own residual motion at release
  const dw = ((wdT + 180) % 360) * D2R;
  let impN = S.drone.n + drift * Math.cos(dw);
  let impE = S.drone.e + drift * Math.sin(dw);
  impN += (telemetry.vx || 0) * tof;
  impE += (telemetry.vy || 0) * tof;

  const missN = impN - S.target.n, missE = impE - S.target.e;
  const miss = Math.hypot(missN, missE);
  const brg = (Math.atan2(missE, missN) / D2R + 360) % 360;
  const dwn = missN * Math.cos(dw) + missE * Math.sin(dw);
  const crs = missN * Math.cos(dw + Math.PI / 2) + missE * Math.sin(dw + Math.PI / 2);

  bakeImpact(impN, impE);
  S.lastDrop = { miss, brg, dwn, crs, tof, drift };

  const verdict = miss < 2 ? '✔ EXCELLENT' : miss < 5 ? '✔ HIT (inside 5 m)' : '✘ MISS';
  const col = miss < 5 ? 'var(--gn)' : 'var(--rd)';
  status(`<b style="color:${col}">${verdict}</b> — ${miss.toFixed(1)} m @ ${brg.toFixed(0)}°<br>` +
         `downwind ${dwn >= 0 ? '+' : ''}${dwn.toFixed(1)} m · cross ${crs.toFixed(1)} m<br>` +
         `TOF ${tof.toFixed(1)} s · true drift ${drift.toFixed(1)} m`, miss < 5 ? 'ok' : 'err');
  showBanner(`${verdict} — ${miss.toFixed(1)} m`); hideBanner(3000);
}

// Burn the crater into the terrain so it persists and tracks with the ground.
function bakeImpact(n, e) {
  const g = S.ground.getContext('2d');
  const x = S.ground.width / 2 + e / S.mpp;
  const y = S.ground.height / 2 - n / S.mpp;
  const r = 0.9 / S.mpp;
  const grd = g.createRadialGradient(x, y, 0, x, y, r * 2.6);
  grd.addColorStop(0, 'rgba(30,26,20,0.95)');
  grd.addColorStop(0.45, 'rgba(70,62,50,0.55)');
  grd.addColorStop(1, 'rgba(150,140,120,0)');
  g.fillStyle = grd;
  g.beginPath(); g.arc(x, y, r * 2.6, 0, 7); g.fill();
  for (let i = 0; i < 60; i++) {
    const a = Math.random() * 7, d = r * (1 + Math.random() * 3);
    g.fillStyle = `rgba(40,36,28,${0.25 + Math.random() * 0.35})`;
    g.beginPath(); g.arc(x + Math.cos(a) * d, y + Math.sin(a) * d, 1 + Math.random() * 2.5, 0, 7); g.fill();
  }
}

// ── UI wiring ─────────────────────────────────────────────────────
function status(html, cls) {
  const b = E('simR'); b.classList.add('show');
  b.style.color = cls === 'err' ? 'var(--rd)' : cls === 'warn' ? 'var(--gd)' : 'var(--gn)';
  b.style.borderColor = cls === 'err' ? 'rgba(255,59,85,.4)' : 'rgba(0,255,136,.3)';
  b.style.background = cls === 'err' ? 'rgba(255,59,85,.06)' : 'rgba(0,255,136,.05)';
  b.innerHTML = html;
}

function start() {
  if (!S.ground) loadDummy();
  S.on = true;
  E('simRows').style.display = 'flex';
  E('simBtn').textContent = '■ STOP SIMULATOR';
  E('simBtn').classList.add('btn-rd');
  document.getElementById('nosig').classList.add('hide');
  status('Simulator running. Tap the target to mark it, CALCULATE AIM, fly 🔵 onto 🟢, then SIMULATE DROP.', 'ok');
  tick();
}
function stop() {
  S.on = false;
  clearTimeout(S.raf);
  E('simRows').style.display = 'none';
  E('simBtn').textContent = '▶ START SIMULATOR';
  E('simBtn').classList.remove('btn-rd');
}

function bindUI() {
  E('simBtn').addEventListener('click', () => S.on ? stop() : start());
  E('simScene').addEventListener('change', () => {
    S.scene = V('simScene');
    E('simSatRow').style.display = S.scene === 'sat' ? 'flex' : 'none';
    if (S.scene === 'dummy') { loadDummy(); status('Dummy range loaded.', 'ok'); }
    else status('Load a satellite image, then set its ground width.', 'warn');
  });
  E('simFile').addEventListener('change', ev => {
    const f = ev.target.files && ev.target.files[0];
    if (f) loadImage(f, parseFloat(V('simGw')));
  });
  const lbl = (id, l, fmt) => { const el = E(id), lb = E(l); const u = () => lb.textContent = fmt(parseFloat(el.value)); el.addEventListener('input', u); u(); };
  lbl('simGw', 'simGwL', v => v.toFixed(0) + ' m');
  lbl('simAlt', 'simAltL', v => v.toFixed(0) + ' m');
  lbl('simWs', 'simWsL', v => v.toFixed(1) + ' m/s');
  lbl('simWd', 'simWdL', v => v.toFixed(0) + '°');
  lbl('simSh', 'simShL', v => v.toFixed(0) + '%');
  E('simAlt').addEventListener('input', () => S.drone.alt = parseFloat(V('simAlt')));
  E('simWs').addEventListener('input', () => S.wind.spd = parseFloat(V('simWs')));
  E('simWd').addEventListener('input', () => S.wind.dir = parseFloat(V('simWd')));
  E('simSh').addEventListener('input', () => S.shake = parseFloat(V('simSh')) / 100);
  E('simGw').addEventListener('input', () => {
    if (S.scene === 'sat' && S.ground) S.mpp = parseFloat(V('simGw')) / S.ground.width;
  });

  E('simUp').addEventListener('click', () => nudge(1, 0));
  E('simDown').addEventListener('click', () => nudge(-1, 0));
  E('simLeft').addEventListener('click', () => nudge(0, -1));
  E('simRight').addEventListener('click', () => nudge(0, 1));
  E('simCtr').addEventListener('click', () => {
    // fly straight onto the computed aim point (teaching aid)
    if (!result || !aimPoint) { status('Compute an aim first.', 'warn'); return; }
    const P = VC.width / (2 * S.drone.alt) * parseFloat(V('zoom'));
    const dx = (aimPoint.x - MC.width / 2) * (VC.width / MC.width) / P;
    const dy = -(aimPoint.y - MC.height / 2) * (VC.height / MC.height) / P;
    const h = S.drone.hdg * D2R;
    S.drone.n += dy * Math.cos(h) + dx * Math.cos(h + Math.PI / 2);
    S.drone.e += dy * Math.sin(h) + dx * Math.sin(h + Math.PI / 2);
  });
  E('simDrop').addEventListener('click', simulateDrop);
  E('simReset').addEventListener('click', () => {
    loadDummy();
    if (S.scene === 'sat') status('Reloaded dummy range — reload your image for satellite mode.', 'warn');
    S.drone.n = (Math.random() - 0.5) * 9;   // a few metres off the target
    S.drone.e = (Math.random() - 0.5) * 9;
    S.drone.hdg = Math.random() * 360;
    try { E('clearBtn').click(); } catch (e) {}
    status('Reset. New start position and heading.', 'ok');
  });

  window.addEventListener('keydown', e => {
    if (!S.on) return;
    if (e.key.startsWith('Arrow')) { S.keys[e.key] = true; e.preventDefault(); }
  });
  window.addEventListener('keyup', e => { S.keys[e.key] = false; });
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bindUI);
else bindUI();

})();
