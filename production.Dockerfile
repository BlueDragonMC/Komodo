# syntax = docker/dockerfile:1
# This Dockerfile runs on the CI/CD pipeline when Komodo is being deployed.

# Build the project into an executable JAR
FROM docker.io/library/gradle:7.4.2-jdk17-alpine as build
# Copy build files and source code
COPY . /work
WORKDIR /work
# Run gradle in the /work directory
RUN /usr/bin/gradle --console=plain --info --stacktrace --no-daemon build

# Run Velocity with the built JAR in its plugins folder and expose port 25565
FROM docker.io/library/eclipse-temurin:17-jre-alpine

EXPOSE 25565

ARG VELOCITY_VERSION="3.1.2-SNAPSHOT"
ARG VELOCITY_BUILD_NUMBER=179
ARG REALIP_VERSION="2.6.0"
ARG VIA_VERSION="4.5.1"
ARG LP_VERSION="5.4.55"

WORKDIR /proxy
# Add Velocity using the version specified in the build arg
ADD "https://api.papermc.io/v2/projects/velocity/versions/$VELOCITY_VERSION/builds/$VELOCITY_BUILD_NUMBER/downloads/velocity-$VELOCITY_VERSION-$VELOCITY_BUILD_NUMBER.jar" /proxy/proxy.jar
# Add TCPShield's RealIP plugin
ADD "https://github.com/TCPShield/RealIP/releases/download/$REALIP_VERSION/TCPShield-$REALIP_VERSION.jar" /proxy/plugins/disabled/TCPShield-$REALIP_VERSION.jar
# Add LuckPerms for permissions
ADD "https://download.luckperms.net/1463/velocity/LuckPerms-Velocity-$LP_VERSION.jar" /proxy/plugins/LuckPerms-$LP_VERSION.jar
# Add the Jukebox plugin
ADD "https://github.com/BlueDragonMC/Jukebox/releases/download/latest/Jukebox-1.0-SNAPSHOT-all.jar" /proxy/plugins/Jukebox.jar
# Add ViaVersion to allow newer clients to connect
#ADD "https://github.com/ViaVersion/ViaVersion/releases/download/$VIA_VERSION/ViaVersion-${VIA_VERSION}.jar" /proxy/plugins/ViaVersion-$VIA_VERSION.jar
COPY --from=build /work/build/libs/Komodo-*-all.jar /proxy/plugins/Komodo.jar
COPY /assets /proxy
CMD ["sh", "/proxy/entrypoint.sh"]