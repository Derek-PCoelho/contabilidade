# Server central do Folhas da Michelly (Java 25 + Spring Boot). Construir a partir da raiz do repositório:
#   docker build -f deploy/java/Dockerfile -t folhas-server:1.0.0 .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY java/ ./
RUN ./gradlew --no-daemon -q :server:bootJar -x test

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system folhas && useradd --system --gid folhas --home /app --shell /usr/sbin/nologin folhas
WORKDIR /app
COPY --from=build /src/server/build/libs/folhas-server.jar /app/folhas-server.jar
USER folhas
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD ["curl", "-fsS", "http://127.0.0.1:8080/health/ready"]
ENTRYPOINT ["java", "-jar", "/app/folhas-server.jar"]
