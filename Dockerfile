FROM eclipse-temurin:25-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -ntp package

FROM eclipse-temurin:25-jre-jammy AS runtime
RUN groupadd --system safecrop && useradd --system --gid safecrop --home-dir /app safecrop
WORKDIR /app
COPY --from=build --chown=safecrop:safecrop /workspace/target/safecrop-api-1.0-SNAPSHOT.jar app.jar
USER safecrop
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
