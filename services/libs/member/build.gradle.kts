plugins {
    `java-library`
}

dependencies {
    // JPA 엔티티 + Spring Data, AuditingEntityListener
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // BCryptPasswordEncoder
    implementation("org.springframework.security:spring-security-crypto")
    // JPA Auditing AutoConfiguration 전이 활성화
    api(project(":services:libs:common-infra"))
    // Flyway 마이그레이션
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}
