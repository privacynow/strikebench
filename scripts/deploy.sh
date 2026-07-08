#!/usr/bin/env bash
# StrikeBench deploy — generic, run ON the machine that serves the app.
#
#   scripts/deploy.sh                 update: pull -> build -> atomic jar swap -> restart -> health
#   scripts/deploy.sh --install       first run: create APP_DIR + properties + systemd unit, then deploy
#   scripts/deploy.sh --setup-timer   optional: poll git every 5 min and auto-deploy new commits
#   scripts/deploy.sh --if-changed    exit quietly unless origin/BRANCH is ahead (used by the timer)
#
# Everything machine-specific is an environment override with a sane default:
#   REPO        source checkout            (default: the repo this script lives in)
#   APP_DIR     runtime dir, NOT the repo  (default: /opt/strikebench)
#   SERVICE     systemd unit name          (default: strikebench)
#   PORT        app port, localhost-only   (default: 7070)
#   BRANCH      branch to deploy           (default: main)
#   RUN_USER    service user               (default: the invoking user)
#   JAVA_BIN    java 25+ binary            (default: java on PATH)
#   JAVA_HEAP   max heap                   (default: 768m)
#   SKIP_PULL=1 deploy the working tree as-is (no git pull)
#
# Reverse proxy + TLS stay OUTSIDE this script (one-time, host-specific). Example:
#   /etc/nginx/conf.d/strikebench.conf : server { server_name example.com;
#     location / { proxy_pass http://127.0.0.1:7070; proxy_set_header Host $host; } }
#   sudo certbot --nginx -d example.com --redirect
set -euo pipefail

REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
APP_DIR="${APP_DIR:-/opt/strikebench}"
SERVICE="${SERVICE:-strikebench}"
PORT="${PORT:-7070}"
BRANCH="${BRANCH:-main}"
RUN_USER="${RUN_USER:-$(id -un)}"
JAVA_BIN="${JAVA_BIN:-$(command -v java)}"
JAVA_HEAP="${JAVA_HEAP:-768m}"

health() { curl -sf "localhost:${PORT}/api/health"; }

install_service() {
  echo "== install: ${APP_DIR}, systemd unit '${SERVICE}' (user ${RUN_USER}, port ${PORT})"
  sudo mkdir -p "${APP_DIR}/data"
  if [ ! -f "${APP_DIR}/strikebench.properties" ]; then
    sudo tee "${APP_DIR}/strikebench.properties" > /dev/null <<PROPS
# StrikeBench config (read from the working directory at launch).
# Same keys as env vars, lowercased with dots. Data keys go here when ready, e.g.:
#   polygon.api.key=...
#   alphavantage.api.key=...
brand.name=StrikeBench
PROPS
  fi
  sudo chown -R "${RUN_USER}:${RUN_USER}" "${APP_DIR}"
  sudo tee "/etc/systemd/system/${SERVICE}.service" > /dev/null <<UNIT
[Unit]
Description=StrikeBench options paper-trading app
After=network.target

[Service]
User=${RUN_USER}
WorkingDirectory=${APP_DIR}
Environment=PORT=${PORT}
Environment=DB_PATH=${APP_DIR}/data/strikebench.db
Environment=JAVA_TOOL_OPTIONS=-Xmx${JAVA_HEAP}
ExecStart=${JAVA_BIN} -jar ${APP_DIR}/strikebench.jar
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
UNIT
  sudo systemctl daemon-reload
  sudo systemctl enable "${SERVICE}"
}

setup_timer() {
  echo "== auto-deploy: poll origin/${BRANCH} every 5 min, deploy only when it moves"
  # systemd's default PATH misses login-shell tools (mvn was /opt/maven/bin here) —
  # bake the resolving shell's tool dirs into the unit so the build can actually run
  local mvndir gitdir
  mvndir="$(dirname "$(command -v mvn)")"
  gitdir="$(dirname "$(command -v git)")"
  sudo tee "/etc/systemd/system/${SERVICE}-autodeploy.service" > /dev/null <<UNIT
[Unit]
Description=StrikeBench auto-deploy (git poll)

[Service]
Type=oneshot
User=${RUN_USER}
Environment=REPO=${REPO} APP_DIR=${APP_DIR} SERVICE=${SERVICE} PORT=${PORT} BRANCH=${BRANCH}
Environment=PATH=${mvndir}:${gitdir}:/usr/local/bin:/usr/bin:/bin
ExecStart=${REPO}/scripts/deploy.sh --if-changed
UNIT
  sudo tee "/etc/systemd/system/${SERVICE}-autodeploy.timer" > /dev/null <<UNIT
[Unit]
Description=StrikeBench auto-deploy poll

[Timer]
OnBootSec=2min
OnUnitActiveSec=5min

[Install]
WantedBy=timers.target
UNIT
  sudo systemctl daemon-reload
  sudo systemctl enable --now "${SERVICE}-autodeploy.timer"
  echo "enabled ${SERVICE}-autodeploy.timer (disable: sudo systemctl disable --now ${SERVICE}-autodeploy.timer)"
}

deploy() {
  cd "$REPO"
  if [ "${SKIP_PULL:-0}" != "1" ]; then
    git fetch origin "$BRANCH"
    git checkout -q "$BRANCH"
    git pull --ff-only origin "$BRANCH"
  fi
  mvn -q package -DskipTests
  sudo cp target/strikebench.jar "${APP_DIR}/strikebench.jar.new"
  sudo mv "${APP_DIR}/strikebench.jar.new" "${APP_DIR}/strikebench.jar"   # atomic swap
  sudo systemctl restart "$SERVICE"
  for _ in $(seq 1 20); do
    sleep 1
    if health > /dev/null 2>&1; then
      echo "deployed OK: $(health)"
      # the marker records SUCCESS — --if-changed compares origin against this, never
      # against local HEAD (a deploy that failed after its pull must retry, not go quiet)
      git rev-parse HEAD | sudo tee "${APP_DIR}/.deployed-rev" > /dev/null
      return 0
    fi
  done
  echo "service did not become healthy — check: journalctl -u ${SERVICE} -n 50" >&2
  return 1
}

case "${1:-}" in
  --install)     install_service; deploy ;;
  --setup-timer) setup_timer ;;
  --if-changed)
    cd "$REPO"
    remote="$(git ls-remote origin "refs/heads/${BRANCH}" | cut -f1)"
    if [ -z "$remote" ]; then
      echo "cannot read origin/${BRANCH}" >&2
      exit 1
    fi
    if [ -f "${APP_DIR}/.deployed-rev" ] && [ "$(cat "${APP_DIR}/.deployed-rev")" = "$remote" ]; then
      exit 0  # the running deploy IS the remote tip
    fi
    deploy ;;
  "")            deploy ;;
  *) echo "usage: $0 [--install|--setup-timer|--if-changed]" >&2; exit 2 ;;
esac
