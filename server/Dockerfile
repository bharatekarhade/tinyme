FROM eclipse-temurin:25-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && ./mvnw --batch-mode --no-transfer-progress package -DskipTests
RUN cp target/tinyme-server-*.jar /workspace/app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system tinyme && useradd --system --gid tinyme tinyme \
    && mkdir -p /app/blobs && chown tinyme:tinyme /app/blobs
COPY --from=build --chown=tinyme:tinyme /workspace/app.jar /app/app.jar
USER tinyme
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
