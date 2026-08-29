#!/usr/bin/env bash

set -e
cd "$(dirname "$0")"
cp ../public/index.html ../public/sim.js ../public/manifest.json ../public/icon.svg app/src/main/assets/www/
echo "synced public/ -> app/src/main/assets/www/"
