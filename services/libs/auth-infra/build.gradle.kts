plugins {
    `java-library`
}

dependencies {
    implementation(project(":services:libs:auth-core"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
}
