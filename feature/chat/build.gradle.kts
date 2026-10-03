plugins {
    alias(libs.plugins.taixu.android.feature)
}

android {
    namespace = "top.wkbin.taixu.feature.chat"
}

dependencies {
    implementation(project(":feature:theme"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":runtime"))
    implementation(project(":harness"))
    implementation(project(":tools"))
    // A2UI PoC：render_surface 工具结果在聊天流内嵌渲染原生界面
    implementation(project(":feature:a2uipoc"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.serialization.json)
    // LocalLiquidGlassBackdrop 的类型 LayerBackdrop 来自该库，类型推断需要它在 classpath 上
    implementation(libs.backdrop)
    implementation(libs.bundles.coil)

    testImplementation(libs.bundles.test.robolectric)
    // 折叠段分帧揭示的单测需要驱动 MonotonicFrameClock（BroadcastFrameClock），
    // 与 harness/tools/runtime 的测试一致，显式声明协程而不是依赖传递。
    testImplementation(libs.kotlinx.coroutines.core)
}
