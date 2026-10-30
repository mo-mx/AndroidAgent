#!/usr/bin/env bash
# Phase 1 — device environment detection (read-only, no system modification)
set -u
ADB="/c/Users/Aqua/AndroidAgent/tools/platform-tools/adb.exe"
OUT_DIR="/c/Users/Aqua/AndroidAgent/logs"
TS=$(date +%Y%m%d-%H%M%S)
OUT="$OUT_DIR/phase1-$TS.txt"
mkdir -p "$OUT_DIR"
: > "$OUT"

say() { printf '\n===== %s =====\n' "$1" >> "$OUT"; }

g() { "$ADB" shell getprop "$1" 2>&1 | tr -d '\r'; }

say "ADB connection"
"$ADB" devices -l >> "$OUT" 2>&1
"$ADB" get-state >> "$OUT" 2>&1

say "Model / identity"
for p in ro.product.model ro.product.name ro.product.device ro.product.manufacturer \
         ro.product.brand ro.serialno ro.build.fingerprint ro.build.display.id; do
  printf '%-32s %s\n' "$p" "$(g $p)" >> "$OUT"
done

say "OS versions"
for p in ro.build.version.release ro.build.version.release_or_codename ro.build.version.sdk \
         ro.build.version.security_patch ro.build.version.incremental ro.build.version.oneui \
         ro.build.version.sem ro.build.version.base_os ro.vendor.product.model \
         ro.product.csc ro.csc.country_code ro.csc.sales_code ro.config.one_ui_version; do
  printf '%-40s %s\n' "$p" "$(g $p)" >> "$OUT"
done

say "Hardware"
for p in ro.product.cpu.abi ro.product.cpu.abilist ro.product.cpu.abi2 \
         ro.boot.hardware ro.soc.manufacturer ro.soc.model ro.device.soc_manufacturer ro.device.soc_model; do
  printf '%-32s %s\n' "$p" "$(g $p)" >> "$OUT"
done

say "Memory"
"$ADB" shell cat /proc/meminfo 2>&1 | grep -Ei 'MemTotal|MemAvailable|SwapTotal' >> "$OUT"
"$ADB" shell "cat /sys/block/zram0/disksize 2>/dev/null" >> "$OUT" 2>&1

say "CPU"
"$ADB" shell "grep -m4 -E 'model name|Hardware|implementer' /proc/cpuinfo" >> "$OUT" 2>&1
printf 'cores: '; "$ADB" shell nproc >> "$OUT" 2>&1

say "Storage (df -h)"
"$ADB" shell df -h /data /storage/emulated 2>&1 >> "$OUT"

say "Battery / power"
"$ADB" shell dumpsys battery 2>&1 | head -30 >> "$OUT"
printf 'battery optimization allowlist:\n' >> "$OUT"
"$ADB" shell dumpsys deviceidle whitelist 2>&1 | head -30 >> "$OUT"

say "Screen / display"
"$ADB" shell wm size 2>&1 >> "$OUT"
"$ADB" shell wm density 2>&1 >> "$OUT"
"$ADB" shell dumpsys device_state 2>&1 | head -20 >> "$OUT"

say "Voice assistant config"
for k in voice_interaction_service assistant assist_structure assist_gesture_enabled \
         assistant_enabled enabled_accessibility_services accessibility_enabled \
         assistant_voicelock_enabled voice_recognition_service; do
  printf '%-34s [%s]\n' "$k" "$("$ADB" shell settings get secure $k 2>&1 | tr -d '\r')" >> "$OUT"
  printf '%-34s global [%s]\n' "$k" "$("$ADB" shell settings get global $k 2>&1 | tr -d '\r')" >> "$OUT"
done

say "Voice interaction services installed"
"$ADB" shell "cmd package query-services --brief -a android.service.voice.VoiceInteractionService" >> "$OUT" 2>&1
"$ADB" shell "pm list packages -f | grep -iE 'voice|assistant|bixby|googlequicksearch|himars|gemini'" >> "$OUT" 2>&1

say "Accessibility services installed"
"$ADB" shell "cmd package query-services --brief -a android.accessibilityservice.AccessibilityService" >> "$OUT" 2>&1

say "Side key / assistant gesture hints"
"$ADB" shell settings list secure 2>&1 | grep -iE 'assist|side_key|voice|bixby' >> "$OUT" 2>&1

say "USB / developer state"
for k in adb_enabled development_settings_enabled; do
  printf 'global %-24s [%s]\n' "$k" "$("$ADB" shell settings get global $k 2>&1 | tr -d '\r')" >> "$OUT"
done
"$ADB" shell "getprop | grep -iE 'usb|adb'" >> "$OUT" 2>&1

say "Samsung battery / app sleep policy"
"$ADB" shell dumpsys deviceidle 2>&1 | head -25 >> "$OUT"
"$ADB" shell "cmd appops get com.sec.android.app.launcher" >> "$OUT" 2>&1
"$ADB" shell settings get global app_standby_enabled >> "$OUT" 2>&1
"$ADB" shell settings get global adaptive_battery_management_enabled >> "$OUT" 2>&1
"$ADB" shell dumpsys power 2>&1 | grep -iE 'mWakefulness|mDoze|Doze' | head -10 >> "$OUT"

say "Power-saving / background limits"
"$ADB" shell settings get global low_power >> "$OUT" 2>&1
"$ADB" shell settings get global background_process_limit >> "$OUT" 2>&1
"$ADB" shell dumpsys device_policy 2>&1 | head -15 >> "$OUT"

say "Camera / mic / phone capability probes"
"$ADB" shell "pm list features" 2>&1 | grep -iE 'camera|microphone|telephony|location|nfc' >> "$OUT"

echo "REPORT: $OUT"
