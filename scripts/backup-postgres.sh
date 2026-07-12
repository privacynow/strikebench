#!/usr/bin/env bash
# Off-box backup of the StrikeBench Postgres database: pg_dump -> gzip -> S3, with local
# retention. Run on the app server.
#
#   scripts/backup-postgres.sh                 one backup now
#   scripts/backup-postgres.sh --setup-timer   install a systemd timer for a nightly backup
#
# Config (env or /opt/strikebench/backup.env):
#   DB_NAME        strikebench
#   DB_ROLE        strikebench
#   PGPASSWORD     the app-role password (or use ~/.pgpass)
#   BACKUP_BUCKET  s3://my-bucket/strikebench   (required for the S3 upload; local-only if unset)
#   BACKUP_DIR     /var/backups/strikebench
#   KEEP_LOCAL     14   (how many local dumps to keep)
set -euo pipefail

# Optional env file for the timer to source (keeps secrets out of the unit).
[ -f /opt/strikebench/backup.env ] && . /opt/strikebench/backup.env

DB_NAME="${DB_NAME:-strikebench}"
DB_ROLE="${DB_ROLE:-strikebench}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/strikebench}"
KEEP_LOCAL="${KEEP_LOCAL:-14}"
SERVICE="${SERVICE:-strikebench}"

setup_timer() {
  echo "== installing nightly backup timer (${SERVICE}-backup.timer)"
  local self; self="$(cd "$(dirname "$0")" && pwd)/backup-postgres.sh"
  sudo tee "/etc/systemd/system/${SERVICE}-backup.service" > /dev/null <<UNIT
[Unit]
Description=StrikeBench Postgres backup

[Service]
Type=oneshot
User=$(id -un)
ExecStart=${self}
UNIT
  sudo tee "/etc/systemd/system/${SERVICE}-backup.timer" > /dev/null <<UNIT
[Unit]
Description=StrikeBench nightly Postgres backup

[Timer]
OnCalendar=*-*-* 03:30:00
Persistent=true

[Install]
WantedBy=timers.target
UNIT
  sudo systemctl daemon-reload
  sudo systemctl enable --now "${SERVICE}-backup.timer"
  echo "enabled ${SERVICE}-backup.timer (nightly 03:30). Disable: sudo systemctl disable --now ${SERVICE}-backup.timer"
}

run_backup() {
  mkdir -p "${BACKUP_DIR}"
  local stamp file
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  file="${BACKUP_DIR}/${DB_NAME}-${stamp}.sql.gz"
  echo "== dumping ${DB_NAME} -> ${file}"
  pg_dump -h localhost -U "${DB_ROLE}" "${DB_NAME}" | gzip > "${file}"

  if [ -n "${BACKUP_BUCKET:-}" ]; then
    echo "== uploading to ${BACKUP_BUCKET}/"
    aws s3 cp "${file}" "${BACKUP_BUCKET%/}/$(basename "${file}")"
  else
    echo "BACKUP_BUCKET unset — kept local only (${file})"
  fi

  # Local retention: keep the newest KEEP_LOCAL dumps.
  ls -1t "${BACKUP_DIR}"/${DB_NAME}-*.sql.gz 2>/dev/null | tail -n +"$((KEEP_LOCAL + 1))" | xargs -r rm -f
  echo "== done ($(ls -1 "${BACKUP_DIR}"/${DB_NAME}-*.sql.gz 2>/dev/null | wc -l) local dumps retained)"
}

case "${1:-}" in
  --setup-timer) setup_timer ;;
  "")            run_backup ;;
  *) echo "usage: $0 [--setup-timer]" >&2; exit 2 ;;
esac
