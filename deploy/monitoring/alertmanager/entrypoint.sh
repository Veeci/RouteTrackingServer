#!/bin/sh
# Writes the Alertmanager config with the Discord webhook from the environment, so the URL stays out of git.
set -eu
if [ -n "${DISCORD_WEBHOOK_URL:-}" ]; then
  sed "s|__DISCORD_WEBHOOK_URL__|${DISCORD_WEBHOOK_URL}|" /etc/alertmanager/alertmanager.yml > /tmp/alertmanager.yml
else
  cp /etc/alertmanager/alertmanager-silent.yml /tmp/alertmanager.yml
fi
exec /bin/alertmanager --config.file=/tmp/alertmanager.yml --storage.path=/alertmanager
