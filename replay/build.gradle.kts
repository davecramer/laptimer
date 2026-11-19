plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":core")))
}

application {
    mainClass.set("laptimer.replay.ReplayMainKt")
}

tasks.test {
    useJUnitPlatform()
}
