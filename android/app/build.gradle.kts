import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

val devApiBaseUrl = providers.gradleProperty("afterchime.devApiBaseUrl")
  .orElse("http://10.0.2.2:3000")

android {
  namespace = "com.techfullymade.afterchime"
  compileSdk {
    version = release(37) {
      minorApiLevel = 2
    }
  }

  defaultConfig {
    applicationId = "com.techfullymade.afterchime"
    minSdk = 26
    targetSdk = 36
    versionCode = 1000
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  flavorDimensions += "environment"
  productFlavors {
    create("dev") {
      dimension = "environment"
      applicationIdSuffix = ".dev"
      versionNameSuffix = "-dev"
      buildConfigField("String", "API_BASE_URL", "\"${devApiBaseUrl.get()}\"")
      buildConfigField("boolean", "PRODUCTION_ENABLED", "false")
      resValue("string", "app_name", "Afterchime Dev")
    }
    create("prod") {
      dimension = "environment"
      buildConfigField("String", "API_BASE_URL", "\"https://production-disabled.invalid\"")
      buildConfigField("boolean", "PRODUCTION_ENABLED", "false")
      resValue("string", "app_name", "Afterchime")
    }
  }

  buildTypes {
    debug {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }

  buildFeatures {
    buildConfig = true
    compose = true
    resValues = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging {
    resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
  }

  testOptions {
    unitTests.isIncludeAndroidResources = true
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
    allWarningsAsErrors.set(true)
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)

  testImplementation(libs.junit4)
  testImplementation(libs.org.json)

  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
