#!/usr/bin/env sh

# StopNet Build Tool (macOS zsh & Linux bash compatible)
# 参考 Windows 下的 build.bat，实现等价的交互菜单与任务流程

set -u

ORIGINAL_DIR="$(pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd)"
cd "$SCRIPT_DIR" || {
  echo "无法进入脚本所在目录：$SCRIPT_DIR"
  exit 1
}

APP_ID="com.example.stopnet"

print_header() {
  clear 2>/dev/null || printf "\033c"
  echo "==================================="
  echo "     StopNet Build Tool (Unix)"
  echo "==================================="
}

pause() {
  printf "按回车返回菜单..." && read -r _
}

has_cmd() {
  command -v "$1" >/dev/null 2>&1
}

ensure_gradlew() {
  if [ ! -x "./gradlew" ]; then
    if [ -f "./gradlew" ]; then
      chmod +x ./gradlew 2>/dev/null || true
    else
      echo "未找到 ./gradlew，请确认在项目根目录执行。"
      return 1
    fi
  fi
  return 0
}

gradle() {
  ensure_gradlew || return 1
  ./gradlew "$@"
}

do_clean() { gradle cleanSafe; }
do_build() { gradle cleanSafe && gradle build; }
do_test() { gradle cleanSafe && gradle build && gradle test; }
do_installDebugAndLogcat() { gradle cleanSafe && gradle build && gradle installDebugAndLogcat; }
do_assembleDebug() { gradle cleanSafe && gradle build && gradle assembleDebug; }
do_installRelease() { gradle cleanSafe && gradle build && gradle installRelease; }
do_assembleRelease() { gradle cleanSafe && gradle build && gradle assembleRelease; }

do_stopApp() {
  gradle :app:stopApp
}

do_killEmulator() {
  gradle :app:killEmulator
}

while true; do
  print_header
  echo " 1) ./gradlew cleanSafe"
  echo " 2) ./gradlew build（含 cleanSafe）"
  echo " 3) ./gradlew test（含 cleanSafe、build）"
  echo " 4) ./gradlew installDebugAndLogcat（含 cleanSafe、build）"
  echo " 5) ./gradlew assembleDebug（含 cleanSafe、build）"
  echo " 6) ./gradlew installRelease（含 cleanSafe、build）"
  echo " 7) ./gradlew assembleRelease（含 cleanSafe、build）"
  echo " 8) adb 停止应用（${APP_ID})"
  echo " 9) adb 关闭模拟器"
  echo " 0) 退出"
  echo "==================================="
  printf "输入数字并回车（0 退出）： "
  read -r choice
  case "$choice" in
    1) do_clean ;;
    2) do_build ;;
    3) do_test ;;
    4) do_installDebugAndLogcat ;;
    5) do_assembleDebug ;;
    6) do_installRelease ;;
    7) do_assembleRelease ;;
    8) do_stopApp ;;
    9) do_killEmulator ;;
    0) break ;;
    *) echo "无效选择：$choice" ;;
  esac
  pause
done

cd "$ORIGINAL_DIR" || true
exit 0