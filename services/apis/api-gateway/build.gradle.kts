plugins {
    id("org.springframework.boot")
}

dependencies {
    // econo-passport: Passport 도메인 + @PassportAuth (JNU-econovation/econo-passport)
    // compileOnly로 spring-boot-starter-web 선언되어 있어 Reactive 스택과 충돌 없음
    implementation("com.github.JNU-econovation:econo-passport:1.0.0")
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
