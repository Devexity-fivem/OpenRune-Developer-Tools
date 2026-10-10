plugins {
    java
}

group = "com.openrune"

// CI builds are versioned by run number and match the release tag (v<version>);
// local builds get "-local" which disables the in-client update check.
val baseVersion = "0.1.0"
val runNumber: String? = System.getenv("GITHUB_RUN_NUMBER")
version = if (runNumber != null) "$baseVersion-$runNumber" else "$baseVersion-local"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

// Local builds compile against the Fluxious client's shaded jar; when it isn't
// present (CI), fall back to the upstream RuneLite client artifact.
// Override the path with -PfluxClientJar=...
val fluxClientJar = providers.gradleProperty("fluxClientJar")
    .getOrElse("D:/RSPS/Fluxious/FluxRSClient/runelite-client/build/libs/runelite-client-shaded.jar")
// java.io.File, not Gradle file(): on Linux CI the default Windows path
// ("D:/...") would be parsed as a URL and fail the build
val useLocalClient = File(fluxClientJar).exists()

repositories {
    if (!useLocalClient) {
        maven {
            url = uri("https://repo.runelite.net")
            content {
                includeGroupByRegex("net\\.runelite.*")
            }
        }
    }
    mavenCentral()
}

dependencies {
    if (useLocalClient) {
        compileOnly(files(fluxClientJar))
    } else {
        compileOnly("net.runelite:client:latest.release")
    }
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
}

tasks.jar {
    archiveFileName.set("OpenRune-Developer-Tools.jar")
    manifest {
        attributes["Implementation-Title"] = "OpenRune-Developer-Tools"
        attributes["Implementation-Version"] = project.version
    }
}

// Copy the built jar into every client's sideloaded-plugins folder.
// Runs automatically after jar; skipped on CI.
val deploy = tasks.register("deploy") {
    dependsOn(tasks.jar)
    onlyIf { System.getenv("GITHUB_ACTIONS") == null }
    doLast {
        val jar = tasks.jar.get().archiveFile.get().asFile
        val locked = mutableListOf<File>()
        for (clientDir in listOf(".runelite", ".rsprox", ".fluxious")) {
            val target = File(System.getProperty("user.home"), "$clientDir/sideloaded-plugins")
            target.mkdirs()
            // A running client holds its jar open on Windows. Deploy to the other
            // clients first, then report every target that could not be replaced.
            try {
                jar.copyTo(File(target, jar.name), overwrite = true)
                println("Deployed to $target")
            } catch (e: java.io.IOException) {
                locked += target
            }
        }
        if (locked.isNotEmpty()) {
            throw GradleException(
                "Could not replace the jar in ${locked.joinToString()} - a running client " +
                    "has it open and will keep the old build until it restarts. Close it and run again."
            )
        }
    }
}

tasks.jar {
    finalizedBy(deploy)
}
