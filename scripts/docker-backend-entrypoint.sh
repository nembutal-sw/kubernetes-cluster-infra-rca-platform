#!/usr/bin/env sh
set -eu

if [ "${RCA_RUN_MIGRATIONS:-true}" = "true" ]; then
  python -m alembic upgrade head
fi

exec "$@"
