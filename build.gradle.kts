/* This is free and unencumbered software released into the public domain*/

import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/* ------------------------------ Plugins ------------------------------ */
plugins {
    id("java") // Tell gradle this is a java project.
    id("java-library") // Import helper for source-based libraries.
    kotlin("jvm") version "2.1.21" // Import kotlin jvm plugin for kotlin/java integration.
    id("com.diffplug.spotless") version "7.0.4" // Import auto-formatter.
    id("com.gradleup.shadow") version "8.3.6" // Import shadow API.
    eclipse // Import eclipse plugin for IDE integration.
}

/* --------------------------- JDK / Kotlin ---------------------------- */
java {
    sourceCompatibility = JavaVersion.VERSION_17 // Compile with JDK 17 compatibility.
    toolchain { // Select Java toolchain.
        languageVersion.set(JavaLanguageVersion.of(17)) // Use JDK 17.
        vendor.set(JvmVendorSpec.GRAAL_VM) // Use GraalVM CE.
    }
}

kotlin { jvmToolchain(17) }

/* ----------------------------- Metadata ------------------------------ */
group = "net.trueog.kotlintemplate-og"

version = "1.0"

val apiVersion = "1.19"

eclipse { project { name = "KotlinTemplate-OG-Plugin" } }

/* -------- Kotlin subprojects -> jars on Eclipse/compile classpath ----- */
val kotlinPluginProjects = listOf(":libs:DiamondBank-OG", ":libs:Chat-OG") // <— single place

kotlinPluginProjects.forEach { evaluationDependsOn(it) } // Ensure subprojects are evaluated first.

val ideLibDir = layout.buildDirectory.dir("ide-libs")
val hashRegex = Regex("-[0-9a-fA-F]{10}(?=\\.jar$)") // Black magic.

/* --------------------------- IDE-only configuration ------------------- */
val ideLibs: Configuration by
    configurations.creating {
        isCanBeResolved = true
        isCanBeConsumed = false
    }

/* Tell Eclipse/Buildship to include the IDE jars                        */
eclipse { classpath { plusConfigurations += ideLibs } }

/* ----------------------------- Resources ----------------------------- */
tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to version, "apiVersion" to apiVersion)
    inputs.properties(props) // Indicates to rerun if version changes.
    filesMatching("plugin.yml") { expand(props) }
    from("LICENSE") { into("/") } // Bundle licenses into jarfiles.
}

/* ---------------------------- Repos ---------------------------------- */
repositories {
    mavenCentral()
    gradlePluginPortal()
    maven { url = uri("https://repo.purpurmc.org/snapshots") }
    maven { url = uri("file://${System.getProperty("user.home")}/.m2/repository") }
    System.getProperty("SELF_MAVEN_LOCAL_REPO")?.let { // TrueOG Bootstrap mavenLocal().
        val dir = file(it)
        if (dir.isDirectory) {
            println("Using SELF_MAVEN_LOCAL_REPO at: $it")
            maven { url = uri("file://${dir.absolutePath}") }
        } else {
            logger.error("TrueOG Bootstrap not found, defaulting to ~/.m2 for mavenLocal()")
        }
    } ?: logger.error("TrueOG Bootstrap not found, defaulting to ~/.m2 for mavenLocal()")
}

/* ---------------------- Java project deps ---------------------------- */
dependencies {
    compileOnly("org.purpurmc.purpur:purpur-api:1.19.4-R0.1-SNAPSHOT")
    compileOnly("io.github.miniplaceholders:miniplaceholders-api:2.2.3")
    implementation("org.jetbrains.kotlin:kotlin-stdlib") // Import Kotlin standard library.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") // Import Kotlin async library.
    compileOnlyApi(project(":libs:Utilities-OG"))
    compileOnlyApi(project(":libs:GxUI-OG"))

    // Import TrueOG Network Kotlin-based APIs as jars (shadow output) so Eclipse code later can treat them as jar deps.
    kotlinPluginProjects.forEach { compileOnlyApi(project(mapOf("path" to it, "configuration" to "shadow"))) }
}

/* --- copy shaded jars & make Eclipse see them as individual libs ---- */
val copyTasks = mutableListOf<TaskProvider<Copy>>()

kotlinPluginProjects.forEach { path ->
    val sub = project(path)
    val shadowJarProv = sub.tasks.named("shadowJar")
    val copyTask =
        tasks.register<Copy>("ideCopy${sub.name.replaceFirstChar(Char::titlecase)}") {
            dependsOn(shadowJarProv)
            from(shadowJarProv)
            into(ideLibDir)
            rename { it.replace(hashRegex, "") } // Remove git commit hash from jarfile.
        }
    copyTasks += copyTask
}

/* Ensure the jars exist before .classpath is generated */
tasks.named("eclipseClasspath").configure { dependsOn(copyTasks) }

/* Supply those jars to the ideLibs configuration (after evaluation) */
afterEvaluate { dependencies { add("ideLibs", fileTree(ideLibDir) { include("*.jar") }) } }

/* ------------------ FINAL patcher: runs AFTER :eclipse ---------------- */
val injectIdeLibs =
    tasks.register("injectIdeLibs") {
        dependsOn("eclipse") // run after all eclipse files are generated
        doLast {
            val cpFile = file(".classpath")
            if (!cpFile.exists()) return@doLast

            val jars = fileTree(ideLibDir.get()) { include("*.jar") }.files.sortedBy { it.name }
            if (jars.isEmpty()) return@doLast

            // Parse DOM
            val dbf = DocumentBuilderFactory.newInstance()
            val doc = dbf.newDocumentBuilder().parse(cpFile)
            val root = doc.documentElement

            // Helper to see if an entry already exists
            fun exists(path: String): Boolean =
                root.getElementsByTagName("classpathentry").let { list ->
                    (0 until list.length).any { i ->
                        val n = list.item(i)
                        val kind = n.attributes?.getNamedItem("kind")?.nodeValue
                        val p = n.attributes?.getNamedItem("path")?.nodeValue
                        kind == "lib" && p == path
                    }
                }

            // Remove any entry that points to ide-libs dir (folder or jars) to avoid dupes
            val toRemove = mutableListOf<org.w3c.dom.Node>()
            val list = root.getElementsByTagName("classpathentry")
            val dirPath = ideLibDir.get().asFile.absolutePath
            for (i in 0 until list.length) {
                val n = list.item(i)
                val kind = n.attributes?.getNamedItem("kind")?.nodeValue
                val p = n.attributes?.getNamedItem("path")?.nodeValue ?: ""
                if (kind == "lib" && (p == dirPath || p.startsWith("$dirPath/"))) {
                    toRemove += n
                }
            }
            toRemove.forEach { root.removeChild(it) }

            // Append our jar entries LAST
            jars.forEach { f ->
                val abs = f.absolutePath
                if (!exists(abs)) {
                    val entry = doc.createElement("classpathentry")
                    entry.setAttribute("kind", "lib")
                    entry.setAttribute("path", abs)
                    root.appendChild(entry)
                }
            }

            // Write back pretty
            val tf =
                TransformerFactory.newInstance().newTransformer().apply {
                    setOutputProperty(OutputKeys.INDENT, "yes")
                    setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "1")
                    setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
                    setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                }
            Files.newBufferedWriter(cpFile.toPath()).use { w -> tf.transform(DOMSource(doc), StreamResult(w)) }
        }
    }

tasks.named("eclipse").configure { finalizedBy(injectIdeLibs) }

/* ---------------------- Reproducible jars ---------------------------- */
tasks.withType<AbstractArchiveTask>().configureEach { // Ensure reproducible .jars
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

/* ----------------------------- Shadow -------------------------------- */
tasks.shadowJar {
    exclude("io.github.miniplaceholders.*") // Exclude the MiniPlaceholders package from being shadowed.
    archiveClassifier.set("") // Use empty string instead of null.
    minimize()
}

tasks.jar { archiveClassifier.set("part") } // Applies to root jarfile only.

tasks.build { dependsOn(tasks.spotlessApply, tasks.shadowJar) }

/* --------------------------- Javac opts ------------------------------- */
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation") // Triggers deprecation warning messages.
    options.encoding = "UTF-8"
    options.isFork = true
}

/* ----------------------- Eclipse BuildShip SHIM ----------------------- */
fun Project.addResolvableEclipseConfigs() {
    val jarAttr =
        objects.named(org.gradle.api.attributes.LibraryElements::class, org.gradle.api.attributes.LibraryElements.JAR)
    val apiAttr = objects.named(org.gradle.api.attributes.Usage::class, org.gradle.api.attributes.Usage.JAVA_API)

    val compileOnlyRes =
        configurations.create("eclipseCompileOnly") {
            extendsFrom(configurations.compileOnly.get())
            isCanBeResolved = true
            isCanBeConsumed = false
            attributes.attribute(org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE, apiAttr)
            attributes.attribute(org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, jarAttr)
        }
    val compileOnlyApiRes =
        configurations.create("eclipseCompileOnlyApi") {
            extendsFrom(configurations.getByName("compileOnlyApi"))
            isCanBeResolved = true
            isCanBeConsumed = false
            attributes.attribute(org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE, apiAttr)
            attributes.attribute(org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, jarAttr)
        }
    eclipse.classpath.plusConfigurations.addAll(listOf(compileOnlyRes, compileOnlyApiRes))
}

addResolvableEclipseConfigs()

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "eclipse")
    addResolvableEclipseConfigs()
    eclipse.project.name = "${project.name}-${rootProject.name}"
    tasks.withType<Jar>().configureEach { archiveBaseName.set("${project.name}-${rootProject.name}") }
}

/* ----------------------------- Auto Formatting ------------------------ */
spotless {
    kotlin { ktfmt().kotlinlangStyle().configure { it.setMaxWidth(120) } }
    kotlinGradle {
        ktfmt().kotlinlangStyle().configure { it.setMaxWidth(120) }
        target("build.gradle.kts", "settings.gradle.kts")
    }
}
