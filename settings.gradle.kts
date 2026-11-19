rootProject.name = "laptimer"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// core: protocol parsing, session log format, and (later) lap timing.
// It has no Android dependencies so it builds and tests on any JVM.
include("core")

// replay: command-line tool that reads a recorded session log.
include("replay")

// app: the Android app. Building it needs the Android SDK; local.properties
// points at it.
include("app")

// racebox-emulator: an Android app that acts as a RaceBox over Bluetooth LE,
// driving simulated laps, for testing the lap timer without a car.
include("racebox-emulator")
