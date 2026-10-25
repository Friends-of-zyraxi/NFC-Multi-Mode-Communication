// dependencyResolutionManagement / RepositoriesMode / repositories{} 目前在 Gradle 中仍标注 @Incubating，
// 但它们是官方推荐且暂无替代写法，因此在此显式抑制该警告。
@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/central") }
        maven { setUrl("https://maven.aliyun.com/repository/jcenter") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        maven { setUrl("https://jitpack.io") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/central") }
        maven { setUrl("https://maven.aliyun.com/repository/jcenter") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        maven { setUrl("https://jitpack.io") }
        // Play services 固定从官方仓库获取，避免镜像中缺失的 AAR 被缓存后持续构建失败。
        exclusiveContent {
            forRepository {
                google { name = "GooglePlayServices" }
            }
            filter {
                includeGroup("com.google.android.gms")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "NFC Communication"
include(":app")
