plugins { id("com.android.library") }
android {
    namespace = "win.cheems.core"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("org.yaml:snakeyaml:2.3")
    implementation(files("libs/cheems-quic.aar"))
}
