FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src/ src/
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=build /build/target/urlshortener-agentic.jar app.jar

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
