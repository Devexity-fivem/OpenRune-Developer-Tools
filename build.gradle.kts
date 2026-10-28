plugins {
    java
}

group = "com.openrune"

// CI builds are versioned by run number and match the release tag (v<version>);
// local builds get "-local" which disables the in-client update check.
val baseVersion = "0.2.0"
val runNumber: String? = System.getenv("GITHUB_RUN_NUMBER")
version = if (runNumber != null) "$baseVersion-$runNumber" else "$baseVersion-local"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

// Builds against the upstream RuneLite client artifact. Point -PclientJar at a
// client's own shaded jar to compile against that instead, when it exposes API
// this plugin uses.
// java.io.File, not Gradle file(): an absolute Windows path would be parsed as a
// URL and fail the build.
val clientJar = (providers.gradleProperty("clientJar").orNull
    ?: providers.gradleProperty("fluxClientJar").orNull)?.takeIf { it.isNotBlank() }
val useLocalClient = clientJar != null && File(clientJar).exists()

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
        compileOnly(files(clientJar!!))
        testImplementation(files(clientJar!!))
    } else {
        compileOnly("net.runelite:client:latest.release")
        testImplementation("net.runelite:client:latest.release")
    }
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    archiveFileName.set("OpenRune-Developer-Tools.jar")
    manifest {
        attributes["Implementation-Title"] = "OpenRune-Developer-Tools"
        attributes["Implementation-Version"] = project.version
    }
}

// Every client that has ever run has created its own sideloaded-plugins folder,
// so deploy to whichever ones exist rather than to a hardcoded list - a fork
// using its own home directory is picked up with no configuration. Override with
// -PsideloadDirs=.runelite,.myclient to target an exact set.
fun sideloadTargets(): List<File> {
    val home = File(System.getProperty("user.home"))
    val requested = providers.gradleProperty("sideloadDirs").orNull
    if (requested != null) {
        return requested.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .map { File(home, "$it/sideloaded-plugins") }
    }
    val detected = home.listFiles { f: File -> File(f, "sideloaded-plugins").isDirectory }
        ?.map { File(it, "sideloaded-plugins") }
        ?.sortedBy { it.path }
        .orEmpty()
    // Nothing installed yet: fall back to RuneLite's own folder and create it.
    return detected.ifEmpty { listOf(File(home, ".runelite/sideloaded-plugins")) }
}

// Copy the built jar into every detected client's sideloaded-plugins folder.
// Deliberately NOT wired into `jar`: compiling and testing must never overwrite
// client installs. Run `gradlew deploy` explicitly to install the build.
val deploy = tasks.register("deploy") {
    dependsOn(tasks.jar)
    onlyIf { System.getenv("GITHUB_ACTIONS") == null }
    doLast {
        val jar = tasks.jar.get().archiveFile.get().asFile
        val locked = mutableListOf<File>()
        val targets = sideloadTargets()
        for (target in targets) {
            target.mkdirs()
            // A running client holds its jar open on Windows. Deploy to the other
            // clients first, then report every target that could not be replaced.
            try {
                jar.copyTo(File(target, jar.name), overwrite = true)
                println("Deployed ${jar.name} to $target")
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

tasks.test {
    useJUnitPlatform()
}
