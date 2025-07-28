# syntax = docker/dockerfile:1
# This Dockerfile runs on the CI/CD pipeline when Komodo is being deployed.

# Build the project into an executable JAR
FROM docker.io/library/gradle:8.13-jdk21-alpine as build
# Copy build files and source code
COPY . /work
WORKDIR /work
# Run gradle in the /work directory
RUN /usr/bin/gradle --console=plain --info --stacktrace --no-daemon build

# Run Velocity with the built JAR in its plugins folder and expose port 25565
FROM docker.io/library/eclipse-temurin:21-jre-alpine

EXPOSE 25565

ARG REALIP_VERSION="2.8.1"

LABEL com.bluedragonmc.image=komodo
LABEL com.bluedragonmc.environment=production

WORKDIR /proxy

# Add Velocity
ADD "https://fill-data.papermc.io/v1/objects/f82780ce33035ebe3d6ea7981f0e6e8a3e41a64f2080ef5c0f1266fada03cbee/velocity-3.4.0-SNAPSHOT-522.jar" /proxy/proxy.jar

# Add TCPShield's RealIP plugin
ADD "https://github.com/TCPShield/RealIP/releases/download/$REALIP_VERSION/TCPShield-$REALIP_VERSION.jar" /proxy/plugins/disabled/TCPShield-$REALIP_VERSION.jar

# Add LuckPerms for permissions
ADD "https://download.luckperms.net/1595/velocity/LuckPerms-Velocity-5.5.10.jar" /proxy/plugins/LuckPerms.jar

COPY --from=build /work/build/libs/Komodo-*-all.jar /proxy/plugins/Komodo.jar
COPY /assets /proxy
CMD ["sh", "/proxy/entrypoint.sh"]
