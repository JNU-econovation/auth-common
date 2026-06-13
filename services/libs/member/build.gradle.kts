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
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    // @DataJpaTest 는 실제 DB(replace=NONE)라 ddl-auto=none 이므로 스키마를 Flyway 로 만든다.
    // 운영 마이그레이션은 flyway 컨테이너가 담당하고, 여기선 테스트 전용으로만 flyway 를 둔다.
    testImplementation("org.flywaydb:flyway-core")
}

// 마이그레이션 SQL 의 단일 진실 소스는 레포 루트 db/migration 이다(어느 모듈도 소유하지 않음).
// 테스트 클래스패스로 복사해 @DataJpaTest 가 실제 마이그레이션으로 스키마를 구성한다.
tasks.named<Copy>("processTestResources") {
    from("$rootDir/db/migration") {
        into("db/migration")
    }
}
