plugins {
    `java-library`
}

dependencies {
    api(project(":services:libs:auth-common-lib"))
    // 도메인 예외 클래스가 HttpStatus를 참조 — 컴파일 시점에만 필요
    compileOnly("org.springframework.boot:spring-boot-starter-web")
}
