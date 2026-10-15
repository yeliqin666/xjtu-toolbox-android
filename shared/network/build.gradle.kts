// :shared:network —— Ktor 客户端：serve 模式下各域 *Source 的 HTTP 实现（`core.net`）与黄页接口，三端可用。
plugins {
    id("toolbox.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:model"))
            api(project(":shared:navigation"))
            // api 而非 implementation：HttpClientEngine 出现在公开的 expect 声明里
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}
