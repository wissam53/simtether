# Deployable image for the SimTether splice relay.
# Build the jar first:  gradlew :relay:fatJar
# Then deploy from this directory (fly deploy / docker build).
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY build/libs/simtether-relay.jar app.jar
ENV PORT=44711
EXPOSE 44711
# ACCESS_TOKEN is injected at runtime (fly secrets / docker -e).
CMD ["java", "-jar", "app.jar"]
