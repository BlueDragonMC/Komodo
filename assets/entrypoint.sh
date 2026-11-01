#!/bin/bash
set -e

cd /proxy

echo "$PUFFIN_VELOCITY_SECRET" > /proxy/forwarding.secret

case "${VELOCITY_HAPROXY_PROTOCOL:-false}" in
    true|TRUE|1)
        sed -i 's/^haproxy-protocol = .*/haproxy-protocol = true/' /proxy/velocity.toml
        ;;
esac

exec java -jar proxy.jar
