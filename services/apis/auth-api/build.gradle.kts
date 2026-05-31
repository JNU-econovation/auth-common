plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":services:libs:member-core"))
    implementation(project(":services:libs:member-infra"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-authorization-server")
    implementation("org.springframework.session:spring-session-jdbc")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}
