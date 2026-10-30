#!/usr/bin/env bash
set -u
ADB="/c/Users/Aqua/AndroidAgent/tools/platform-tools/adb.exe"
OUT="/c/Users/Aqua/AndroidAgent/logs/phase1b-$(date +%Y%m%d-%H%M%S).txt"
: > "$OUT"
say() { printf '\n===== %s =====\n' "$1" >> "$OUT"; }
r() { "$ADB" shell "$1" 2>&1 | tr -d '\r' >> "$OUT"; }

say "Assistant role holders"
r "cmd role get-role-holders android.app.role.ASSISTANT"
r "cmd role get-role-holders android.app.role.DIALER"
r "dumpsys role 2>/dev/null | grep -A3 -i assistant | head -40"

say "Full secure settings (non-default keys)"
r "settings list secure"

say "Global settings (assistant / battery / doze related)"
r "settings list global | grep -iE 'assist|voice|bixby|side|battery|doze|idle|power|standby|sleep|fold'"

say "System settings (fold / cover / aod)"
r "settings list system | grep -iE 'aod|fold|cover|flex|screen_off|wake|gesture|assist'"

say "Cover / flex window state"
r "dumpsys device_state | head -30"
r "settings get global cover_app_tray_list"
r "pm list packages | grep -iE 'cover|flex|bixby|intellivoice'"

say "Power saving / app standby"
r "settings get global low_power"
r "dumpsys power | grep -iE 'mPowerSaveMode|mLowPowerMode|ForceSuspend|mIsPowered|WakeLock' | head -20"
r "dumpsys appops get com.google.android.googlequicksearchbox RECORD_AUDIO 2>/dev/null | head -5"
r "cmd deviceidle get deep"
r "cmd deviceidle get light"
r "dumpsys deviceidle whitelist | wc -l"

say "Doze / background execution limits"
r "getprop | grep -iE 'doze|low_ram|background'"
r "settings get global cached_apps_freezer"
r "settings get global app_standby_enabled"

say "Foreground service / FGS types support"
r "getprop ro.build.version.sdk"
r "dumpsys activity service-restrictions 2>&1 | head -20"

say "Samsung specific packages (assistant / voice)"
r "pm list packages | grep -iE 'samsung.android.bixby|intellivoiceservice|himars|voice|assistant|rubin'"

say "Audio / mic"
r "dumpsys audio | grep -iE 'mode|mic|record' | head -20"
r "getprop | grep -iE 'audio.*mic|voice.*recog'"

say "Telephony / SIM"
r "dumpsys telephony.registry | grep -iE 'mServiceState|mCallState' | head -5"
r "getprop gsm.sim.state"

say "Location providers"
r "settings get secure location_mode"
r "dumpsys location | head -15"

say "Installed third-party agent-ish apps"
r "pm list packages -3"

say "Screen lock / secure state"
r "dumpsys window | grep -iE 'mDreamingLockscreen|mShowingLockscreen|KeyguardShowing' | head -5"
r "dumpsys trust | head -10"

say "ADB stability check (3 quick calls)"
for i in 1 2 3; do printf 'ping%s: %s\n' "$i" "$("$ADB" shell echo pong 2>&1 | tr -d '\r')" >> "$OUT"; done

echo "REPORT: $OUT"
