plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":services:libs:auth-core"))
    implementation(project(":services:libs:auth-infra"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}
