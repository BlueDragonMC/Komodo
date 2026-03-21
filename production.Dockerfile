# syntax = docker/dockerfile:1
# This Dockerfile runs on the CI/CD pipeline when Komodo is being deployed.

# Build the project into an executable JAR
FROM docker.io/library/gradle:9.4-jdk25-alpine as build
# Copy build files and source code
COPY . /work
WORKDIR /work
# Run gradle in the /work directory
RUN /usr/bin/gradle --console=plain --info --stacktrace --no-daemon build

# Run Velocity with the built JAR in its plugins folder and expose port 25565
FROM docker.io/library/eclipse-temurin:25-jre-alpine

EXPOSE 25565

ARG REALIP_VERSION="2.8.1"

LABEL com.bluedragonmc.image=komodo
LABEL com.bluedragonmc.environment=production

WORKDIR /proxy

# Add Velocity
ADD "https://fill-data.papermc.io/v1/objects/b4eac4dc81dea6b183401c06dff44755f19da7fb4ec3495debde7fdcbb665e40/velocity-3.5.0-SNAPSHOT-583.jar" /proxy/proxy.jar

# Add TCPShield's RealIP plugin
ADD "https://github.com/TCPShield/RealIP/releases/download/$REALIP_VERSION/TCPShield-$REALIP_VERSION.jar" /proxy/plugins/disabled/TCPShield-$REALIP_VERSION.jar

# Add LuckPerms for permissions
ADD "https://download.luckperms.net/1626/velocity/LuckPerms-Velocity-5.5.38.jar" /proxy/plugins/LuckPerms.jar

COPY --from=build /work/build/libs/Komodo-*-all.jar /proxy/plugins/Komodo.jar
COPY /assets /proxy
CMD ["sh", "/proxy/entrypoint.sh"]
