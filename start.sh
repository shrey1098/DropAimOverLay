#!/data/data/com.termux/files/usr/bin/bash
# DROP·AIM — Start everything in one command
# Run: bash ~/dropaim/start.sh

echo "╔═══════════════════════════════════╗"
echo "║  DROP·AIM — Starting...           ║"
echo "╚═══════════════════════════════════╝"

# Kill any existing instances
pkill -f mediamtx 2>/dev/null
pkill -f "ffmpeg.*8554" 2>/dev/null
pkill -f "node server" 2>/dev/null
sleep 1

# 1. Start RTSP relay server
#echo "[1/3] Starting mediamtx..."
#mediamtx &
#sleep 2

# 2. Start C20 video bridge
#echo "[2/3] Starting video bridge..."
#ffmpeg -loglevel error -rtsp_transport tcp \
 # -i rtsp://192.168.144.108:554/main \
  #-vcodec copy -an \
  #-f rtsp rtsp://127.0.0.1:8554/live &
#sleep 2

# 3. Start Node.js server
echo "[3/3] Starting server..."
echo ""
cd ~/dropaim
node server.js
