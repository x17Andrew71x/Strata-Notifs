import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.ksp)
}

val devApiBaseUrl = providers.gradleProperty("afterchime.devApiBaseUrl")
  .orElse("http://10.0.2.2:3000")
val devShellUrl = providers.gradleProperty("afterchime.devShellUrl")
  .orElse("https://shell-disabled.invalid/")
val shellUrl = URI(devShellUrl.get())
require(shellUrl.scheme == "https" && !shellUrl.host.isNullOrBlank() && shellUrl.userInfo == null) {
  "afterchime.devShellUrl must be an HTTPS URL without userinfo"
}

android {
  namespace = "com.techfullymade.afterchime"
  compileSdk {
    version = release(37) {
      minorApiLevel = 2
    }
  }

  sourceSets {
    getByName("androidTest") {
      assets.directories.clear()
      assets.directories.add("schemas")
    }
  }

  defaultConfig {
    applicationId = "com.techfullymade.afterchime"
    minSdk = 31
    targetSdk = 36
    versionCode = 1002
    versionName = "0.1.2"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  flavorDimensions += "environment"
  productFlavors {
    create("dev") {
      dimension = "environment"
      applicationIdSuffix = ".dev"
      versionNameSuffix = "-dev"
      buildConfigField("String", "API_BASE_URL", "\"${devApiBaseUrl.get()}\"")
      buildConfigField("String", "SHELL_URL", "\"${shellUrl}\"")
      buildConfigField("boolean", "PRODUCTION_ENABLED", "false")
      resValue("string", "app_name", "Afterchime Dev")
    }
    create("prod") {
      dimension = "environment"
      buildConfigField("String", "API_BASE_URL", "\"https://production-disabled.invalid\"")
      buildConfigField("String", "SHELL_URL", "\"https://production-disabled.invalid/\"")
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
    unitTests.all {
      it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
    allWarningsAsErrors.set(true)
  }
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.webkit)
  implementation(libs.androidx.datastore.preferences)
  ksp(libs.room.compiler)

  testImplementation(libs.junit4)
  testImplementation(libs.org.json)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.androidx.compose.ui.test.junit4)

  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.room.testing)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
