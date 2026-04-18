plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
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
