# Komodo
BlueDragon's [Velocity](https://velocitypowered.com/) plugin. It currently serves a few purposes:
- Routing players to servers based on received RabbitMQ messages
- Dynamically create and remove servers based on other RabbitMQ messages
- Forwarding server list pings to the first backend server that responds within 1 second
- Sending players to a lobby when they first join
- Create new lobbies if none exist (currently disabled)

## Usage
Build with `./gradlew build` and place the JAR from `build/libs/Komodo-x.x.x.jar` in your Velocity proxy's plugins folder.

## Development
This can be built as a docker container with the following command:
```shell
DOCKER_BUILDKIT=1 docker build -t bluedragonmc/komodo:testing --label com.bluedragonmc.komodo.version=testing .
```
This uses the `Dockerfile` in the current directory to make an image with the version string `"testing"`.

**Note**: When running inside Docker, a `forwarding_secret` environment variable must be passed to the container, which is used to create the `forwarding.secret` file in the container.
