plugins {
    id("org.springframework.boot")
    id("com.google.cloud.tools.jib")
}

dependencies {
    implementation("com.github.JNU-econovation:econo-passport:1.0.3")
    implementation(project(":services:libs:member"))
    implementation(project(":services:libs:service-client"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-authorization-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.springframework.security:spring-security-test")
}

// Jib — Docker 데몬 없이 OCI 이미지 빌드·푸시 (Dockerfile 불필요)
// 이미지 좌표(네임스페이스)·자격증명·태그는 CI에서 환경변수/시스템 프로퍼티로 주입한다.
//   - DOCKERHUB_NAMESPACE (env): Docker Hub 계정/조직명. 미설정 시 로컬 빌드용 기본값.
//   - -Djib.to.tags=latest,<sha> : 태그(CI에서 지정)
//   - -Djib.to.auth.username / -Djib.to.auth.password : Docker Hub 자격증명(CI 시크릿)
jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "${System.getenv("DOCKERHUB_NAMESPACE") ?: "econovation"}/auth-api"
        tags = setOf("latest")
    }
    container {
        ports = listOf("8080")
        jvmFlags = listOf("-XX:MaxRAMPercentage=75.0")
        creationTime.set("USE_CURRENT_TIMESTAMP")
    }
}
