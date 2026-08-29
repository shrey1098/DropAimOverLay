#!/data/data/com.termux/files/usr/bin/bash

echo "╔═══════════════════════════════════╗"
echo "║  DROP·AIM — Starting...           ║"
echo "╚═══════════════════════════════════╝"

pkill -f mediamtx 2>/dev/null
pkill -f "ffmpeg.*8554" 2>/dev/null
pkill -f "node server" 2>/dev/null
sleep 1

echo "[3/3] Starting server..."
echo ""
cd ~/dropaim
node server.js
