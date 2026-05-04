import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget // Importación necesaria para el nuevo DSL

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.2.21"
}

group = "com.veabsoluta"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Persistencia y Base de Datos (Requisito: PostgreSQL) 
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Módulo de Ingesta 
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Resilience4j para Circuit Breaker y retry
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
    implementation("io.github.resilience4j:resilience4j-retry:2.2.0")

    // Kotlin y JSON
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Object Storage (MinIO / S3 compatible)
    implementation("software.amazon.awssdk:s3:2.29.0")

    // HTTP Client para inferencia en la nube
    implementation("org.springframework.boot:spring-boot-starter-webflux") // WebClient reactivo
    implementation("org.springframework.boot:spring-boot-starter-web") // RestClient sincrónico

    //Cloudinary
    implementation("com.cloudinary:cloudinary-http44:1.36.0")

    // Rate Limiting
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    implementation("jakarta.servlet:jakarta.servlet-api:6.0.0")

    // Swagger / OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // Logs estructurados
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        // Alineado a Java 17 para Render
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile> {
    // Alineado a Java 17 para Render
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}