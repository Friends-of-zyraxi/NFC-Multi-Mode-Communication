import java.io.File
import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0 起内置 Kotlin 支持，无需再应用 org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
}

// UnstableApiUsage：AGP 9 的 androidResources.localeFilters 仍是 @Incubating API，
// 但它是 resConfigs 的唯一替代品，只能抑制告警。
@Suppress("DEPRECATION", "UnstableApiUsage")
android {
    namespace = "io.github.zyraxi21.nfc"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.zyraxi21.nfc"
        minSdk = 34
        targetSdk = 37
        versionCode = 202610071
        versionName = "2026.10.07.1"

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
                "proguard-rules.pro",
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
            "en-rAU", "en-rCA", "en-rGB", "en-rIN", "en-rXC",
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
    // Compose BOM：统一约束 Compose 各模块版本，各模块不要再单独声明版本号。
    //
    // 这个 BOM 版本不能回退：NavigationView.kt 用的是已稳定的 HorizontalPager
    // 与 beyondViewportPageCount，它们需要 Compose Foundation ≥ 1.7，而旧 BOM
    // 只能给到 1.6.5。此前之所以能编译，是因为 navigation-compose 与
    // constraintlayout-compose 顺带把 foundation/ui 顶到了 1.7.8（隐式升级）；
    // 把这两个无关依赖删掉后就暴露了真实缺口，所以在这里显式对齐到 1.7.8。
    // 该 BOM 同时提供 foundation/ui 1.7.8、material 1.7.8、material3 1.3.1。

    // Material Components：values/themes.xml 的窗口主题继承
    // Theme.MaterialComponents.DayNight.NoActionBar，必须有这份依赖。
    // 它自己以 api 方式带进 appcompat 与 core-ktx，所以二者无需单独声明。
    implementation(libs.material)

    // Compose：各模块版本统一由上面的 BOM 约束
    // 注意：androidTest 配置并不继承这里的 platform 约束，测试源集必须再声明一次；
    // 这是 Gradle 官方推荐写法，但 IDE 的 AvoidDuplicateDependencies 检查会误报，
    // 实测删掉下面 androidTest 那行会直接构建失败，所以只抑制告警、不改代码。
    @Suppress("AvoidDuplicateDependencies")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Material 2 Compose：NavigationView 的 Icons.Default.* 来自它带入的
    // material-icons-core。涟漪已改用 Material3 的 ripple()，不再需要 M2 的
    // rememberRipple（那个 API 在 1.7 起已废弃）。
    implementation(libs.androidx.compose.material)

    // Nearby Connections
    implementation(libs.play.services.nearby)

    // 仅调试期使用：Compose 预览工具与测试清单
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // 测试
    testImplementation(libs.junit)
    // androidTest 配置不继承主源集的 platform 约束，必须重复声明一次（已实测：
    // 删掉这行会报 "Could not find androidx.compose.ui:ui-test-junit4:."）。
    @Suppress("AvoidDuplicateDependencies")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)

    // Fluent Design：按需引入模块化依赖
    implementation(libs.fluentui.core)
    implementation(libs.fluentui.controls)
    implementation(libs.fluentui.progress)
    implementation(libs.fluentui.topappbars)
    implementation(libs.fluentui.tablayout)
    implementation(libs.fluentui.notification)
    implementation(libs.fluentui.menus)
    implementation(libs.fluentui.listitem)
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

// assembleRelease 与 packageRelease 都会产出 R8 报告，两者谁先跑完都触发归档；
// finalizedBy 是幂等的，即使两个任务都执行也只会复制一次。
val r8ReportTasks = setOf("assembleRelease", "packageRelease")
tasks.matching { it.name in r8ReportTasks }.configureEach {
    finalizedBy(archiveR8Mapping)
}
