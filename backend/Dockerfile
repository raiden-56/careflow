# --- build ---
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B package -DskipTests

# --- runtime ---
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 careflow && mkdir -p /app/uploads && chown -R careflow /app
WORKDIR /app
COPY --from=build /src/target/careflow-hms-*.jar app.jar
USER careflow
ENV UPLOAD_DIR=/app/uploads JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
