plugins {
    id("base-conventions")
}

dependencies {
    implementation(libs.fastutil)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.toml)
    implementation(libs.kotlin.inline.logger)
    implementation(libs.rsprot.api)
    implementation(libs.rsprot.shared)
    implementation(projects.api.cheat)
    implementation(projects.api.gameProcess)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.invtx)
    implementation(projects.api.net)
    implementation(projects.api.player)
    implementation(projects.api.registry)
}
