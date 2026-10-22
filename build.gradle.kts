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
    maven{
        url = uri("https://repo.opencollab.dev/main/")
    }
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")

    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")

    compileOnly("org.geysermc.floodgate:api:2.2.3-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
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
	test {
		useJUnitPlatform()
		testLogging {
			events("passed", "failed", "skipped")
		}
	}
}
