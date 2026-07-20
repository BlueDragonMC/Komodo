# This Dockerfile must be run with BuildKit enabled
# see https://docs.docker.com/engine/reference/builder/#buildkit

# Build the project into an executable JAR
FROM gradle:9.5.1-jdk25 AS build
# Copy build files and source code
COPY . /work
WORKDIR /work
# Run gradle in the /work directory
RUN --mount=target=/home/gradle/.gradle,type=cache \
    /usr/bin/gradle --console=plain --stacktrace --no-daemon build

FROM docker.io/badouralix/curl-jq:latest@sha256:3670578737d1d2019b10a4f9e607284101feb9fdd6b0478e78ea87bf61d96145 AS luckperms
# Download the latest version of LuckPerms
RUN curl -f "$(curl -f https://metadata.luckperms.net/data/all | jq -r '.downloads.velocity')" -o /tmp/luckperms.jar

# Run Velocity with the built JAR in its plugins folder and expose port 25565
FROM eclipse-temurin:25-jre

EXPOSE 25565

ARG REALIP_VERSION="2.8.1"

LABEL com.bluedragonmc.image=komodo
LABEL com.bluedragonmc.environment=development

WORKDIR /proxy

# Add Velocity
ADD "https://fill-data.papermc.io/v1/objects/635ffe27b4fe1b97e61479012121d4e7c61a9eec99e6bd5a1f923053c2a259ce/velocity-4.1.0-SNAPSHOT-9.jar" /proxy/proxy.jar

# Add TCPShield's RealIP plugin
ADD "https://github.com/TCPShield/RealIP/releases/download/$REALIP_VERSION/TCPShield-$REALIP_VERSION.jar" /proxy/plugins/disabled/TCPShield-$REALIP_VERSION.jar

# Add LuckPerms for permissions
COPY --from=luckperms /tmp/luckperms.jar /proxy/plugins/LuckPerms.jar

COPY --from=build /work/build/libs/Komodo-*-all.jar /proxy/plugins/Komodo.jar
COPY /assets /proxy
CMD ["sh", "/proxy/entrypoint.sh"]