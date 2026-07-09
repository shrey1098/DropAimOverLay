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
const { spawn } = require('child_process');
const path      = require('path');

// ── CONFIG ────────────────────────────────────────────────────────
const CONFIG = {
  port:        3000,
  rtspUrl:     'rtsp://192.168.144.108:554/main',   // local mediamtx
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
};

// ── STATE ─────────────────────────────────────────────────────────
let currentFrame  = null;
const videoState  = { connected: false, ffmpeg: null };
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

// MJPEG HTTP stream — browser uses <img src="/stream"> or fetch()
app.get('/stream', (req, res) => {
  res.writeHead(200, {
    'Content-Type':  'multipart/x-mixed-replace; boundary=mjpegframe',
    'Cache-Control': 'no-cache',
    'Connection':    'keep-alive',
    'Pragma':        'no-cache',
  });
  const iv = setInterval(() => {
    if(currentFrame && !res.destroyed) {
      try {
        res.write('--mjpegframe\r\n');
        res.write('Content-Type: image/jpeg\r\n');
        res.write(`Content-Length: ${currentFrame.length}\r\n\r\n`);
        res.write(currentFrame);
        res.write('\r\n');
      } catch(e) {}
    }
  }, Math.round(1000 / CONFIG.videoFps));
  req.on('close', () => clearInterval(iv));
});

app.get('/api/status', (req, res) => {
  res.json({ video: videoState.connected, mavlink: mavState.connected });
});

// Flight-mode command (LOCK → BRAKE, UNLOCK → LOITER). Only these two are allowed.
app.use(express.json());
const ALLOWED_MODES = { BRAKE:17, LOITER:5 };
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
function startVideo() {
  if(videoState.ffmpeg) return;
  console.log('[VIDEO] Starting ffmpeg...');

  const ff = spawn('ffmpeg', [
    '-loglevel', 'error',
    '-rtsp_transport', 'tcp',
    '-i', CONFIG.rtspUrl,
    '-f', 'image2pipe',
    '-vf', `fps=${CONFIG.videoFps},scale=${CONFIG.videoWidth}:${CONFIG.videoHeight}`,
    '-vcodec', 'mjpeg',
    '-q:v', String(CONFIG.videoQuality),
    'pipe:1',
  ]);
  videoState.ffmpeg = ff;
  let buf = Buffer.alloc(0);

  ff.stdout.on('data', chunk => {
    videoState.connected = true;
    buf = Buffer.concat([buf, chunk]);
    let s = -1;
    for(let i=0;i<buf.length-1;i++){
      if(buf[i]===0xFF&&buf[i+1]===0xD8) s=i;
      if(s!==-1&&buf[i]===0xFF&&buf[i+1]===0xD9){
        currentFrame=buf.slice(s,i+2);
        buf=buf.slice(i+2); s=-1; i=-1; break;
      }
    }
    if(buf.length>5e6) buf=Buffer.alloc(0);
  });

  ff.stderr.on('data', d => { const m=d.toString().trim(); if(m) console.log('[ffmpeg]',m); });
  ff.on('close', c => { console.log(`[VIDEO] ffmpeg exited (${c}). Retry 3s...`); videoState.connected=false; videoState.ffmpeg=null; setTimeout(startVideo,3000); });
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
