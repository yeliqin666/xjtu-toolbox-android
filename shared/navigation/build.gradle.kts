// :shared:navigation —— `AppRoute`（路由键）与会话失效的路由端口；各端壳负责注册路由。
plugins {
    id("toolbox.kmp.library")
    id("toolbox.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:model"))
        }
    }
}
