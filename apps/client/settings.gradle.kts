pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "KaiXu"
include(":shared", ":desktopApp")

// 桌面单独学习与测试时无需安装 Android SDK；默认同时启用 Android。
if (!providers.gradleProperty("desktopOnly").orNull.toBoolean()) {
    include(":androidApp")
}

