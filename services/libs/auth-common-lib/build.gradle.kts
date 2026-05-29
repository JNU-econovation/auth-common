plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    // Passport 직렬화에 필요한 최소 의존성 — 모든 소비자(MVC/Reactive)에서 사용 가능
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // PassportArgumentResolver, AuthAutoConfiguration은 Spring MVC 전용
    // MVC 소비자는 자체 spring-boot-starter-web이 있으므로 compileOnly로 충분
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    compileOnly("org.springframework.boot:spring-boot-starter-validation")
}

java {
    withJavadocJar()
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

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
