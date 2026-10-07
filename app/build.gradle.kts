import java.io.File
import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0 起内置 Kotlin 支持，无需再应用 org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
}

@Suppress("DEPRECATION")
android {
    namespace = "io.github.zyraxi21.nfc"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.zyraxi21.nfc"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "20261007"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            // R8：代码压缩、混淆与优化（AGP 内置 R8，此开关即官方启用方式）
            isMinifyEnabled = true
            // 资源压缩：移除未被引用的资源，依赖上面的 isMinifyEnabled 生效
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    // jvmTarget 无需显式设置：内置 Kotlin 下默认取 compileOptions.targetCompatibility（1.8）
    buildFeatures {
        // 界面全部由 Compose 实现，工程内没有 res/layout，无需 viewBinding
        compose = true
    }
    // 语言资源裁剪：应用界面只有中文，且中文在 res/values/strings.xml（默认资源）里，
    // 不受影响；这里裁掉的是库自带的 137 种语言翻译。AGP 9 已用 localeFilters 取代 resConfigs。
    // 注意必须写完整的 BCP 47 配置串：写 "zh" 匹配不到 values-zh-rCN，会连中文一起删掉。
    // 下列变体来自当前依赖，依赖升级后若出现新变体需要在此追加。
    androidResources {
        localeFilters += listOf(
            "zh-rCN", "zh-rHK", "zh-rTW",
            "en-rAU", "en-rCA", "en-rGB", "en-rIN", "en-rXC"
        )
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // AGP / AndroidX 的版本标记，只有构建工具会读
            excludes += "/META-INF/*.version"
            // kotlinx-coroutines 的调试探针，release 包不需要
            excludes += "/DebugProbesKt.bin"
            // Kotlin 反射元数据：本应用不含 kotlin-reflect（APK 的 dex 中没有 kotlin/reflect 类），
            // 这 8 个 *.kotlin_builtins 无人读取，全部排除（约 53 KB）。
            // 将来若引入 kotlin-reflect，需删掉这一行。
            excludes += "**/*.kotlin_builtins"
        }
    }
}

dependencies {
    // Compose BOM：统一约束 Compose 各模块版本。主源集与测试源集必须各自声明，
    // 这里复用同一个 platform 实例，避免重复书写同一坐标。
    val composeBom = platform(libs.androidx.compose.bom)

    // AndroidX 基础
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.navigation.compose)

    // Compose：版本统一由 BOM 约束，各模块不要再单独声明版本
    implementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Fluent 内部依赖 material（Material 2 Compose）的 rememberRipple
    implementation("androidx.compose.material:material")
    // FluentTheme 用 observeAsState 观察 LiveData，需要此适配器
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation(libs.androidx.constraintlayout.compose)

    // Nearby Connections
    implementation(libs.play.services.nearby)

    // 仅调试期使用：Compose 预览工具与测试清单
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // 测试
    testImplementation(libs.junit)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)

    // Fluent Design：按需引入模块化依赖
    implementation("com.microsoft.fluentui:fluentui_core:0.3.11")
    implementation("com.microsoft.fluentui:fluentui_controls:0.3.3")
    implementation("com.microsoft.fluentui:fluentui_progress:0.3.7")
    implementation("com.microsoft.fluentui:fluentui_topappbars:0.3.9")
    implementation("com.microsoft.fluentui:fluentui_tablayout:0.3.5")
    implementation("com.microsoft.fluentui:fluentui_notification:0.3.10")
    implementation("com.microsoft.fluentui:fluentui_menus:0.3.5")
    implementation("com.microsoft.fluentui:fluentui_listitem:0.3.7")
}

// ======================================================================
// R8 映射文件归档
//
// mapping.txt 是唯一能把线上混淆堆栈还原成源码行号的文件，且无法事后重建。
// 每次 assembleRelease 结束后，把它和 usage.txt 复制到 app/release/ 目录，
// 文件名带 versionName-versionCode，避免多版本之间配错。
//
// 默认目标：app/release/（与发布用 APK 同级，且不会被 clean 删除）
// 不能写进 app/build/outputs/apk/release/：那是 AGP 自己的输出目录，Gradle 会判定
// 目录所有权冲突并让构建失败，而且 clean 会连 mapping 一起删掉。
// 长期存档建议覆盖到仓库外（30 MB 的映射文件不适合进版本库）：
//   .\gradlew :app:assembleRelease -PmappingArchiveDir=D:\archive\nfc
// ======================================================================
val r8ReportDir = layout.buildDirectory.dir("outputs/mapping/release")
val releaseVersionTag = "${android.defaultConfig.versionName}-${android.defaultConfig.versionCode}"

val archiveR8Mapping = tasks.register<Copy>("archiveR8Mapping") {
    group = "build"
    description = "把 R8 的 mapping.txt / usage.txt 复制到 app/release 目录"

    val mappingFile = r8ReportDir.map { it.file("mapping.txt") }
    val usageFile = r8ReportDir.map { it.file("usage.txt") }
    val archiveDir = providers.gradleProperty("mappingArchiveDir")
        .map { File(it) }
        .orElse(layout.projectDirectory.dir("release").asFile)

    from(mappingFile) { rename { "mapping-$releaseVersionTag.txt" } }
    from(usageFile) { rename { "usage-$releaseVersionTag.txt" } }
    into(archiveDir)

    // 未开启 R8 时不产生报告，任务直接跳过
    onlyIf { mappingFile.get().asFile.exists() }
}

tasks.matching { it.name == "assembleRelease" || it.name == "packageRelease" }.configureEach {
    finalizedBy(archiveR8Mapping)
}
