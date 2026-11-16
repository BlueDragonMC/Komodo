#!/bin/bash
set -e

cd /proxy

echo "$PUFFIN_VELOCITY_SECRET" > /proxy/forwarding.secret

case "${VELOCITY_HAPROXY_PROTOCOL:-false}" in
    true|TRUE|1)
        sed -i 's/^haproxy-protocol = .*/haproxy-protocol = true/' /proxy/velocity.toml
        ;;
esac

exec java -Dlog4j.configurationFile=/proxy/log4j2.xml -jar proxy.jar
