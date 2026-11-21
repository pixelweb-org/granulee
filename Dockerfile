# Builds Granulee from source with nothing but Docker: the web page first, then the jar with the page inside.
#
#   docker build -t granulee .
#   docker run -d --name granulee -p 8080:8080 -v granulee-data:/data granulee
#
# The release workflow builds the same image with Jib instead (see docs/development.md).

FROM node:24-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS backend
WORKDIR /src
COPY version.txt ./
COPY backend/ backend/
# What `-PwithFrontend` does in the Gradle build: the built page becomes the jar's static files.
COPY --from=frontend /src/frontend/dist/ backend/src/main/resources/static/
WORKDIR /src/backend
RUN ./gradlew bootJar --no-daemon --console=plain

FROM eclipse-temurin:21-jre
ENV GRANULEE_DATA_DIR=/data
VOLUME /data
EXPOSE 8080
COPY --from=backend /src/backend/build/libs/granulee-*.jar /app/granulee.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/granulee.jar"]
