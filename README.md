# Komodo

BlueDragon's [Velocity](https://velocitypowered.com/) plugin. It currently serves a few purposes:

- Routing players to servers based on received gRPC messages
- Dynamically create and remove servers based on other gRPC calls
- Requesting a player count from an external service and generating a server list ping response with it
- Sending players to a lobby when they first join
- Graceful shutdown using a Kubernetes preStop hook

## Usage

Build with `./gradlew build` and place the JAR from `build/libs/Komodo-x.x.x.jar` in your Velocity proxy's plugins
folder.

## Configuration

Environment variables:

- `KOMODO_PUFFIN_URI`: The URI used to connect to Puffin. Should comply
  with [this document](https://grpc.io/docs/guides/custom-name-resolution/).

  *Default value*: `puffin:50051`

- `KOMODO_GRPC_PORT`: The port used for Komodo's gRPC server.

  *Default value*: `50051`

- `KOMODO_HEALTH_PORT`: The port used for `/health`, `/ready`, and `/drain`.

  *Default value*: `8081`

- `KOMODO_HEALTH_TIMEOUT_MS`: How long the proxy may go without a scheduler heartbeat before it is considered unhealthy.

  *Default value*: `1000`

- `KOMODO_SHUTDOWN_COUNTDOWN_SECONDS`: How long players have before a draining proxy disconnects them.

  *Default value*: `7200`

- `KOMODO_COUNTDOWN_HIDDEN_SECONDS`: How long the countdown stays hidden at the start of a drain.
  Players who switch games during this window are transferred without seeing a shutdown warning.

  *Default value*: `3600`

- `KOMODO_DRAIN_QUIESCENCE_MS`: How long a draining proxy must remain empty before it stops early.

  *Default value*: `15000`

- `KOMODO_TRANSFER_HOST`: The player-facing proxy hostname used in transfer packets. If unset, proxy
  transfers are disabled.

  *Default value*: unset

- `KOMODO_TRANSFER_PORT`: The player-facing proxy port used in transfer packets.

  *Default value*: `25565`

- `KOMODO_LIFECYCLE_DISABLED`: If present, disables the lifecycle HTTP endpoints.

## Development

This can be built as a docker container with the following command:

```shell
$ docker build -t bluedragonmc/komodo:dev .
```

This uses the `Dockerfile` in the current directory to make an image with Velocity, Komodo, and a few other plugins.

**Note**: When running inside Docker, a `PUFFIN_VELOCITY_SECRET` environment variable must be passed to the container,
which is used to create the `forwarding.secret` file in the container.
