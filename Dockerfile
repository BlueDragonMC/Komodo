# This Dockerfile must be run with BuildKit enabled
# see https://docs.docker.com/engine/reference/builder/#buildkit

# Build the project into an executable JAR
FROM gradle:9.8.2-jdk25@sha256:e46e15353757ef9c9be0197a243653e88d25ebcd4c3c841057cf839c11fcf898 AS build
# Copy build files and source code
COPY . /work
WORKDIR /work
# Run gradle in the /work directory
RUN --mount=target=/home/gradle/.gradle,type=cache \
    /usr/bin/gradle --console=plain --stacktrace --no-daemon build

FROM docker.io/badouralix/curl-jq:latest@sha256:7f3c18b2a7d72e8f6f89d5395f171d37a83d6015b6de718e5f4c5396305981b7 AS luckperms
# Download the latest version of LuckPerms
RUN curl -f "$(curl -f https://metadata.luckperms.net/data/all | jq -r '.downloads.velocity')" -o /tmp/luckperms.jar

# Run Velocity with the built JAR in its plugins folder and expose port 25565
FROM eclipse-temurin:25-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0

EXPOSE 25565
EXPOSE 8081

LABEL com.bluedragonmc.image=komodo
LABEL com.bluedragonmc.environment=development

WORKDIR /proxy

# Add Velocity
ADD "https://fill-data.papermc.io/v1/objects/0844bcabdcdb4f5b875906fd4c3365bc1bb76918e42bc595ed88ad195258dd75/velocity-4.3.1-45.jar" /proxy/proxy.jar

# Add LuckPerms for permissions
COPY --from=luckperms /tmp/luckperms.jar /proxy/plugins/LuckPerms.jar

# Pre-download and relocate LuckPerms' runtime libraries so the image does not need to
# fetch them on first start.
COPY --chmod=755 tools/preload-luckperms.sh /usr/local/bin/preload-luckperms
RUN preload-luckperms

COPY --from=build /work/build/libs/Komodo-*-all.jar /proxy/plugins/Komodo.jar
COPY /assets /proxy
CMD ["sh", "/proxy/entrypoint.sh"]