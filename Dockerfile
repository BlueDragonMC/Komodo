# This Dockerfile must be run with BuildKit enabled
# see https://docs.docker.com/engine/reference/builder/#buildkit

# Build the project into an executable JAR
FROM gradle:jdk21 AS build
# Copy build files and source code
COPY . /work
WORKDIR /work
# Run gradle in the /work directory
RUN --mount=target=/home/gradle/.gradle,type=cache \
    /usr/bin/gradle --console=rich --warn --stacktrace --no-daemon --build-cache build

# Run Velocity with the built JAR in its plugins folder and expose port 25565
FROM eclipse-temurin:21

EXPOSE 25565

ARG REALIP_VERSION="2.8.1"

LABEL com.bluedragonmc.image=komodo
LABEL com.bluedragonmc.environment=development

WORKDIR /proxy

# Add Velocity
ADD "https://fill-data.papermc.io/v1/objects/f82780ce33035ebe3d6ea7981f0e6e8a3e41a64f2080ef5c0f1266fada03cbee/velocity-3.4.0-SNAPSHOT-522.jar" /proxy/proxy.jar

# Add TCPShield's RealIP plugin
ADD "https://github.com/TCPShield/RealIP/releases/download/$REALIP_VERSION/TCPShield-$REALIP_VERSION.jar" /proxy/plugins/disabled/TCPShield-$REALIP_VERSION.jar

# Add LuckPerms for permissions
ADD "https://download.luckperms.net/1595/velocity/LuckPerms-Velocity-5.5.10.jar" /proxy/plugins/LuckPerms.jar

# Add the Jukebox plugin (and Protocolize, its dependency)
# This is a fork of Protocolize with support for up to 1.21.8-rc1. Thanks @proferabg!
ADD "./assets/protocolize-velocity-proferabg-b1.jar" /proxy/plugins/protocolize.jar
ADD "https://github.com/BlueDragonMC/Jukebox/releases/download/latest/Jukebox-1.0-SNAPSHOT-all.jar" /proxy/plugins/Jukebox.jar

COPY --from=build /work/build/libs/Komodo-*-all.jar /proxy/plugins/Komodo.jar
COPY /assets /proxy
CMD ["sh", "/proxy/entrypoint.sh"]