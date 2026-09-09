plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val wristBriefGatewayUrl = providers.gradleProperty("WRISTBRIEF_GATEWAY_URL").orElse("").get()
val wristBriefGatewayToken = providers.gradleProperty("WRISTBRIEF_GATEWAY_TOKEN").orElse("").get()

android {
    namespace = "ink.underflo.wristbrief"
    compileSdk = 35

    defaultConfig {
        applicationId = "ink.underflo.wristbrief"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "AI_GATEWAY_URL", wristBriefGatewayUrl.asBuildConfigString())
        buildConfigField("String", "AI_GATEWAY_TOKEN", wristBriefGatewayToken.asBuildConfigString())
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("androidx.wear:wear-remote-interactions:1.2.0")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material3:1.4.2")
    implementation("androidx.wear.watchface:watchface-complications-data-source:1.3.0")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
