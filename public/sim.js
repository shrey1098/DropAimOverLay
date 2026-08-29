

(function () {
'use strict';

const S = window.SIM = {
  on: false,
  scene: 'dummy',
  ground: null,
  mpp: 0.1,

  drone: { n: 4, e: -3, alt: 150, hdg: 0 },
  target: { n: 0, e: 0 },
  wind: { spd: 4, dir: 200 },
  shake: 0.5,
  t: 0,
  raf: null,
  keys: {},
  stick: { x:0, y:0, z:0, rz:0, rx:0, ry:0, hatx:0, haty:0 },
  stickSeen: false,
  map: 'rzz',
  lastDrop: null,
  fall: null,
};

const D2R = Math.PI / 180;

window.__stick = function (a) {
  Object.assign(S.stick, a);
  for (const k in a) if (Math.abs(a[k]) > 0.12) S.stickSeen = true;
  if (S.on) showStickReadout();
};
window.__pad = function (btn) {
  if (!S.on) return;
  if (btn === 'up') nudge(1, 0); else if (btn === 'down') nudge(-1, 0);
  else if (btn === 'left') nudge(0, -1); else if (btn === 'right') nudge(0, 1);
  else if (btn === 'a') simulateDrop();
};
const E = id => document.getElementById(id);

function makeGround(size, mpp, withTarget) {
  const c = document.createElement('canvas');
  c.width = c.height = size;
  const g = c.getContext('2d');
  const m = v => v / mpp;

  g.fillStyle = '#8a7f6d'; g.fillRect(0, 0, size, size);

  for (let i = 0; i < 140; i++) {
    const x = Math.random() * size, y = Math.random() * size, r = m(2 + Math.random() * 10);
    const sh = 105 + Math.random() * 55;
    g.fillStyle = `rgba(${sh},${sh - 8},${sh - 24},0.07)`;
    g.beginPath(); g.arc(x, y, r, 0, 7); g.fill();
  }

  for (let i = 0; i < 6; i++) {
    const y0 = Math.random() * size, a = (Math.random() - 0.5) * 0.7;
    g.strokeStyle = 'rgba(80,72,60,0.35)'; g.lineWidth = m(0.25 + Math.random() * 0.2);
    g.beginPath(); g.moveTo(-50, y0); g.lineTo(size + 50, y0 + Math.tan(a) * size); g.stroke();
    g.strokeStyle = 'rgba(190,180,160,0.22)'; g.lineWidth = m(0.08);
    g.beginPath(); g.moveTo(-50, y0 + m(0.35)); g.lineTo(size + 50, y0 + m(0.35) + Math.tan(a) * size); g.stroke();
  }

  for (let i = 0; i < 700; i++) {
    const x = Math.random() * size, y = Math.random() * size, r = m(0.10 + Math.random() * 0.28);
    g.fillStyle = `rgba(72,78,52,${0.22 + Math.random() * 0.30})`;
    g.beginPath(); g.arc(x, y, r, 0, 7); g.fill();
  }

  const stones = Math.round(size * size / 900);
  for (let i = 0; i < stones; i++) {
    const x = Math.random() * size, y = Math.random() * size, r = m(0.015 + Math.random() * 0.06);
    g.fillStyle = Math.random() < 0.5 ? `rgba(60,56,44,${0.25 + Math.random() * 0.4})`
                                      : `rgba(205,198,178,${0.2 + Math.random() * 0.4})`;
    g.beginPath(); g.arc(x, y, Math.max(0.6, r), 0, 7); g.fill();
  }

  const img = g.getImageData(0, 0, size, size), d = img.data;
  for (let i = 0; i < d.length; i += 4) {
    const n = (Math.random() - 0.5) * 22;
    d[i] += n; d[i + 1] += n; d[i + 2] += n;
  }
  g.putImageData(img, 0, 0);

  if (withTarget) paintTarget(g, size / 2, size / 2, mpp);
  return c;
}

function paintTarget(g, cx, cy, mpp) {
  const m = r => r / mpp;
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

function loadDummy() {

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

function renderScene() {
  if (!S.ground) return;
  if (VC.width !== 854 || VC.height !== 480) { VC.width = 854; VC.height = 480; }
  const W = VC.width, H = VC.height;

  const k = S.shake;
  S.t += 1 / 15;
  const shN = k * (0.22 * Math.sin(S.t * 1.7) + 0.10 * Math.sin(S.t * 4.3 + 1));
  const shE = k * (0.22 * Math.cos(S.t * 1.3) + 0.10 * Math.sin(S.t * 3.1 + 2));
  const shH = k * (0.5 * Math.sin(S.t * 0.9) + 0.25 * Math.sin(S.t * 2.6));

  const Dn = S.drone.n + shN, De = S.drone.e + shE;
  const h = (S.drone.hdg + shH) * D2R;
  const zoomEl = E('zoom');
  const zoom = zoomEl ? parseFloat(zoomEl.value) : 22;
  const P = W / (2 * S.drone.alt) * zoom;
  const mpp = S.mpp, gW = S.ground.width, gH = S.ground.height;

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

  vx.globalAlpha = 0.05;
  for (let i = 0; i < 120; i++) {
    vx.fillStyle = Math.random() < 0.5 ? '#fff' : '#000';
    vx.fillRect(Math.random() * W, Math.random() * H, 2, 2);
  }
  vx.globalAlpha = 1;
}

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

function tick() {
  if (!S.on) return;
  flyFromKeys();
  applyStick();
  renderScene();

  try { drawFall(); } catch (e) { console.error('[SIM/fall]', e); S.fall = null; }
  pushTelemetry();
  try { processFrame(); } catch (e) { console.error('[SIM]', e); }
  S.raf = setTimeout(tick, 1000 / 15);
}

function applyStick() {
  const st = S.stick;
  let fwd = 0, right = 0, yaw = 0, climb = 0;
  if (S.map === 'rzz') {
    right = st.z;  fwd = -st.rz;  yaw = st.x;  climb = -st.y;
  } else if (S.map === 'xy') {
    right = st.x;  fwd = -st.y;   yaw = st.z;  climb = -st.rz;
  } else if (S.map === 'rxry') {
    right = st.rx; fwd = -st.ry;  yaw = st.x;  climb = -st.y;
  }

  right += st.hatx; fwd += -st.haty;

  const RATE = 2.2;
  const dt = 1 / 15;
  if (fwd || right) {
    const h = S.drone.hdg * D2R;
    S.drone.n += (fwd * Math.cos(h) + right * Math.cos(h + Math.PI / 2)) * RATE * dt;
    S.drone.e += (fwd * Math.sin(h) + right * Math.sin(h + Math.PI / 2)) * RATE * dt;
  }
  if (yaw)   S.drone.hdg = (S.drone.hdg + yaw * 45 * dt + 360) % 360;
  if (climb) {
    S.drone.alt = Math.max(50, Math.min(400, S.drone.alt + climb * 12 * dt));
    const el = E('simAlt'); if (el) { el.value = S.drone.alt; E('simAltL').textContent = S.drone.alt.toFixed(0) + ' m'; }
  }
}

function showStickReadout() {
  const el = E('simStick'); if (!el) return;
  const st = S.stick;
  const f = v => (v >= 0 ? '+' : '') + v.toFixed(2);
  el.innerHTML = S.stickSeen
    ? `<b style="color:var(--gn)">STICKS DETECTED</b><br>` +
      `x ${f(st.x)}  y ${f(st.y)}<br>z ${f(st.z)}  rz ${f(st.rz)}<br>` +
      `rx ${f(st.rx)}  ry ${f(st.ry)}  hat ${f(st.hatx)}/${f(st.haty)}`
    : `<b style="color:var(--gd)">no stick input seen yet</b><br>` +
      `move a stick — if nothing changes, this GCS does not expose its sticks to Android`;
}

function flyFromKeys() {
  const step = 0.35;
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

function fallTrajectory(h, mass, cd, area, wSpd) {
  const DT = 0.005, G = 9.80665;
  let x = 0, y = h, vx0 = 0, vy = 0, t = 0;
  const path = [{ t: 0, x: 0, y: h }];
  const acc = (vvx, vvy, yy) => {
    const rho = getRho(Math.max(0, yy));
    const wL = windAtAlt(wSpd, Math.max(0.1, yy));
    const rx = vvx - wL, ry = vvy;
    const sp = Math.sqrt(rx * rx + ry * ry);
    const F = sp < 1e-9 ? 0 : 0.5 * rho * sp * sp * cd * area;
    return { ax: sp < 1e-9 ? 0 : -F * (rx / sp) / mass,
             ay: -G + (sp < 1e-9 ? 0 : -F * (ry / sp) / mass) };
  };
  let i = 0;
  while (y > 0 && t < 60) {
    const a1 = acc(vx0, vy, y);
    const a2 = acc(vx0 + a1.ax * DT / 2, vy + a1.ay * DT / 2, y + vy * DT / 2);
    const a3 = acc(vx0 + a2.ax * DT / 2, vy + a2.ay * DT / 2, y + (vy + a1.ay * DT / 2) * DT / 2);
    const a4 = acc(vx0 + a3.ax * DT, vy + a3.ay * DT, y + (vy + a2.ay * DT / 2) * DT);
    vx0 += (DT / 6) * (a1.ax + 2 * a2.ax + 2 * a3.ax + a4.ax);
    vy  += (DT / 6) * (a1.ay + 2 * a2.ay + 2 * a3.ay + a4.ay);
    x += vx0 * DT; y += vy * DT; t += DT;
    if (++i % 4 === 0) path.push({ t, x, y: Math.max(0, y) });
  }
  path.push({ t, x, y: 0 });
  return path;
}

function simulateDrop() {
  if (!S.on || S.fall) return;
  const alt = S.drone.alt;
  const mass = parseFloat(V('pm'));
  const cdDial = getEffCd(), areaDial = parseFloat(V('pa'));

  const cdT = cdDial * (0.88 + Math.random() * 0.24);
  const areaT = areaDial * (0.92 + Math.random() * 0.16);
  const wsT = S.wind.spd * (0.85 + Math.random() * 0.30);
  const wdT = S.wind.dir + (Math.random() - 0.5) * 10;

  const path = fallTrajectory(alt, mass, cdT, areaT, wsT);
  const drift = path[path.length - 1].x, tof = path[path.length - 1].t;

  const dw = ((wdT + 180) % 360) * D2R;
  const relN = S.drone.n, relE = S.drone.e;
  const vN = telemetry.vx || 0, vE = telemetry.vy || 0;

  const impN = relN + drift * Math.cos(dw) + vN * tof;
  const impE = relE + drift * Math.sin(dw) + vE * tof;

  const missN = impN - S.target.n, missE = impE - S.target.e;
  const miss = Math.hypot(missN, missE);
  const brg = (Math.atan2(missE, missN) / D2R + 360) % 360;
  const dwn = missN * Math.cos(dw) + missE * Math.sin(dw);
  const crs = missN * Math.cos(dw + Math.PI / 2) + missE * Math.sin(dw + Math.PI / 2);

  S.fall = {
    path, dw, relN, relE, vN, vE, alt, tof,
    t0: performance.now(), impN, impE,
    res: { miss, brg, dwn, crs, tof, drift }, boom: -1,
  };
  status(`Round away — ${tof.toFixed(1)} s to impact…`, 'warn');
  showBanner('ROUND AWAY'); hideBanner(1200);
}

function drawFall() {
  const f = S.fall; if (!f) return;
  const W = VC.width, H = VC.height;
  const now = performance.now();
  const tSec = (now - f.t0) / 1000;
  const zoom = parseFloat(V('zoom'));
  const h = S.drone.hdg * D2R, cs = Math.cos(h), sn = Math.sin(h);

  const project = (n, e, hgt) => {
    const dist = Math.max(6, f.alt - hgt);
    const P = W / (2 * dist) * zoom;
    const dn = n - S.drone.n, de = e - S.drone.e;
    const up = dn * cs + de * sn;
    const rt = dn * Math.cos(h + Math.PI / 2) + de * Math.sin(h + Math.PI / 2);
    return { x: W / 2 + rt * P, y: H / 2 - up * P };
  };
  const at = t => {
    const p = f.path;
    let i = Math.min(p.length - 1, Math.max(0, Math.round(t / f.tof * (p.length - 1))));
    return p[i];
  };

  if (tSec < f.tof) {

    const s = at(tSec);
    const n = f.relN + s.x * Math.cos(f.dw) + f.vN * s.t;
    const e = f.relE + s.x * Math.sin(f.dw) + f.vE * s.t;
    const pt = project(n, e, s.y);
    const frac = s.y / f.alt;
    const size = 3.5 + 17 * frac;

    for (let k = 1; k <= 6; k++) {
      const tt = tSec - k * 0.06; if (tt <= 0) break;
      const q = at(tt);
      const qn = f.relN + q.x * Math.cos(f.dw) + f.vN * q.t;
      const qe = f.relE + q.x * Math.sin(f.dw) + f.vE * q.t;
      const qp = project(qn, qe, q.y);
      const qs = (3.5 + 17 * (q.y / f.alt)) * 0.75;
      vx.fillStyle = `rgba(25,25,30,${0.30 * (1 - k / 7)})`;
      vx.beginPath(); vx.arc(qp.x, qp.y, qs, 0, 7); vx.fill();
    }

    vx.fillStyle = '#1b1b20';
    vx.beginPath(); vx.arc(pt.x, pt.y, size, 0, 7); vx.fill();
    vx.strokeStyle = 'rgba(120,124,134,0.9)'; vx.lineWidth = Math.max(1, size * 0.12);
    vx.stroke();
    vx.fillStyle = 'rgba(190,196,206,0.55)';
    vx.beginPath(); vx.arc(pt.x - size * 0.28, pt.y - size * 0.3, size * 0.32, 0, 7); vx.fill();

    const gp = project(f.impN, f.impE, 0);
    const gs = 2 + 7 * (1 - frac);
    vx.fillStyle = `rgba(0,0,0,${0.35 * (1 - frac)})`;
    vx.beginPath(); vx.ellipse(gp.x, gp.y, gs, gs * 0.75, 0, 0, 7); vx.fill();
    return;
  }

  if (f.boom < 0) {
    f.boom = now;
    bakeImpact(f.impN, f.impE);
    const r = f.res;
    S.lastDrop = r;
    const verdict = r.miss < 2 ? '✔ EXCELLENT' : r.miss < 5 ? '✔ HIT (inside 5 m)' : '✘ MISS';
    const col = r.miss < 5 ? 'var(--gn)' : 'var(--rd)';
    status(`<b style="color:${col}">${verdict}</b> — ${r.miss.toFixed(1)} m @ ${r.brg.toFixed(0)}°<br>` +
           `downwind ${r.dwn >= 0 ? '+' : ''}${r.dwn.toFixed(1)} m · cross ${r.crs.toFixed(1)} m<br>` +
           `TOF ${r.tof.toFixed(1)} s · true drift ${r.drift.toFixed(1)} m`, r.miss < 5 ? 'ok' : 'err');
    showBanner(`${verdict} — ${r.miss.toFixed(1)} m`); hideBanner(3200);
  }
  const k = (now - f.boom) / 1000;
  const gp = project(f.impN, f.impE, 0);
  if (k < 1.3) {

    const e1 = Math.min(1, k / 0.9);
    vx.save();
    for (let i = 0; i < 22; i++) {
      const a = (i / 22) * 7 + (i % 3), d = (4 + 44 * e1) * (0.45 + (i % 5) / 7);
      const rr = 3 + 9 * e1;
      vx.fillStyle = `rgba(168,158,136,${0.42 * (1 - k / 1.3)})`;
      vx.beginPath(); vx.arc(gp.x + Math.cos(a) * d, gp.y + Math.sin(a) * d * 0.72, rr, 0, 7); vx.fill();
    }
    [0, 0.14].forEach((dly, i) => {
      const kk = k - dly; if (kk < 0 || kk > 0.7) return;
      const r = 6 + 70 * (kk / 0.7);
      vx.strokeStyle = `rgba(255,255,255,${0.55 * (1 - kk / 0.7)})`;
      vx.lineWidth = 2.5 - i; vx.beginPath(); vx.arc(gp.x, gp.y, r, 0, 7); vx.stroke();
    });
    vx.restore();
  } else {
    S.fall = null;
  }
}

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
  showStickReadout();
  status('Simulator running. Tap the target to mark it, CALCULATE AIM, fly 🔵 onto 🟢, then SIMULATE DROP.', 'ok');
  tick();
}
function stop() {
  S.on = false;
  S.fall = null;
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

  const mapSel = E('simMap');
  if (mapSel) mapSel.addEventListener('change', () => { S.map = V('simMap'); });
  E('simUp').addEventListener('click', () => nudge(1, 0));
  E('simDown').addEventListener('click', () => nudge(-1, 0));
  E('simLeft').addEventListener('click', () => nudge(0, -1));
  E('simRight').addEventListener('click', () => nudge(0, 1));
  E('simCtr').addEventListener('click', () => {

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
    S.fall = null;
    loadDummy();
    if (S.scene === 'sat') status('Reloaded dummy range — reload your image for satellite mode.', 'warn');
    S.drone.n = (Math.random() - 0.5) * 9;
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
