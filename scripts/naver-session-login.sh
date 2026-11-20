#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'naver-session-login: %s\n' "$1" >&2
  exit 1
}

if [[ -n "${NAVER_MAP_SELENIUM_USER_DATA_DIR:-}" ]]; then
  PROFILE_DIR="$NAVER_MAP_SELENIUM_USER_DATA_DIR"
else
  DATA_HOME="${XDG_DATA_HOME:-${HOME:?HOME or XDG_DATA_HOME must be set}/.local/share}"
  PROFILE_DIR="$DATA_HOME/naver-maps-mcp/chrome-profile"
fi

[[ "$PROFILE_DIR" == /* ]] || die 'NAVER_MAP_SELENIUM_USER_DATA_DIR must be an absolute path'
[[ "$PROFILE_DIR" != / ]] || die 'profile directory cannot be /'

if [[ -n "${CHROME_BIN:-}" ]]; then
  CHROME="$CHROME_BIN"
else
  CHROME="$(command -v google-chrome || true)"
fi
[[ -n "$CHROME" && -x "$CHROME" ]] || die 'set CHROME_BIN to an executable Google Chrome binary or install google-chrome on PATH'

umask 077
mkdir -p -m 700 -- "$PROFILE_DIR"
[[ -d "$PROFILE_DIR" && ! -L "$PROFILE_DIR" ]] || die 'profile path must be a real directory, not a symlink'

PROFILE_MODE="$(stat -c '%a' -- "$PROFILE_DIR")" || die 'could not inspect profile directory permissions'
[[ "$PROFILE_MODE" == 700 ]] || die 'profile directory must have mode 0700 before continuing'
[[ ! -e "$PROFILE_DIR/SingletonLock" && ! -L "$PROFILE_DIR/SingletonLock" ]] \
  || die 'Chrome has a profile lock; stop the worker and close the browser first. Do not delete its lock while Chrome is running'

printf '%s\n' \
  'Stop the Naver Maps worker that uses this profile before continuing.' \
  'Complete sign-in or any security verification in the Chrome window that opens.' \
  'Close Chrome completely before restarting the worker.'

exec "$CHROME" "--user-data-dir=$PROFILE_DIR" \
  'https://nid.naver.com/nidlogin.login?url=https%3A%2F%2Fmap.naver.com%2F'
