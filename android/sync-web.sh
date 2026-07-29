#!/usr/bin/env bash
# Copy the tested web app into the APK assets (single source: ../public).
set -e
cd "$(dirname "$0")"
cp ../public/index.html ../public/manifest.json ../public/icon.svg app/src/main/assets/www/
echo "synced public/ -> app/src/main/assets/www/"
