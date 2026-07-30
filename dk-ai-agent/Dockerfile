FROM maven:3.9-eclipse-temurin-25 AS build

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    MAVEN_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

WORKDIR /build
COPY pom.xml .
COPY .mvn ./.mvn
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8" \
    DJL_CACHE_DIR=/home/app/.djl.ai

WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app --create-home --home-dir /home/app app \
    && mkdir -p /app /home/app/.djl.ai /tmp/spring-ai-onnx-generative \
    && chown -R app:app /app /home/app /tmp/spring-ai-onnx-generative
COPY --from=build --chown=app:app /build/target/dk-ai-agent-0.0.1-SNAPSHOT.jar /app/app.jar

USER app
EXPOSE 8123
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=prod"]
