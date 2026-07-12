# Runtime image for the optional compose `app` profile and a future containerized production.
# Local inner-loop development runs the jar NATIVELY (mvn / java) — this image only packages an
# already-built jar on a slim JRE, so it always works regardless of the Maven base-image tag.
# Build the jar first:  mvn -q package -DskipTests   (then: docker compose --profile app up --build)
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/strikebench.jar ./strikebench.jar
EXPOSE 7070
ENTRYPOINT ["java", "-jar", "/app/strikebench.jar"]
