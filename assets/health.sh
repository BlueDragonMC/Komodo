#!/bin/bash

if [ -z "$ENABLE_TCPSHIELD" ]; then
  mc-monitor status --host localhost --port 25565
  exit $?
else
  # If TCPShield protection is enabled, mc-monitor will not be able to ping the server locally.
  echo "TCPShield protection is enabled"
  exit 0
fi
