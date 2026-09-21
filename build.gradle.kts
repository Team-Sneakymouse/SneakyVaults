plugins {
    id("java")
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "net.sneakymouse"
version = "1.1-SNAPSHOT"

val paperVersion = "26.2.build.+"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.playpro.com")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")

    compileOnly("net.coreprotect:coreprotect:22.4")

    testImplementation("io.papermc.paper:paper-api:$paperVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    compileJava {
        options.release.set(25)
    }
    test {
        useJUnitPlatform()
    }
    runServer {
        minecraftVersion("26.2")
    }
}
