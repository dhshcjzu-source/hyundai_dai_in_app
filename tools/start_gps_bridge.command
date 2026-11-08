#!/bin/zsh
cd "$(dirname "$0")/.."
swift tools/mac_gps_bridge.swift emulator-5554 2>&1 | tee /tmp/mac_gps_bridge.log
