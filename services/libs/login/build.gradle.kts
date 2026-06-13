plugins {
    `java-library`
}

dependencies {
    implementation(project(":services:libs:member"))
    implementation(project(":services:libs:service-client"))
    implementation("org.springframework.boot:spring-boot-starter")
    // spring-security-oauth2-jose, spring-security-oauth2-jwt 의존 추가 금지
}
