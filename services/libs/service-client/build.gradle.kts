plugins {
    `java-library`
}

dependencies {
    // JPA 엔티티 + Spring Data, AuditingEntityListener
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // 예외 클래스의 @ResponseStatus — spring-web 에 포함
    implementation("org.springframework.boot:spring-boot-starter-web")
    // SasClientRegistrarAdapter: RegisteredClient, RegisteredClientRepository
    implementation("org.springframework.boot:spring-boot-starter-oauth2-authorization-server")
    // JpaAuditingConfig 공유 (common-infra에 @EnableJpaAuditing AutoConfiguration 선언됨)
    implementation(project(":services:libs:common-infra"))
}
