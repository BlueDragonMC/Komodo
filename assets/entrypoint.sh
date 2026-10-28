#!/bin/bash

cd /proxy
# The `velocity_secret` environment variable is added by Puffin when the container is created.
echo $PUFFIN_VELOCITY_SECRET > /proxy/forwarding.secret

exec java -jar proxy.jar