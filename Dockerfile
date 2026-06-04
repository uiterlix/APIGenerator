FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src

RUN chmod +x ./gradlew && ./gradlew installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /opt/apigenerator

RUN groupadd --system app && useradd --system --gid app --create-home app

COPY --from=build /workspace/build/install/APIGenerator/ /opt/apigenerator/

EXPOSE 8080
USER app

ENTRYPOINT ["java", "-cp", "/opt/apigenerator/lib/*", "ApiGenerator"]
