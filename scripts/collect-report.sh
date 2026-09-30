#!/system/bin/sh
# FCMFix 问题报告收集脚本。需要 Root：su -c sh /sdcard/Download/collect-report.sh [输出文件名]
# 只读取系统状态和日志，不修改任何设置。

OUT="/sdcard/Download/${1:-fcmfix-report.txt}"
GMS=com.google.android.gms
MODULE=io.github.zopulus.ffc
GMS_UID=$(pm list packages -U "$GMS" | grep "^package:$GMS " | grep -o 'uid:[0-9]*' | cut -d: -f2)

section() { echo; echo "== $1"; }

{
  echo "FCMFix report $(date '+%Y-%m-%d %H:%M:%S %z')"

  section "系统版本"
  echo "display=$(getprop ro.build.display.id)"
  echo "oplusrom=$(getprop ro.build.version.oplusrom.display)"
  echo "sdk=$(getprop ro.build.version.sdk)"
  echo "model=$(getprop ro.product.model)"

  section "模块版本"
  dumpsys package "$MODULE" | grep -m1 versionName || echo "未安装 $MODULE"

  section "GMS 联网策略（无匹配行不代表已确认无限制；同时检查 Oplus 策略与 Hook 日志）"
  echo "gms uid=$GMS_UID"
  [ -n "$GMS_UID" ] && dumpsys netpolicy | grep -E "UID=$GMS_UID( |$)"

  section "GMS 待机分组（10/20/30 正常，40 为 RARE 受限）"
  am get-standby-bucket "$GMS"

  section "google_restric_info（1 表示系统判定 Google 受限）"
  settings get secure google_restric_info

  section "FCMFix / ColorOS Google 限制日志"
  logcat -d | grep -iE "fcmfix|GoogleController|OplusGoogle"
  section "Doze 白名单"
  dumpsys deviceidle whitelist

  section "Google 唤醒闹钟"
  dumpsys alarm | grep -iE "google|gms|restrict|wakeup"
} > "$OUT" 2>&1

echo "已保存到 $OUT"
