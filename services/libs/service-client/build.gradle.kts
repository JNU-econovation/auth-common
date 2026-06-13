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
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    // @DataJpaTest 는 실제 DB(replace=NONE)라 ddl-auto=none 이므로 스키마를 Flyway 로 만든다.
    testImplementation("org.flywaydb:flyway-core")
}

// 마이그레이션 SQL 의 단일 진실 소스는 레포 루트 db/migration 이다(어느 모듈도 소유하지 않음).
// V9/V10 신규 마이그레이션 포함하여 테스트 클래스패스로 복사한다.
tasks.named<Copy>("processTestResources") {
    from("$rootDir/db/migration") {
        into("db/migration")
    }
}
