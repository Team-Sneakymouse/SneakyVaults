import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    id("java")
    id("xyz.jpenilla.run-paper") version "3.1.0"
    `maven-publish`
}

group = "io.github.team-sneakymouse"

version = providers.exec {
    workingDir(rootDir)
    commandLine("git", "show", "-s", "--format=%ct:%h", "--abbrev=12", "HEAD")
}.standardOutput.asText.map { commit ->
    val (timestamp, hash) = commit.trim().split(":", limit = 2)
    val date = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC)
        .format(Instant.ofEpochSecond(timestamp.toLong()))
    "$date-$hash"
}.get()

val paperVersion = "26.2.build.+"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.playpro.com")
    maven("https://maven.sneakyrp.com/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly("net.coreprotect:coreprotect:22.4")
    compileOnly("io.github.team-sneakymouse:sneakyutils:2026.10.18-1f2e2f1ad8bf")

    testImplementation("io.papermc.paper:paper-api:$paperVersion")
    testImplementation("io.github.team-sneakymouse:sneakyutils:2026.10.18-1f2e2f1ad8bf")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
    withJavadocJar()
}

base {
    archivesName.set("SneakyVaults")
}

tasks {
    processResources {
        inputs.property("version", project.version.toString())
        filesMatching("plugin.yml") {
            expand("version" to project.version.toString())
        }
    }

    compileJava {
        options.release.set(25)
    }

    jar {
        archiveFileName.set("SneakyVaults-${project.version}.jar")
    }

    test {
        useJUnitPlatform()
    }

    runServer {
        minecraftVersion("26.2")
    }

    withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            addStringOption("Xdoclint:none", "-quiet")
        }
        isFailOnError = false
    }
}

publishing {
    publications {
        create<MavenPublication>("library") {
            artifactId = "sneakyvaults"
            from(components["java"])
            pom {
                name.set("SneakyVaults")
                description.set("Paper plugin for player vault storage with templates and admin tools.")
                url.set("https://github.com/Team-Sneakymouse/SneakyVaults")
                properties.put("sneakyrp.deployment.type", "paper-plugin")
                licenses {
                    license {
                        name.set("GNU General Public License v3.0")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("team-sneakymouse")
                        name.set("Team Sneakymouse")
                        url.set("https://github.com/Team-Sneakymouse")
                    }
                }
                scm {
                    url.set("https://github.com/Team-Sneakymouse/SneakyVaults")
                    connection.set("scm:git:https://github.com/Team-Sneakymouse/SneakyVaults.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "sneakyrp"
            url = uri("https://maven.sneakyrp.com/releases")
            credentials(PasswordCredentials::class)
            authentication {
                create<org.gradle.authentication.http.BasicAuthentication>("basic")
            }
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(tasks.check)
}
