plugins {
    `kotlin-dsl`
}

// compileOnly：AGP / KGP / CMP 在运行期用根工程 `apply false` 钉死的那一份，约定插件不再带第二份上 classpath。
dependencies {
    compileOnly("com.android.tools.build:gradle-api:${libs.versions.agp.get()}")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    compileOnly("org.jetbrains.compose:compose-gradle-plugin:${libs.versions.composeMultiplatform.get()}")
}

kotlin {
    compilerOptions {
        // KGP 的元数据版本可能比 Gradle 内嵌的 Kotlin 新。
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "toolbox.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("kmpJvmAndroid") {
            id = "toolbox.kmp.jvm-android"
            implementationClass = "KmpJvmAndroidConventionPlugin"
        }
        register("kmpCompose") {
            id = "toolbox.kmp.compose"
            implementationClass = "KmpComposeConventionPlugin"
        }
    }
}
