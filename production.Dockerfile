# syntax = docker/dockerfile:1
# This Dockerfile runs on the CI/CD pipeline when the Server is being deployed.
# It is much slower because `RUN mount=type=cache` is not supported by BuildKit,
# Buildah, or Kaniko in a Kubernetes cluster (docker-in-docker environment)

# Build the project into an executable JAR
FROM gradle:jdk17 as build
# Copy build files and source code
COPY . /work
WORKDIR /work
# Run gradle in the /work directory
RUN /usr/bin/gradle --console=rich --warn --stacktrace --no-daemon build

# Run Velocity with the built JAR in its plugins folder and expose port 25565
FROM eclipse-temurin:17

EXPOSE 25565

ARG VELOCITY_VERSION="3.1.2-SNAPSHOT"
ARG VELOCITY_BUILD_NUMBER=162
ARG REALIP_VERSION="2.6.0"
ARG MC_MONITOR_VERSION="0.10.6"

# Add mc-monitor for container healthchecks
ADD https://github.com/itzg/mc-monitor/releases/download/$MC_MONITOR_VERSION/mc-monitor_${MC_MONITOR_VERSION}_linux_amd64.tar.gz /tmp/mc-monitor.tgz
RUN tar -xf /tmp/mc-monitor.tgz -C /usr/local/bin mc-monitor && rm /tmp/mc-monitor.tgz

HEALTHCHECK --start-period=10s --interval=5s --retries=4 CMD sh /proxy/health.sh

WORKDIR /proxy
# Add Velocity using the version specified in the build arg
ADD "https://api.papermc.io/v2/projects/velocity/versions/$VELOCITY_VERSION/builds/$VELOCITY_BUILD_NUMBER/downloads/velocity-$VELOCITY_VERSION-$VELOCITY_BUILD_NUMBER.jar" /proxy/proxy.jar
# Add TCPShield's RealIP plugin
ADD "https://github.com/TCPShield/RealIP/releases/download/$REALIP_VERSION/TCPShield-$REALIP_VERSION.jar" /proxy/plugins/disabled/TCPShield-$REALIP_VERSION.jar
COPY --from=build /work/build/libs/Komodo-*.jar /proxy/plugins/Komodo.jar
COPY /assets /proxy
CMD ["sh", "/proxy/entrypoint.sh"]