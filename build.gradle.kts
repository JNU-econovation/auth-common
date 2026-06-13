plugins {
    java
    id("org.springframework.boot") version "3.2.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.google.cloud.tools.jib") version "3.4.4" apply false
    id("com.diffplug.spotless") version "6.25.0"
}

allprojects {
    group = "com.econo.auth"
    version = "1.0.0"

    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "com.diffplug.spotless")

    configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.2.2")
        }
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
    }

    dependencies {
        compileOnly("org.projectlombok:lombok")
        annotationProcessor("org.projectlombok:lombok")
        testCompileOnly("org.projectlombok:lombok")
        testAnnotationProcessor("org.projectlombok:lombok")

        testImplementation("org.springframework.boot:spring-boot-starter-test")
        testImplementation("org.junit.jupiter:junit-jupiter")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    spotless {
        java {
            googleJavaFormat("1.17.0")
            indentWithTabs(2)
            endWithNewline()
            removeUnusedImports()
            trimTrailingWhitespace()
            target("src/*/java/**/*.java")
        }
    }

    tasks.check {
        dependsOn(tasks.spotlessCheck)
    }

    tasks.register("format") {
        group = "formatting"
        description = "코드 포맷팅 적용"
        dependsOn(tasks.spotlessApply)
    }
}

// pre-commit 훅 자동 설치 — git clone 후 ./gradlew build 또는 ./gradlew installGitHooks 실행
tasks.register("installGitHooks") {
    group = "setup"
    description = "Spotless 포맷 검사 pre-commit 훅 설치"
    doLast {
        val hooksDir = file("${rootProject.projectDir}/.git/hooks")
        hooksDir.mkdirs()
        val preCommit = file("${hooksDir}/pre-commit")
        preCommit.writeText(
            """
            #!/bin/sh
            echo "🔍 Spotless 포맷 검사 중..."
            ./gradlew spotlessCheck -q 2>&1
            if [ ${'$'}? -ne 0 ]; then
              echo ""
              echo "❌ 포맷 오류: ./gradlew spotlessApply 실행 후 다시 커밋하세요."
              exit 1
            fi
            echo "✅ 포맷 OK"
            """.trimIndent()
        )
        preCommit.setExecutable(true)
        println("✅ pre-commit 훅 설치 완료: ${preCommit.absolutePath}")
    }
}

// build 시 자동으로 훅 설치
tasks.named("build") {
    dependsOn("installGitHooks")
}

spotless {
    format("misc") {
        target("**/*.gradle.kts", "**/*.md", "**/.gitignore")
        targetExclude(".release/*.*", "**/build/**")
        indentWithSpaces()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
