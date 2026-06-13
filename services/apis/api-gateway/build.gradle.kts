plugins {
    id("org.springframework.boot")
    id("com.google.cloud.tools.jib")
}

dependencies {
    // econo-passport: Passport 도메인 + @PassportAuth (JNU-econovation/econo-passport)
    // compileOnly로 spring-boot-starter-web 선언되어 있어 Reactive 스택과 충돌 없음
    implementation("com.github.JNU-econovation:econo-passport:1.0.3")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.cloud:spring-cloud-starter-gateway")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("io.projectreactor:reactor-test")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2023.0.0")
    }
}

// Jib — Docker 데몬 없이 OCI 이미지 빌드·푸시 (Dockerfile 불필요)
// 이미지 좌표(네임스페이스)·자격증명·태그는 CI에서 환경변수/시스템 프로퍼티로 주입한다.
jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "${System.getenv("DOCKERHUB_NAMESPACE") ?: "econovation"}/api-gateway"
        tags = setOf("latest")
    }
    container {
        ports = listOf("8080")
        jvmFlags = listOf("-XX:MaxRAMPercentage=75.0")
        creationTime.set("USE_CURRENT_TIMESTAMP")
    }
}
