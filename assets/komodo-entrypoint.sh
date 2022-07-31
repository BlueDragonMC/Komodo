#!/bin/bash

cd /proxy
# The `PUFFIN_VELOCITY_SECRET` environment variable is added by Puffin when the container is created.
echo $PUFFIN_VELOCITY_SECRET > /proxy/forwarding.secret

# Add the container ID to the end of the UnifiedMetrics configuration
echo "server:" >> /proxy/plugins/unifiedmetrics/config.yml
echo "  name: $PUFFIN_CONTAINER_ID" >> /proxy/plugins/unifiedmetrics/config.yml

echo "  job: $PUFFIN_CONTAINER_ID" >> /proxy/plugins/unifiedmetrics/driver/prometheus.yml

# Add the TCPShield plugin if the correct environment variable is set.
if [ -z "$ENABLE_TCPSHIELD" ]; then
  echo "TCPShield is disabled for this container."
else
  mv plugins/disabled/TCPShield-*.jar plugins/
fi

java -jar proxy.jar