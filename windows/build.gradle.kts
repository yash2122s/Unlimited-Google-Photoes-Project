plugins { kotlin("jvm"); id("org.jetbrains.kotlin.plugin.compose"); application }
kotlin { jvmToolchain(21) }
application { mainClass.set("dev.lantern.windows.MainKt") }
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.10.3")
    implementation("com.google.zxing:javase:3.5.3")
    implementation("com.github.sarxos:webcam-capture:0.3.12")
}
dependencies { testImplementation("junit:junit:4.13.2") }
dependencies { testImplementation("org.jetbrains.compose.ui:ui-test-junit4-desktop:1.10.3") }
tasks.test {
    // Desktop UI tests open the same folder and image dialogs used by the application.
    systemProperty("java.awt.headless","false")
}
