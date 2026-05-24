plugins {
    java
    id("org.springframework.boot") version "3.2.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
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

spotless {
    format("misc") {
        target("**/*.gradle.kts", "**/*.md", "**/.gitignore")
        targetExclude(".release/*.*", "**/build/**")
        indentWithSpaces()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
