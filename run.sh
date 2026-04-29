#!/bin/bash




SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
sh "$SCRIPT_DIR/init.sh"
sh "$SCRIPT_DIR/build.sh"

cd "$SCRIPT_DIR/frontend"
npm install
npm run dev