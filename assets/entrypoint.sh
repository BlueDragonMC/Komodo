#!/bin/bash

cd /proxy
# The `velocity_secret` environment variable is added by Puffin when the container is created.
echo $PUFFIN_VELOCITY_SECRET > /proxy/forwarding.secret

# Add the TCPShield plugin if the correct environment variable is set.
if [ -z "$ENABLE_TCPSHIELD" ]; then
  echo "TCPShield is disabled for this container."
else
  mv plugins/disabled/TCPShield-*.jar plugins/
fi

java -jar proxy.jar