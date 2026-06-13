#!/usr/bin/env bash

set -euo pipefail

readonly DEPLOY_PATH="${DEPLOY_PATH:-/opt/zxyz-database}"
readonly SERVICE_NAME="${SERVICE_NAME:-zxyz-database.service}"
readonly RELEASES_DIR="${DEPLOY_PATH}/releases"
readonly CURRENT_JAR="${DEPLOY_PATH}/current/app.jar"
readonly HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:18080/actuator/health}"
readonly STARTUP_WAIT_SECONDS="${STARTUP_WAIT_SECONDS:-60}"

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <jar-path>"
  exit 1
fi

readonly NEW_JAR_SOURCE="$1"

if [[ ! -f "$NEW_JAR_SOURCE" ]]; then
  echo "Jar not found: $NEW_JAR_SOURCE"
  exit 1
fi

timestamp="$(date +%Y%m%d%H%M%S)"
release_dir="${RELEASES_DIR}/${timestamp}"
backup_jar=""

rollback() {
  if [[ -n "$backup_jar" && -f "$backup_jar" ]]; then
    echo "Deployment failed. Rolling back to previous version."
    install -D -m 0644 "$backup_jar" "$CURRENT_JAR"
    sudo systemctl restart "$SERVICE_NAME"
  fi
}

trap rollback ERR

mkdir -p "${DEPLOY_PATH}/current" "$RELEASES_DIR"
install -D -m 0644 "$NEW_JAR_SOURCE" "${release_dir}/app.jar"

if [[ -f "$CURRENT_JAR" ]]; then
  backup_jar="${release_dir}/previous-app.jar"
  cp "$CURRENT_JAR" "$backup_jar"
fi

install -D -m 0644 "${release_dir}/app.jar" "$CURRENT_JAR"
sudo systemctl restart "$SERVICE_NAME"

for ((i = 1; i <= STARTUP_WAIT_SECONDS; i++)); do
  if curl --silent --fail "$HEALTH_URL" | grep -q '"status":"UP"'; then
    echo "Deployment completed successfully."
    rm -f "$NEW_JAR_SOURCE"
    exit 0
  fi
  sleep 1
done

echo "Health check failed: $HEALTH_URL"
exit 1
