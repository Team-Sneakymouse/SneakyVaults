plugins {
    id("java")
    id("io.papermc.paperweight.userdev") version "1.7.1"
    id("xyz.jpenilla.run-paper") version "2.3.0" // Adds runServer and runMojangMappedServer tasks for testing
}

group = "net.sneakymouse"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    maven("https://maven.playpro.com")
}

//paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION

dependencies {
    paperweight.paperDevBundle("1.20.6-R0.1-SNAPSHOT")

    compileOnly("net.coreprotect:coreprotect:22.4")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    compileJava {
        options.release = 21
    }
    assemble {
        dependsOn(reobfJar)
    }
}