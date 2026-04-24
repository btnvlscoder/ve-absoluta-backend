# Etapa 1: Compilación
FROM gradle:8-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle build -x test

# Etapa 2: Ejecución
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]