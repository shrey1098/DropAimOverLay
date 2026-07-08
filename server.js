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
  mavlinkPort: 14445,                            // QGC forwards here
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
    timestamp:null,
  }
};

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
  let rem = Buffer.alloc(0);

  udp.on('message', (msg, ri) => {
    if(!mavState.connected){ console.log(`[MAV] Receiving from ${ri.address}:${ri.port} ✓`); mavState.connected=true; }
    rem = Buffer.concat([rem, msg]);
    rem = parseMav(rem);
    if(rem.length>512) rem=Buffer.alloc(0);
  });
  udp.on('error', e => console.log('[MAV] Error:', e.message));
  udp.bind(CONFIG.mavlinkPort, () => console.log(`[MAV] Listening UDP :${CONFIG.mavlinkPort}`));
}

function parseMav(buf) {
  let i=0;
  while(i<buf.length-8){
    const v2=buf[i]===0xFD, v1=buf[i]===0xFE;
    if(!v1&&!v2){i++;continue;}
    const pl=buf[i+1], hl=v2?10:6, tl=hl+pl+2;
    if(i+tl>buf.length) break;
    const id=v2?(buf[i+7]|buf[i+8]<<8|buf[i+9]<<16):buf[i+5];
    const p=buf.slice(i+hl,i+hl+pl);
    const d=new DataView(p.buffer,p.byteOffset,p.length);
    try{
      if(id===30&&pl>=28){
        mavState.latest.roll=d.getFloat32(4,true)*180/Math.PI;
        mavState.latest.pitch=d.getFloat32(8,true)*180/Math.PI;
        let y=d.getFloat32(12,true)*180/Math.PI;
        mavState.latest.yaw=y<0?y+360:y;
      }else if(id===33&&pl>=28){
        mavState.latest.lat=d.getInt32(4,true)*1e-7;
        mavState.latest.lon=d.getInt32(8,true)*1e-7;
        mavState.latest.altMSL=d.getInt32(12,true)*1e-3;
        mavState.latest.altAGL=d.getInt32(16,true)*1e-3;
        mavState.latest.vx=d.getInt16(20,true)*0.01;
        mavState.latest.vy=d.getInt16(22,true)*0.01;
        mavState.latest.vz=d.getInt16(24,true)*0.01;
        mavState.latest.heading=d.getUint16(26,true)*0.01;
        mavState.latest.groundspeed=Math.sqrt(mavState.latest.vx**2+mavState.latest.vy**2);
      }else if(id===168&&pl>=12){
        mavState.latest.windDir=((d.getFloat32(0,true)*180/Math.PI)+360)%360;
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
