# Komodo
BlueDragon's [Velocity](https://velocitypowered.com/) plugin. It currently serves a few purposes:
- Routing players to servers based on received gRPC messages
- Dynamically create and remove servers based on other gRPC calls
- Requesting a player count from an external service and generating a server list ping response with it
- Sending players to a lobby when they first join

## Usage
Build with `./gradlew build` and place the JAR from `build/libs/Komodo-x.x.x.jar` in your Velocity proxy's plugins folder.

## Development
This can be built as a docker container with the following command:
```shell
$ docker build -t bluedragonmc/komodo:dev .
```
This uses the `Dockerfile` in the current directory to make an image with Velocity, Komodo, and a few other plugins.

**Note**: When running inside Docker, a `PUFFIN_VELOCITY_SECRET` environment variable must be passed to the container, which is used to create the `forwarding.secret` file in the container.
