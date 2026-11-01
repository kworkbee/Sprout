# Build the jar first: ./gradlew bootJar
FROM eclipse-temurin:25-jre
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin sprout
COPY build/libs/sprout.jar /app/sprout.jar
USER 10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/sprout.jar"]
