# This Dockerfile must be run with BuildKit enabled
# see https://docs.docker.com/engine/reference/builder/#buildkit

# Build the project into an executable JAR
FROM gradle:jdk17 as build
# Copy build files and source code
COPY . /work
WORKDIR /work
# Run gradle in the /work directory
RUN --mount=target=/home/gradle/.gradle,type=cache \
    /usr/bin/gradle --console=rich --warn --stacktrace --no-daemon --build-cache build

# Run Velocity with the built JAR in its plugins folder and expose port 25565
FROM eclipse-temurin:17

EXPOSE 25565

ARG VELOCITY_VERSION="3.1.2-SNAPSHOT"
ARG VELOCITY_BUILD_NUMBER=179
ARG REALIP_VERSION="2.6.0"
ARG VIA_VERSION="4.4.2"

WORKDIR /proxy
# Add Velocity using the version specified in the build arg
ADD "https://api.papermc.io/v2/projects/velocity/versions/$VELOCITY_VERSION/builds/$VELOCITY_BUILD_NUMBER/downloads/velocity-$VELOCITY_VERSION-$VELOCITY_BUILD_NUMBER.jar" /proxy/proxy.jar
# Add TCPShield's RealIP plugin
ADD "https://github.com/TCPShield/RealIP/releases/download/$REALIP_VERSION/TCPShield-$REALIP_VERSION.jar" /proxy/plugins/disabled/TCPShield-$REALIP_VERSION.jar
# Add ViaVersion to allow newer clients to connect
#ADD "https://github.com/ViaVersion/ViaVersion/releases/download/$VIA_VERSION/ViaVersion-${VIA_VERSION}.jar" /proxy/plugins/ViaVersion-$VIA_VERSION.jar
COPY --from=build /work/build/libs/Komodo-*-all.jar /proxy/plugins/Komodo.jar
COPY /assets /proxy
CMD ["sh", "/proxy/entrypoint.sh"]