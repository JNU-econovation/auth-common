plugins {
    `java-library`
}

dependencies {
    // 도메인 예외 클래스가 HttpStatus를 참조 — 컴파일 시점에만 필요
    // auth-common-lib 제거: econo-passport(JNU-econovation/econo-passport)로 분리됨
    compileOnly("org.springframework.boot:spring-boot-starter-web")
}
