# ---- build stage ----
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn --batch-mode package -DskipTests

# ---- run stage ----
FROM eclipse-temurin:17-jre

RUN groupadd --gid 1337 sidecar \
    && useradd --uid 1337 --gid 1337 --no-create-home --shell /usr/sbin/nologin sidecar

WORKDIR /app

COPY --from=build /workspace/target/netty-sidecar-proxy-1.0.0.jar app.jar

EXPOSE 15001

USER 1337:1337

ENTRYPOINT ["java", "-jar", "app.jar"]
