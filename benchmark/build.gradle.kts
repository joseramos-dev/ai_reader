import com.android.build.api.dsl.TestExtension

plugins {
    alias(libs.plugins.android.test)
}

// Pruebas de rendimiento con Macrobenchmark contra la variante `benchmark` de `:app`. Se ejecutan en
// un móvil conectado: ./gradlew :benchmark:connectedBenchmarkAndroidTest
configure<TestExtension> {
    namespace = "dev.joseramos.aireader.benchmark"
    compileSdk = 37
    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    beforeVariants { it.enable = it.buildType == "benchmark" }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.ext.junit)
}
