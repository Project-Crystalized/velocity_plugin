plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.3"
}

group = "gg.crystalized.velocity"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
		implementation("org.xerial:sqlite-jdbc:3.47.0.0")
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
	shadowJar {
		archiveClassifier.set("")
	}
}
tasks {
	build {
    dependsOn("shadowJar")
	}
}
