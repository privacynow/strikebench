#!/usr/bin/env bash
# Provision a local PostgreSQL 16 for StrikeBench on the app server (Amazon Linux 2023).
# Postgres listens on localhost only; the app connects over 127.0.0.1 with scram-sha-256.
# Run ONCE on the box, as a sudo-capable user:
#
#   DB_PASSWORD='a-strong-password' scripts/provision-postgres.sh
#
# Then put the SAME password in /opt/strikebench/strikebench.properties as db.password=...
# and start the app.
#
# Overrides: PG_VERSION (16), DB_NAME (strikebench), DB_ROLE (strikebench), DB_PASSWORD (required).
set -euo pipefail

PG_VERSION="${PG_VERSION:-16}"
DB_NAME="${DB_NAME:-strikebench}"
DB_ROLE="${DB_ROLE:-strikebench}"
DB_PASSWORD="${DB_PASSWORD:-}"

if [ -z "$DB_PASSWORD" ]; then
  echo "DB_PASSWORD is required (the app-role password). Re-run: DB_PASSWORD='...' $0" >&2
  exit 2
fi

echo "== installing PostgreSQL ${PG_VERSION} (Amazon Linux 2023)"
sudo dnf install -y "postgresql${PG_VERSION}-server" "postgresql${PG_VERSION}"

PGDATA="/var/lib/pgsql/data"
if [ ! -f "${PGDATA}/PG_VERSION" ]; then
  echo "== initializing cluster"
  sudo /usr/bin/postgresql-setup --initdb
fi

echo "== hardening config: localhost-only, scram-sha-256"
sudo sed -i "s/^#\?listen_addresses.*/listen_addresses = 'localhost'/" "${PGDATA}/postgresql.conf"
sudo sed -i "s/^#\?password_encryption.*/password_encryption = scram-sha-256/" "${PGDATA}/postgresql.conf"
# Only local + loopback, all scram; drop any trust/ident lines for host connections.
sudo tee "${PGDATA}/pg_hba.conf" > /dev/null <<HBA
local   all   all                 scram-sha-256
host    all   all   127.0.0.1/32  scram-sha-256
host    all   all   ::1/128       scram-sha-256
HBA

echo "== enabling + starting postgresql"
sudo systemctl enable postgresql
sudo systemctl restart postgresql

echo "== creating role '${DB_ROLE}' + database '${DB_NAME}'"
# Idempotent: create the role/db only if absent; always (re)set the password.
sudo -u postgres psql -v ON_ERROR_STOP=1 <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${DB_ROLE}') THEN
    CREATE ROLE ${DB_ROLE} LOGIN;
  END IF;
END \$\$;
ALTER ROLE ${DB_ROLE} WITH PASSWORD '${DB_PASSWORD}';
SQL
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" | grep -q 1; then
  sudo -u postgres createdb -O "${DB_ROLE}" "${DB_NAME}"
fi

echo
echo "Postgres ready: jdbc:postgresql://localhost:5432/${DB_NAME} (user ${DB_ROLE})"
echo "Next:"
echo "  1) put the password in /opt/strikebench/strikebench.properties:  db.password=${DB_PASSWORD}"
echo "  2) sudo systemctl restart strikebench   (the app initializes an empty database from its current schema)"
