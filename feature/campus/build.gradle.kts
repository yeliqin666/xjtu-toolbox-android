// :feature:campus —— 校园服务：校园卡、场馆、付款码、优惠券、电费、空教室、黄页、体测、课堂人脸、师资。只依赖 :shared:*，不依赖别的 feature。
plugins {
    id("toolbox.kmp.library")
    id("toolbox.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:model"))
            api(project(":shared:navigation"))
            api(project(":shared:designsystem"))
            api(project(":shared:network"))
        }
    }
}
