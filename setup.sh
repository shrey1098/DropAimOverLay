#!/data/data/com.termux/files/usr/bin/bash
# DROP·AIM — One-time setup
# Run: bash setup.sh

echo "Installing dependencies..."
pkg update -y
pkg install nodejs ffmpeg mediamtx -y

echo "Installing Node.js packages..."
cd ~/dropaim
npm init -y
npm install express ws

echo "Making start script executable..."
chmod +x ~/dropaim/start.sh

echo ""
echo "╔═══════════════════════════════════╗"
echo "║  Setup complete!                  ║"
echo "║                                   ║"
echo "║  To start:  bash ~/dropaim/start.sh║"
echo "║  Browser:   http://localhost:3000  ║"
echo "║                                   ║"
echo "║  QGC MAVLink forwarding:           ║"
echo "║  Host: localhost:14445             ║"
echo "╚═══════════════════════════════════╝"
