plugins {
    application
}

group = "dev.melo"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:-options")
}

application {
    mainClass = "melo.Main"
    applicationName = "melo"
    applicationDefaultJvmArgs = listOf(
        "-XX:+UseSerialGC",
        "-Xmx384m",
        "-Xss1m",
        "-Djava.awt.headless=true",
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
    )
}
