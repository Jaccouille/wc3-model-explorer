plugins {
    id("java")
    id("application")
    id("org.beryx.runtime") version "2.0.1"
}

group = "com.hiveworkshop"
version = "1.4.0"

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("http://maven.nikr.net/"); isAllowInsecureProtocol = true  }
}

// LWJGL native classifier for the OS/arch currently running the build.
val lwjglNatives: String = run {
    val os = org.gradle.internal.os.OperatingSystem.current()
    val arch = System.getProperty("os.arch")
    val aarch64 = arch.startsWith("aarch64") || arch.startsWith("arm64")
    when {
        os.isWindows -> if (aarch64) "natives-windows-arm64" else "natives-windows"
        os.isMacOsX  -> if (aarch64) "natives-macos-arm64" else "natives-macos"
        else         -> if (aarch64) "natives-linux-arm64" else "natives-linux"
    }
}

dependencies {
    implementation(files("libs/modelstudio-0.05.jar"))
    implementation(files("libs/JCASC.jar"))
    implementation(files("libs/blp-iio-plugin.jar"))
    implementation("com.github.inwc3:JMPQ3:1.7.14")
    implementation("org.json:json:20240303")
    implementation("com.formdev:flatlaf:3.4")
    implementation("net.nikr:dds:1.0.0")

    implementation(platform("org.lwjgl:lwjgl-bom:3.3.6"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-opengl")
    // Bundle the LWJGL natives matching the OS building the package, so
    // jpackage on each release runner produces a runnable image for that OS.
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    implementation("org.lwjglx:lwjgl3-awt:0.2.3") {
        exclude(group = "org.lwjgl", module = "lwjgl")
        exclude(group = "org.lwjgl", module = "lwjgl-opengl")
    }

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.hiveworkshop.Main"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

runtime {
    options.addAll("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages")

    modules.addAll(
        "java.desktop",
        "java.datatransfer",
        "java.logging",
        "java.naming",
        "jdk.unsupported"
    )

    jpackage {
        imageName = "WC3ModelExplorer-${project.version}"
        installerName = "WC3ModelExplorer"
        appVersion = project.version.toString()
        val os = org.gradle.internal.os.OperatingSystem.current()
        if (os.isWindows) {
            imageOptions.addAll(listOf("--icon", "src/main/resources/images/app-icon.ico"))
            installerOptions.addAll(listOf(
                "--win-dir-chooser",
                "--win-shortcut",
                "--win-menu",
                "--win-per-user-install"
            ))
            installerType = "exe"
        } else if (os.isMacOsX) {
            installerType = "dmg"
        } else {
            installerType = "deb"
        }
        jvmArgs.addAll(listOf("--enable-native-access=ALL-UNNAMED"))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("version.properties") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}
