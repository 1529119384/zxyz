#!/bin/sh
# Substitute environment variables into nginx config template.
# Only replace OSS_PUBLIC_BASE_URL — leave nginx's $host, $remote_addr etc. untouched.
envsubst '${OSS_PUBLIC_BASE_URL}' \
  < /etc/nginx/templates/default.conf.template \
  > /etc/nginx/conf.d/default.conf

exec nginx -g 'daemon off;'
