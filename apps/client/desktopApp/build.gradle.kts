import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}
kotlin { jvmToolchain(21) }

// 本机路径通过用户级 Gradle 属性或环境变量提供，不提交到仓库。
val configuredJbrHome = providers.gradleProperty("jbrHome")
    .orElse(providers.environmentVariable("JBR_HOME"))
    .orNull
    ?.let { configuredPath ->
        val home = file(configuredPath)
        val releaseFile = home.resolve("release")
        require(releaseFile.isFile) { "JBR 路径无效，请将 jbrHome 或 JBR_HOME 指向包含 release 文件的 JBR 21 根目录。" }
        val release = Properties().apply { releaseFile.inputStream().use { load(it) } }
        require(release.getProperty("IMPLEMENTOR", "").contains("JetBrains")) {
            "指定的 Java 不是 JetBrains Runtime，请选择 JBR 21。"
        }
        require(release.getProperty("JAVA_VERSION", "").trim('"').startsWith("21.")) {
            "当前项目需要 JBR 21，请勿选择其他 Java 主版本。"
        }
        home.absolutePath
    }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.swing)
    testImplementation(compose.desktop.uiTestJUnit4)
}
compose.desktop {
    application {
        configuredJbrHome?.let { javaHome = it }
        mainClass = "com.kaixu.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "KaiXuDemo"
            packageVersion = "0.1.0"
        }
    }
}

val desktopRuntimeHome = configuredJbrHome ?: System.getProperty("java.home")
val desktopJavaExecutable = file(desktopRuntimeHome).resolve(
    if (System.getProperty("os.name").startsWith("Windows")) "bin/java.exe" else "bin/java"
)

tasks.withType<Test>().configureEach {
    configuredJbrHome?.let { executable = desktopJavaExecutable.absolutePath }
}

tasks.register<Exec>("desktopRuntimeInfo") {
    group = "help"
    description = "显示桌面启动和打包所选的 Java 路径、厂商及版本。"
    commandLine(desktopJavaExecutable.absolutePath, "-XshowSettings:properties", "-version")
}

