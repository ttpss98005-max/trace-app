#!/bin/bash
# Runs the APK in both orientations and saves screenshots to shots/.
mkdir -p shots
adb install -r trace.apk
adb shell settings put global hide_error_dialogs 1
adb shell settings put system accelerometer_rotation 0
rot() { adb shell cmd window user-rotation lock "$1" || adb shell settings put system user_rotation "$1"; sleep 5; }
run() { adb shell am start -S -n tw.trace.app/.MainActivity --ez demo true --es screen "$1"; sleep 8; }
shot() { adb exec-out screencap -p > "shots/$1.png"; }
sleep 45
rot 0
run board; shot port-board
run panel; shot port-panel
rot 1; shot land-panel-rotated
run settings; shot land-settings
run board; shot land-board
run hub; shot land-hub
run login; shot land-login
rot 0
run board; rot 1; shot land-board-rotated; rot 0; shot port-board-rotated
run hub; shot port-hub
run settings; shot port-settings
run wire; shot port-wire-picked
run wirecut; shot port-wire-deleted
run login; shot port-login
adb logcat -d -b crash > shots/crash.txt || true
exit 0
