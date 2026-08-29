/*
 * DROP·AIM Overlay Server — G20 Local Edition
 * =============================================
 * Runs entirely on G20 controller via Termux.
 *
 * Video:     mediamtx (local) re-streams C20 RTSP
 *            ffmpeg pulls from mediamtx → MJPEG → served at /stream
 * Telemetry: QGC forwards MAVLink UDP to localhost:14445
 * Browser:   G20 Chrome opens localhost:3000
 *
 * No laptop, no WiFi hops, no IP configuration needed.
 */

'use strict';

const express   = require('express');
const http      = require('http');
const WebSocket = require('ws');
const dgram     = require('dgram');
const net       = require('net');
const { EventEmitter } = require('events');
const { spawn } = require('child_process');
const path      = require('path');

// ── CONFIG ────────────────────────────────────────────────────────
const CONFIG = {
  port:        3000,
  // The sensors this airframe might carry — kept in step with Config.kt.
  // zoom is the aim-solution scale (pxPerM = width/(2*alt)*zoom), not a camera
  // control: the two sensors have different optics, so each carries its own and
  // switching sensors switches it. calibrated:false means the figure is a
  // placeholder and must be measured against a known ground distance first.
  // Add credentials inline if the camera demands them:
  //   'rtsp://admin:pass@192.168.144.108:554/stream=1'
  // zoom = 1/tan(HFOV/2), so a fixed-lens sensor is NOT zoom 0 — zero makes
  // pixels-per-metre zero and the aim point undefined. HFOV 50/40/32/25 deg
  // give 2.14/2.75/3.49/4.51. Thermal is 1 as a flagged placeholder.
  // Two thermal models are fielded on identical URLs — C12 384x288 and C13
  // 640x512 — and the Android build tells them apart from the resolution the
  // stream reports, applying that variant's zoom. This Node build has no such
  // callback from ffmpeg, so it uses the camera default; the browser version is
  // for bench work, and thermal aim needs calibrating there by hand.
  cameras: [
    { id:'day',     label:'DAY',     url:'rtsp://192.168.144.108:554/stream=1', zoom:22, calibrated:true  },
    { id:'thermal', label:'THERMAL', url:'rtsp://192.168.144.108:555/stream=2', zoom:1,  calibrated:false,
      variants: [
        { model:'C12', width:384, height:288, zoom:1, calibrated:false },
        { model:'C13', width:640, height:512, zoom:1, calibrated:false },
      ] },
  ],
  metricsUrl: '',
  mavlinkPort: 14551,
  qgcPort:     14550,                            // QGC forwards here
  targetSys:   1,    // flight controller system id
  targetComp:  1,    // flight controller (autopilot) component id
  gcsSys:      255,  // our identity when sending commands (standard GCS sysid)
  gcsComp:     190,
  videoWidth:  854,
  videoHeight: 480,
  videoFps:    15,
  videoQuality: 5,   // MJPEG quality (1=best, 31=worst)
  streamMaxBacklog: 256 * 1024,  // bytes queued per viewer before frames are dropped
};

// ── STATE ─────────────────────────────────────────────────────────
let currentFrame  = null;
// Frames are pushed to viewers the instant they arrive rather than polled on a
// timer — a timer adds up to one frame-interval of latency and re-sends stale
// frames when the camera stalls.
const frameBus    = new EventEmitter();
frameBus.setMaxListeners(0);
const videoState  = { connected: false, ffmpeg: null, activeUrl: '', lastError: '', attempt: 0, camera: 0 };
// The SELECTED sensor over TCP first, then UDP. TCP traverses the datalink more
// reliably, but some cameras only implement UDP — pinning the transport on its
// own was enough to make a working camera look dead. Only the selected sensor is
// tried: falling through to the other one would show the operator a different
// field of view than the aim solution is computed for.
function videoAttempts() {
  const c = CONFIG.cameras[videoState.camera];
  return c ? [{ url:c.url, transport:'tcp' }, { url:c.url, transport:'udp' }] : [];
}
const mavState    = {
  connected: false,
  latest: {
    roll:0, pitch:0, yaw:0,
    lat:null, lon:null,
    altAGL:null, altMSL:null,
    groundspeed:0, vx:0, vy:0, vz:0,
    heading:0,
    windSpeed:null, windDir:null,
    mode:null,          // current flight mode name (from HEARTBEAT custom_mode)
    timestamp:null,
  }
};

// Copter custom_mode → name (only the ones we care about shown; others as raw)
const COPTER_MODES = {0:'STABILIZE',1:'ACRO',2:'ALTHOLD',3:'AUTO',4:'GUIDED',5:'LOITER',6:'RTL',7:'CIRCLE',9:'LAND',16:'POSHOLD',17:'BRAKE',18:'THROW',20:'GUIDED_NOGPS',21:'SMART_RTL'};

// UDP command TX state (populated by startMavlink)
let mavSocket = null;      // the bound dgram socket
let mavDest   = null;      // {address,port} of the datalink — where we send commands
let txSeq     = 0;

// ── EXPRESS ───────────────────────────────────────────────────────
const app    = express();
const server = http.createServer(app);

app.use(express.static(path.join(__dirname, 'public')));
// Before the routes, not after them: Express runs middleware in registration
// order, so a body parser added below a POST handler never runs for it and that
// handler sees req.body undefined.
app.use(express.json());

// MJPEG HTTP stream — browser uses <img src="/stream"> or fetch()
app.get('/stream', (req, res) => {
  res.writeHead(200, {
    'Content-Type':  'multipart/x-mixed-replace; boundary=mjpegframe',
    'Cache-Control': 'no-cache',
    'Connection':    'keep-alive',
    'Pragma':        'no-cache',
  });
  // Nagle batches small writes — that delay is visible as video lag.
  if(res.socket) res.socket.setNoDelay(true);

  const send = frame => {
    if(res.destroyed) return;
    // Back-pressure: if the socket is already behind, DROP this frame instead
    // of queueing it. Queued frames are what turn a small delay into a lag
    // that grows for as long as the app is left running.
    if(res.writableLength > CONFIG.streamMaxBacklog) return;
    try {
      res.write('--mjpegframe\r\n');
      res.write('Content-Type: image/jpeg\r\n');
      res.write(`Content-Length: ${frame.length}\r\n\r\n`);
      res.write(frame);
      res.write('\r\n');
    } catch(e) {}
  };

  if(currentFrame) send(currentFrame);   // paint immediately, don't wait
  frameBus.on('frame', send);
  req.on('close', () => frameBus.removeListener('frame', send));
});

app.get('/api/status', (req, res) => {
  res.json({
    video: videoState.connected,
    mavlink: mavState.connected,
    videoUrl: videoState.activeUrl || '',
    videoErr: videoState.lastError || '',
  });
});

// ── SENSOR SELECT ─────────────────────────────────────────────────
// Kept in step with the Android build so index.html behaves the same in a
// browser. No DESCRIBE probe here: ffmpeg discovers a missing sensor by failing,
// so every configured camera is offered.
app.get('/api/cameras', (req, res) => {
  res.json({
    cameras: CONFIG.cameras.map((c, index) => ({ index, ...c, present: true })),
    active: (CONFIG.cameras[videoState.camera] || {}).id || '',
    detected: false,
  });
});

app.post('/api/camera', (req, res) => {
  const i = req.body && Number(req.body.index);
  const c = CONFIG.cameras[i];
  if(!c) return res.status(400).json({ ok:false, err:'no such camera' });
  if(i !== videoState.camera){
    videoState.camera = i;
    videoState.attempt = 0;
    videoState.connected = false;
    currentFrame = null;              // don't leave the other sensor's frame up
    console.log(`[VIDEO] switching to ${c.label} (${c.url})`);
    if(videoState.ffmpeg){ try { videoState.ffmpeg.kill('SIGKILL'); } catch(e) {} }
    // ffmpeg's close handler restarts it, which will pick up the new selection.
  }
  res.json({ ok:true, id:c.id, label:c.label, zoom:c.zoom, calibrated:c.calibrated });
});

// ── SETTINGS ──────────────────────────────────────────────────────
// The handful of values that differ between ground stations and airframes —
// which MAVLink port telemetry arrives on, which port QGC is using, and the
// camera URLs. Mirrors Settings.kt so index.html drives both builds.
//
// Unlike the Android build these are NOT persisted: this Node server is the
// bench harness, its config lives in the source file above, and a settings file
// that silently overrode it would be a trap. The UI is told so.
const DEFAULTS = {
  mavlinkPort: CONFIG.mavlinkPort,
  qgcPort:     CONFIG.qgcPort,
  cameraUrls:  CONFIG.cameras.map(c => c.url),
};

function settingsJson(extra) {
  return Object.assign({
    platform: 'node',
    persisted: false,
    mavlinkPort: CONFIG.mavlinkPort, defaultMavlinkPort: DEFAULTS.mavlinkPort,
    qgcPort: CONFIG.qgcPort,         defaultQgcPort: DEFAULTS.qgcPort,
    // Bluetooth telemetry is an Android capability; the bench build is UDP only
    // and says so by offering no devices rather than pretending to have them.
    telemetrySource: 'udp',
    bluetoothAddress: '',
    bluetoothDevices: [],
    // Uploading is an Android-only path; the bench build reports it as off
    // rather than pretending to have a collector.
    metricsUrl: CONFIG.metricsUrl || '',
    defaultMetricsUrl: '',
    metricsEnabled: false,
    cameras: CONFIG.cameras.map((c, i) => ({
      id: c.id, label: c.label, url: c.url,
      defaultUrl: DEFAULTS.cameraUrls[i],
      overridden: c.url !== DEFAULTS.cameraUrls[i],
    })),
  }, extra || {});
}

// Same rules as Settings.kt — an operator switching between the two builds must
// not find one accepting what the other rejects.
function validateSettings(mav, qgc, urls) {
  if(!Number.isInteger(mav) || mav < 1024 || mav > 65535) return 'MAVLink port must be between 1024 and 65535';
  if(!Number.isInteger(qgc) || qgc < 1024 || qgc > 65535) return 'QGC port must be between 1024 and 65535';
  // Both are UDP ports on this machine; the same number for each would mean
  // relaying telemetry straight back into our own socket.
  if(mav === qgc) return `MAVLink and QGC ports must differ (both are ${mav})`;
  for(const [id, url] of Object.entries(urls || {})){
    if(!CONFIG.cameras.some(c => c.id === id)) return `unknown camera '${id}'`;
    const u = String(url).trim();
    if(!u) continue;                                  // cleared = back to the built-in URL
    if(!u.startsWith('rtsp://')) return `camera '${id}' URL must start with rtsp://`;
    try { const p = new URL(u); if(!p.hostname) throw 0; }
    catch(e){ return `camera '${id}' URL is not a valid address`; }
  }
  return null;
}

function restartMavlink() {
  console.log(`[MAV] Restarting on :${CONFIG.mavlinkPort} → QGC :${CONFIG.qgcPort}`);
  mavState.connected = false;
  mavDest = null;
  try { if(mavSocket) mavSocket.close(); } catch(e) {}
  mavSocket = null;
  // Give the old socket a moment to release the port it was holding.
  setTimeout(startMavlink, 200);
}

function restartVideo() {
  console.log('[VIDEO] Restarting with current settings');
  videoState.attempt = 0;
  videoState.connected = false;
  videoState.lastError = '';
  currentFrame = null;
  // ffmpeg's close handler restarts it, which picks up the new URL.
  if(videoState.ffmpeg){ try { videoState.ffmpeg.kill('SIGKILL'); } catch(e) {} }
  else startVideo();
}

app.get('/api/settings', (req, res) => res.json(settingsJson()));

// The bench build has no uploader — say so rather than reporting a pass that
// means nothing.
app.post('/api/metrics/test', (req, res) => res.json({
  ok:false, attempted:false, sent:0, tokenSet:false,
  detail:'Uploading is an Android-only path; this browser build has no uploader to test.' }));

app.post('/api/settings', (req, res) => {
  const b = req.body || {};
  const oldMav = CONFIG.mavlinkPort, oldQgc = CONFIG.qgcPort;
  const oldUrls = CONFIG.cameras.map(c => c.url);

  if(b.reset){
    CONFIG.mavlinkPort = DEFAULTS.mavlinkPort;
    CONFIG.qgcPort     = DEFAULTS.qgcPort;
    CONFIG.cameras.forEach((c, i) => { c.url = DEFAULTS.cameraUrls[i]; });
  } else {
    const mav = b.mavlinkPort === undefined ? CONFIG.mavlinkPort : Number(b.mavlinkPort);
    const qgc = b.qgcPort     === undefined ? CONFIG.qgcPort     : Number(b.qgcPort);
    const err = validateSettings(mav, qgc, b.cameras);
    if(err) return res.status(400).json({ ok:false, err });
    if(b.metricsUrl !== undefined){
      const mu = String(b.metricsUrl).trim();
      if(mu && !mu.startsWith('https://'))
        return res.status(400).json({ ok:false, err:'metrics URL must start with https://' });
      CONFIG.metricsUrl = mu;
    }
    if(b.telemetrySource && b.telemetrySource !== 'udp')
      return res.status(400).json({ ok:false, err:'this build supports UDP telemetry only — Bluetooth is Android' });
    // Measured aim scales, saved per sensor. Merged, not replaced: saving the
    // day camera must not wipe a measured thermal figure.
    if(b.cameraZooms) for(const [id, z] of Object.entries(b.cameraZooms)){
      const c = CONFIG.cameras.find(x=>x.id===id);
      if(!c) return res.status(400).json({ ok:false, err:`unknown camera '${id}'` });
      const v = Number(z);
      if(!Number.isFinite(v) || v <= 0) return res.status(400).json({ ok:false, err:`camera '${id}' zoom must be greater than 0` });
      if(v > 100) return res.status(400).json({ ok:false, err:`camera '${id}' zoom looks wrong (${v}) — expected 0.5 to 100` });
      c.zoom = v; c.calibrated = true; c.zoomSet = true;
    }
    CONFIG.mavlinkPort = mav;
    CONFIG.qgcPort     = qgc;
    if(b.cameras) CONFIG.cameras.forEach((c, i) => {
      if(!(c.id in b.cameras)) return;
      const u = String(b.cameras[c.id]).trim();
      c.url = u || DEFAULTS.cameraUrls[i];
    });
  }

  const portsChanged = CONFIG.mavlinkPort !== oldMav || CONFIG.qgcPort !== oldQgc;
  const urlsChanged  = CONFIG.cameras.some((c, i) => c.url !== oldUrls[i]);
  if(portsChanged) restartMavlink();
  if(urlsChanged)  restartVideo();

  console.log(`[CFG] Settings saved (ports changed=${portsChanged} urls changed=${urlsChanged})`);
  res.json(settingsJson({ ok:true, mavlinkRestarted:portsChanged, videoRestarted:urlsChanged }));
});

// ── MAVLINK SCAN ──────────────────────────────────────────────────
// Mirrors MavScan.kt so the same UI drives both builds, and so the scan can be
// exercised against a known sender on the bench before it is trusted in the
// field. READ-ONLY: opens its own sockets, looks, closes them.
const SCAN_PORTS = [19856, 19857, 14550, 14551, 14552, 14553, 14555, 14556, 14445, 15550, 18570];
const SCAN_MS = 6000;

/** MAVLink v1/v2 frames in a buffer -> the system and message ids they carry. */
function scanDecode(b, sysIds, msgIds) {
  let i = 0;
  while (i < b.length - 8) {
    const v2 = b[i] === 0xFD, v1 = b[i] === 0xFE;
    if (!v1 && !v2) { i++; continue; }
    const hl = v2 ? 10 : 6, total = hl + b[i + 1] + 2;
    if (i + total > b.length) break;
    sysIds.add(v2 ? b[i + 5] : b[i + 3]);
    msgIds.add(v2 ? (b[i + 7] | (b[i + 8] << 8) | (b[i + 9] << 16)) : b[i + 5]);
    i += total;
  }
}

function scanInterfaces() {
  const os = require('os'), out = [];
  const ifs = os.networkInterfaces();
  for (const name of Object.keys(ifs)) {
    const addrs = (ifs[name] || []).filter(a => a.family === 'IPv4' && !a.internal).map(a => a.address);
    if (addrs.length) out.push({ name, up: true, addresses: addrs });
  }
  return out;
}

/** Hex address -> dotted quad, or a readable IPv6. */
function scanIp(hex) {
  try {
    if (hex.length === 8) return [3,2,1,0].map(k => parseInt(hex.substr(k*2,2),16)).join('.');
    if (hex.length === 32) {
      if (/^0000000000000000FFFF0000/i.test(hex)) return scanIp(hex.slice(24));
      if (/^0+$/.test(hex)) return '::';
      const parts = [];
      for (let w = 0; w < 4; w++) {
        const word = hex.substr(w*8, 8);
        let be = ''; for (let k = 3; k >= 0; k--) be += word.substr(k*2, 2);
        parts.push((be.slice(0,4).replace(/^0+/,'') || '0') + ':' + (be.slice(4,8).replace(/^0+/,'') || '0'));
      }
      return parts.join(':');
    }
  } catch (e) {}
  return hex;
}

/** Every socket on this machine, UDP and TCP, with the REMOTE end kept — a far
 *  end on the datalink's subnet is what identifies the telemetry path. */
function scanProcNet() {
  const out = [];
  const files = [['/proc/net/udp','UDP'],['/proc/net/udp6','UDP6'],
                 ['/proc/net/tcp','TCP'],['/proc/net/tcp6','TCP6']];
  for (const [path, proto] of files) {
    try {
      const lines = require('fs').readFileSync(path, 'utf8').split('\n').slice(1);
      for (const line of lines) {
        const c = line.trim().split(/\s+/);
        if (c.length < 8) continue;
        const lp = c[1].split(':'), rp = c[2].split(':');
        if (lp.length !== 2 || rp.length !== 2) continue;
        const localPort = parseInt(lp[1], 16), remotePort = parseInt(rp[1], 16);
        if (!Number.isFinite(localPort)) continue;
        out.push({ proto, localAddr: scanIp(lp[0]), localPort,
                   remoteAddr: remotePort ? scanIp(rp[0]) : '', remotePort: remotePort || 0,
                   state: c[3], uid: parseInt(c[7],10) });
      }
    } catch (e) { out.push({ error: `${path}: ${e.message}` }); }
  }
  return out;
}

function scanListen(cb) {
  const rows = [];
  let pending = SCAN_PORTS.length;
  const done = () => { if (--pending === 0) cb(rows.sort((a,b)=>(b.packets||0)-(a.packets||0))); };
  for (const port of SCAN_PORTS) {
    const row = { port, bound:false, packets:0, bytes:0, sources:[], mavlink:false, sysIds:[], msgIds:[] };
    const sysIds = new Set(), msgIds = new Set(), sources = new Set();
    const s = dgram.createSocket({ type:'udp4', reuseAddr:true });
    let finished = false;
    const finish = () => {
      if (finished) return; finished = true;
      row.sources=[...sources]; row.sysIds=[...sysIds]; row.msgIds=[...msgIds];
      row.mavlink = msgIds.size > 0;
      try { s.close(); } catch(e) {}
      rows.push(row); done();
    };
    s.on('error', e => { row.error = e.message; finish(); });
    s.on('message', (msg, ri) => {
      row.packets++; row.bytes += msg.length;
      sources.add(`${ri.address}:${ri.port}`);
      scanDecode(msg, sysIds, msgIds);
    });
    s.bind(port, () => {
      row.bound = true;
      try { s.setBroadcast(true); } catch(e) {}
      setTimeout(finish, SCAN_MS);
    });
  }
}

app.get('/api/mavscan', (req, res) => {
  console.log('[SCAN] listening on', SCAN_PORTS.join(', '), `for ${SCAN_MS}ms`);
  scanListen(listened => {
    res.json({
      platform: 'node',
      listenMs: SCAN_MS,
      multicastLock: false,               // Android-only concept
      interfaces: scanInterfaces(),
      sockets: scanProcNet(),
      listened,
      appMavlinkPort: CONFIG.mavlinkPort,
      myUid: typeof process.getuid === 'function' ? process.getuid() : -1,
    });
  });
});

// TRANSMITS. Sends one standard GCS heartbeat per endpoint and listens for a
// reply. Many MAVLink UDP endpoints are servers that stay silent until a client
// speaks first — if that is the case here, no port is "missing", we have simply
// never introduced ourselves. Reached only from a button that says so.
const PROBE_HOSTS = ['192.168.144.11', '192.168.144.12', '192.168.144.255'];
const PROBE_PORTS = [19856, 14550, 14551, 14555];
const PROBE_WAIT_MS = 3000;

function probeHeartbeat() {
  const pl = Buffer.alloc(9);
  pl[4]=6; pl[5]=8; pl[6]=0; pl[7]=4; pl[8]=3;      // GCS, invalid autopilot, active
  const hdr = Buffer.alloc(10);
  hdr[0]=0xFD; hdr[1]=pl.length; hdr[5]=CONFIG.gcsSys; hdr[6]=CONFIG.gcsComp;
  let c=0xFFFF;
  for(let k=1;k<10;k++) c=crcAccum(hdr[k],c);
  for(const b of pl) c=crcAccum(b,c);
  c=crcAccum(50,c);                                  // HEARTBEAT crc_extra
  return Buffer.concat([hdr,pl,Buffer.from([c&0xFF,(c>>8)&0xFF])]);
}

app.get('/api/mavprobe', (req, res) => {
  const hb = probeHeartbeat();
  const targets = [];
  for (const host of PROBE_HOSTS) for (const port of PROBE_PORTS) targets.push({host, port});
  console.log(`[PROBE] heartbeat -> ${targets.length} endpoints`);
  let pending = targets.length;
  const rows = [];
  const done = () => { if(--pending === 0) res.json({ platform:'node', waitMs:PROBE_WAIT_MS, probed:rows }); };
  for (const t of targets) {
    const row = { host:t.host, port:t.port, sent:false, replies:0, sources:[], mavlink:false, sysIds:[], msgIds:[] };
    const sysIds=new Set(), msgIds=new Set(), sources=new Set();
    const s = dgram.createSocket({type:'udp4', reuseAddr:true});
    let finished=false;
    const finish = () => {
      if(finished) return; finished=true;
      row.sources=[...sources]; row.sysIds=[...sysIds]; row.msgIds=[...msgIds];
      row.mavlink = msgIds.size>0;
      try{ s.close(); }catch(e){}
      rows.push(row); done();
    };
    s.on('error', e => { row.error=e.message; finish(); });
    s.on('message', (msg, ri) => { row.replies++; sources.add(`${ri.address}:${ri.port}`); scanDecode(msg,sysIds,msgIds); });
    s.bind(0, () => {
      row.localPort = s.address().port;
      try { s.setBroadcast(true); } catch(e) {}
      const send = () => { try { s.send(hb, t.port, t.host, e => { if(!e) row.sent=true; }); } catch(e) { row.error=e.message; } };
      send(); setTimeout(send, 1000);
      setTimeout(finish, PROBE_WAIT_MS);
    });
  }
});

// Flight-mode command (LOCK → BRAKE, UNLOCK → LOITER). Only these two are allowed.
const ALLOWED_MODES = { BRAKE:17, LOITER:5, RTL:6 };
app.post('/api/mode', (req, res) => {
  const name = (req.body && req.body.mode || '').toUpperCase();
  const cm = ALLOWED_MODES[name];
  if(cm === undefined) return res.status(400).json({ ok:false, err:'mode must be BRAKE or LOITER' });
  if(!mavSocket || !mavDest) return res.status(503).json({ ok:false, err:'no telemetry link yet — cannot reach flight controller' });
  sendModeCommand(cm);
  console.log(`[CMD] Sent DO_SET_MODE ${name} (${cm}) → ${mavDest.address}:${mavDest.port}`);
  res.json({ ok:true, mode:name });
});

// ── WEBSOCKET (telemetry only) ────────────────────────────────────
const wsTelem = new WebSocket.Server({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  if(req.url === '/telemetry') {
    wsTelem.handleUpgrade(req, socket, head, ws => wsTelem.emit('connection', ws, req));
  } else {
    socket.destroy();
  }
});

wsTelem.on('connection', ws => {
  console.log('[TELEM] Client connected');
  ws.send(JSON.stringify({ ...mavState.latest, videoOk: videoState.connected, mavlinkOk: mavState.connected }));
  ws.on('error', () => {});
});

setInterval(() => {
  const p = JSON.stringify({ ...mavState.latest, videoOk: videoState.connected, mavlinkOk: mavState.connected });
  wsTelem.clients.forEach(c => { if(c.readyState === WebSocket.OPEN) try { c.send(p); } catch(e) {} });
}, 200);

// ── VIDEO: RTSP → MJPEG ───────────────────────────────────────────
const SOI = Buffer.from([0xFF, 0xD8]);   // JPEG start-of-image
const EOI = Buffer.from([0xFF, 0xD9]);   // JPEG end-of-image

// Can we open a TCP socket to the camera at all? Separates "unreachable host"
// (wrong IP, not on the camera's network) from "reachable but RTSP refused"
// (wrong path, auth, codec) — two faults with entirely different fixes.
function probe(url, cb){
  let host, port;
  try { const u = new URL(url); host = u.hostname; port = u.port ? +u.port : 554; }
  catch(e){ return cb(true); }               // unparseable — let ffmpeg say why
  const s = new net.Socket();
  let done = false;
  const finish = ok => { if(done) return; done = true; s.destroy(); cb(ok, host, port); };
  s.setTimeout(2000);
  s.once('connect', () => finish(true));
  s.once('timeout', () => finish(false));
  s.once('error',   () => finish(false));
  s.connect(port, host);
}

function startVideo() {
  if(videoState.ffmpeg) return;
  const list = videoAttempts();
  if(!list.length){ console.log('[VIDEO] no camera configured'); return; }
  const a = list[videoState.attempt % list.length];
  const label = `${a.url} [${a.transport.toUpperCase()}]`;
  videoState.activeUrl = label;
  probe(a.url, (ok, host, port) => {
    if(!ok){
      const msg = `Cannot reach ${host}:${port} — nothing is answering there.`;
      console.log(`[VIDEO] UNREACHABLE ${label} — ${msg}`);
      videoState.lastError = msg;
      videoState.attempt++;
      return setTimeout(startVideo, 1500);
    }
    spawnFfmpeg(a, label);
  });
}

function spawnFfmpeg(a, label) {
  if(videoState.ffmpeg) return;
  const n = videoAttempts().length || 1;
  console.log(`[VIDEO] Trying ${label} (candidate ${videoState.attempt % n + 1}/${n})`);

  const ff = spawn('ffmpeg', [
    '-loglevel', 'error',
    // ── low-latency input ──────────────────────────────────────────
    // Default ffmpeg buffers/probes the stream before emitting anything,
    // which shows up as a fixed delay on the live feed.
    '-fflags', 'nobuffer',
    '-flags', 'low_delay',
    '-probesize', '32',
    '-analyzeduration', '0',
    '-rtsp_transport', a.transport,
    '-i', a.url,
    '-an',                      // no audio path at all
    '-f', 'image2pipe',
    '-vf', `fps=${CONFIG.videoFps},scale=${CONFIG.videoWidth}:${CONFIG.videoHeight}`,
    '-vcodec', 'mjpeg',
    '-q:v', String(CONFIG.videoQuality),
    '-flush_packets', '1',      // emit each JPEG as soon as it is encoded
    'pipe:1',
  ]);
  videoState.ffmpeg = ff;
  let buf = Buffer.alloc(0);

  ff.stdout.on('data', chunk => {
    if(!videoState.connected){ console.log(`[VIDEO] PLAYING ${label}`); videoState.lastError=''; }
    videoState.connected = true;
    buf = Buffer.concat([buf, chunk]);
    // Drain EVERY complete JPEG in the buffer and keep only the newest. The
    // previous version stopped after the first frame, so whatever else had
    // already arrived sat in the buffer and was shown one chunk late —
    // a backlog that compounds into steadily worsening lag.
    let latest = null, from = 0;
    for(;;){
      const s = buf.indexOf(SOI, from);
      if(s < 0) break;
      const e = buf.indexOf(EOI, s + 2);
      if(e < 0) break;
      latest = buf.slice(s, e + 2);
      from = e + 2;
    }
    if(latest){
      buf = buf.slice(from);          // discard the stale frames we skipped
      currentFrame = latest;
      frameBus.emit('frame', latest); // push to viewers immediately
    }
    if(buf.length>5e6) buf=Buffer.alloc(0);
  });

  ff.stderr.on('data', d => {
    const m=d.toString().trim();
    if(m){ console.log('[ffmpeg]',m); videoState.lastError = `${label} — ${m.split('\n').pop()}`; }
  });
  ff.on('close', c => {
    // Only advance to the next candidate if this one never produced a frame.
    // A stream that was working and then dropped should be retried as-is.
    const played = videoState.connected;
    console.log(`[VIDEO] ${label} exited (${c}).${played ? '' : ' Trying next candidate.'} Retry 3s...`);
    if(!played) videoState.attempt++;
    videoState.connected=false; videoState.ffmpeg=null;
    setTimeout(startVideo,3000);
  });
  ff.on('error', e => console.error(e.code==='ENOENT'?'[ERR] ffmpeg not found':'[ERR] '+e.message));
}

// ── MAVLINK: UDP ──────────────────────────────────────────────────
function startMavlink() {
  const udp = dgram.createSocket('udp4');
  mavSocket = udp;
  let rem = Buffer.alloc(0);
  let datalink = null; // last source the telemetry arrived from (for uplink return path)

  udp.on('message', (msg, ri) => {
    // Uplink: QGC → drone (params, mode changes, missions) — pass straight back
    if(ri.port === CONFIG.qgcPort && ri.address === '127.0.0.1') {
      if(datalink) udp.send(msg, datalink.port, datalink.address);
      return;
    }
    // Downlink: datalink → us. Parse for the app, then relay to QGC.
    datalink = ri;
    mavDest = ri;          // reply here when we send flight-mode commands
    if(!mavState.connected){ console.log(`[MAV] Receiving from ${ri.address}:${ri.port} ✓`); mavState.connected=true; }
    udp.send(msg, CONFIG.qgcPort, '127.0.0.1');
    rem = Buffer.concat([rem, msg]);
    rem = parseMav(rem);
    if(rem.length>512) rem=Buffer.alloc(0);
  });
  udp.on('error', e => console.log('[MAV] Error:', e.message));
  udp.bind(CONFIG.mavlinkPort, () => console.log(`[MAV] Listening UDP :${CONFIG.mavlinkPort} → relaying to QGC :${CONFIG.qgcPort}`));
}

// ── MAVLINK TX: build + send a v2 COMMAND_LONG (CRC verified in tests) ─────
function crcAccum(byte, crc){
  let tmp = byte ^ (crc & 0xFF);
  tmp = (tmp ^ (tmp << 4)) & 0xFF;
  return ((crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)) & 0xFFFF;
}
const CRC_EXTRA_COMMAND_LONG = 152;   // msgid 76
function sendModeCommand(customMode){
  // COMMAND_LONG payload: 7×float params, uint16 command, target_sys, target_comp, confirmation
  const payload = Buffer.alloc(33);
  payload.writeFloatLE(1, 0);            // param1 = base_mode = MAV_MODE_FLAG_CUSTOM_MODE_ENABLED
  payload.writeFloatLE(customMode, 4);   // param2 = custom_mode
  payload.writeUInt16LE(176, 28);        // command = MAV_CMD_DO_SET_MODE
  payload[30] = CONFIG.targetSys;        // target_system
  payload[31] = CONFIG.targetComp;       // target_component
  payload[32] = 0;                       // confirmation
  const hdr = Buffer.alloc(10);
  hdr[0]=0xFD; hdr[1]=payload.length; hdr[2]=0; hdr[3]=0;
  hdr[4]=txSeq=(txSeq+1)&0xFF; hdr[5]=CONFIG.gcsSys; hdr[6]=CONFIG.gcsComp;
  hdr[7]=76&0xFF; hdr[8]=(76>>8)&0xFF; hdr[9]=(76>>16)&0xFF;
  let c=0xFFFF;
  for(let i=1;i<10;i++) c=crcAccum(hdr[i],c);
  for(const b of payload) c=crcAccum(b,c);
  c=crcAccum(CRC_EXTRA_COMMAND_LONG,c);
  const crc=Buffer.alloc(2); crc.writeUInt16LE(c,0);
  const pkt=Buffer.concat([hdr,payload,crc]);
  // Send twice — UDP is lossy and a dropped mode command should not silently no-op
  mavSocket.send(pkt, mavDest.port, mavDest.address);
  mavSocket.send(pkt, mavDest.port, mavDest.address);
}

function parseMav(buf) {
  let i=0;
  while(i<buf.length-8){
    const v2=buf[i]===0xFD, v1=buf[i]===0xFE;
    if(!v1&&!v2){i++;continue;}
    const pl=buf[i+1], hl=v2?10:6, tl=hl+pl+2;
    if(i+tl>buf.length) break;
    const id=v2?(buf[i+7]|buf[i+8]<<8|buf[i+9]<<16):buf[i+5];
    const sysid=v2?buf[i+5]:buf[i+3];
    // MAVLink v2 truncates trailing zero bytes from payloads; pad back to full
    // length so truncated fields read as zero (their spec-defined value).
    const p=Buffer.alloc(28);
    buf.copy(p,0,i+hl,i+hl+Math.min(pl,28));
    const d=new DataView(p.buffer,p.byteOffset,p.length);
    try{
      if(id===0&&pl>=6){
        // HEARTBEAT: custom_mode (uint32) at offset 0 = Copter flight mode number.
        // Only trust the vehicle's heartbeat — QGC/GCS also emit HEARTBEAT.
        if(sysid===CONFIG.targetSys){
          const cm=d.getUint32(0,true);
          mavState.latest.mode=COPTER_MODES[cm]||('MODE'+cm);
        }
      }else if(id===30&&pl>=16){
        mavState.latest.roll=d.getFloat32(4,true)*180/Math.PI;
        mavState.latest.pitch=d.getFloat32(8,true)*180/Math.PI;
        let y=d.getFloat32(12,true)*180/Math.PI;
        mavState.latest.yaw=y<0?y+360:y;
      }else if(id===33&&pl>=18){
        mavState.latest.lat=d.getInt32(4,true)*1e-7;
        mavState.latest.lon=d.getInt32(8,true)*1e-7;
        mavState.latest.altMSL=d.getInt32(12,true)*1e-3;
        mavState.latest.altAGL=d.getInt32(16,true)*1e-3;
        mavState.latest.vx=d.getInt16(20,true)*0.01;
        mavState.latest.vy=d.getInt16(22,true)*0.01;
        mavState.latest.vz=d.getInt16(24,true)*0.01;
        mavState.latest.heading=d.getUint16(26,true)*0.01;
        mavState.latest.groundspeed=Math.sqrt(mavState.latest.vx**2+mavState.latest.vy**2);
      }else if(id===168&&pl>=5){
        mavState.latest.windDir=((d.getFloat32(0,true)%360)+360)%360;
        mavState.latest.windSpeed=d.getFloat32(4,true);
      }
    }catch(e){}
    mavState.latest.timestamp=Date.now();
    i+=tl;
  }
  return buf.slice(i);
}

// ── START ─────────────────────────────────────────────────────────
server.listen(CONFIG.port, () => {
  console.log(`\n╔═══════════════════════════════════╗`);
  console.log(`║  DROP·AIM — G20 Local Edition     ║`);
  console.log(`║  http://localhost:${CONFIG.port}           ║`);
  console.log(`╚═══════════════════════════════════╝\n`);
  startVideo();
  startMavlink();
});

process.on('SIGINT', () => { if(videoState.ffmpeg) videoState.ffmpeg.kill(); server.close(()=>process.exit(0)); });
