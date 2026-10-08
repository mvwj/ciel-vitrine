plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val yandexClientId = "00000000000000000000000000000000"

android {
    namespace = "com.cielvitrine.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.cielvitrine.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.4"
        // OAuth Client ID is public. Never add a client secret or an access token here.
        manifestPlaceholders["YANDEX_CLIENT_ID"] = yandexClientId
        manifestPlaceholders["YANDEX_OAUTH_HOST"] = "oauth.yandex.ru"
        buildConfigField("String", "YANDEX_CLIENT_ID", "\"$yandexClientId\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":pos-format"))
    implementation(project(":provider-adapter"))
    implementation(project(":archive-engine"))
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.yandex.android:authsdk:3.2.1")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-gif:3.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
