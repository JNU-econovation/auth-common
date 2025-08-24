plugins {
    java
    `java-library`
    `maven-publish`
    id("org.springframework.boot") version "3.2.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "6.25.0"
}

group = "com.github.JNU-econovation"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }

    withJavadocJar()
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    // Spring Boot Starter
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-validation")

    // JSON Processing
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.jar {
    enabled = true
    archiveClassifier = ""
}

tasks.bootJar {
    enabled = false
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            // 의존성 버전 문제 해결
            versionMapping {
                usage("java-api") {
                    fromResolutionOf("runtimeClasspath")
                }
                usage("java-runtime") {
                    fromResolutionResult()
                }
            }

            pom {
                name.set("ECONO Auth Common Library")
                description.set("Authentication and authorization library for ECONO microservices with @PassportAuth annotation support")
                url.set("https://github.com/JNU-econovation/auth-common")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("econo-team")
                        name.set("ECONO Development Team")
                        organization.set("Econovation")
                        organizationUrl.set("https://econovation.kr")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/JNU-econovation/auth-common.git")
                    developerConnection.set("scm:git:ssh://github.com/JNU-econovation/auth-common.git")
                    url.set("https://github.com/JNU-econovation/auth-common")
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/JNU-econovation/auth-common")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_USERNAME")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

// Spotless 설정 (코드 포맷팅)
spotless {
    java {
        googleJavaFormat("1.17.0")
        indentWithTabs(2)
        endWithNewline()
        removeUnusedImports()
        trimTrailingWhitespace()
        target("src/*/java/**/*.java")
    }

    format("misc") {
        target("**/*.gradle.kts", "**/*.md", "**/.gitignore")
        targetExclude(".release/*.*")
        indentWithSpaces()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// 빌드시 Spotless 체크 자동 실행
tasks.check {
    dependsOn(tasks.spotlessCheck)
}

// Spotless 포맷팅 태스크
tasks.register("format") {
    group = "formatting"
    description = "코드 포맷팅 적용"
    dependsOn(tasks.spotlessApply)
}
