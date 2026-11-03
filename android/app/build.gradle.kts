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

val disabledProductionApiUrl = "https://production-disabled.invalid"
val disabledProductionShellUrl = "https://production-disabled.invalid/"
val prodApiBaseUrl = providers.gradleProperty("afterchime.prodApiBaseUrl")
  .orElse(providers.environmentVariable("AFTERCHIME_PROD_API_BASE_URL"))
  .orElse(disabledProductionApiUrl)
val prodShellUrl = providers.gradleProperty("afterchime.prodShellUrl")
  .orElse(providers.environmentVariable("AFTERCHIME_PROD_SHELL_URL"))
  .orElse(disabledProductionShellUrl)

fun exactProductionOrigin(value: String, property: String, trailingSlash: Boolean): URI {
  val origin = URI(value)
  val expectedPath = if (trailingSlash) "/" else ""
  require(
    origin.scheme == "https" &&
      !origin.host.isNullOrBlank() &&
      origin.userInfo == null &&
      origin.port == -1 &&
      origin.path == expectedPath &&
      origin.query == null &&
      origin.fragment == null
  ) {
    "$property must be an exact HTTPS origin${if (trailingSlash) " ending in /" else ""}"
  }
  return origin
}

val prodApiOrigin = exactProductionOrigin(prodApiBaseUrl.get(), "afterchime.prodApiBaseUrl", false)
val prodShellOrigin = exactProductionOrigin(prodShellUrl.get(), "afterchime.prodShellUrl", true)
val prodStoreFile = providers.gradleProperty("afterchime.prodStoreFile")
  .orElse(providers.environmentVariable("AFTERCHIME_PROD_STORE_FILE"))
val prodStorePassword = providers.gradleProperty("afterchime.prodStorePassword")
  .orElse(providers.environmentVariable("AFTERCHIME_PROD_STORE_PASSWORD"))
val prodKeyAlias = providers.gradleProperty("afterchime.prodKeyAlias")
  .orElse(providers.environmentVariable("AFTERCHIME_PROD_KEY_ALIAS"))
val prodKeyPassword = providers.gradleProperty("afterchime.prodKeyPassword")
  .orElse(providers.environmentVariable("AFTERCHIME_PROD_KEY_PASSWORD"))
val productionSigningConfigured = listOf(
  prodStoreFile.orNull,
  prodStorePassword.orNull,
  prodKeyAlias.orNull,
  prodKeyPassword.orNull,
).all { !it.isNullOrBlank() }

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
    versionCode = 1022
    versionName = "0.1.22"
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
      resValue("string", "app_name", "Afterchime Dev")
    }
    create("prod") {
      dimension = "environment"
      buildConfigField("String", "API_BASE_URL", "\"${prodApiOrigin}\"")
      buildConfigField("String", "SHELL_URL", "\"${prodShellOrigin}\"")
      resValue("string", "app_name", "Afterchime")
    }
  }

  signingConfigs {
    if (productionSigningConfigured) {
      create("productionRelease") {
        storeFile = file(requireNotNull(prodStoreFile.orNull))
        storePassword = requireNotNull(prodStorePassword.orNull)
        keyAlias = requireNotNull(prodKeyAlias.orNull)
        keyPassword = requireNotNull(prodKeyPassword.orNull)
      }
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
      signingConfig = signingConfigs.findByName("productionRelease")
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
    create("review") {
      initWith(getByName("release"))
      isDebuggable = false
      signingConfig = signingConfigs.getByName("debug")
      versionNameSuffix = "-review"
      matchingFallbacks += listOf("release")
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

val reviewBuildType = android.buildTypes.getByName("review")
val releaseBuildType = android.buildTypes.getByName("release")
val verifyReviewBuildTypeParity = tasks.register("verifyReviewBuildTypeParity") {
  group = "verification"
  description = "Verifies that the internal review build retains release shrinking policy."
  inputs.property("reviewMinify", reviewBuildType.isMinifyEnabled)
  inputs.property("releaseMinify", releaseBuildType.isMinifyEnabled)
  inputs.property("reviewShrink", reviewBuildType.isShrinkResources)
  inputs.property("releaseShrink", releaseBuildType.isShrinkResources)
  inputs.property("reviewProguard", reviewBuildType.proguardFiles.map { it.name })
  inputs.property("releaseProguard", releaseBuildType.proguardFiles.map { it.name })
  doLast {
    check(inputs.properties["reviewMinify"] == inputs.properties["releaseMinify"]) {
      "review minification must match release"
    }
    check(inputs.properties["reviewShrink"] == inputs.properties["releaseShrink"]) {
      "review resource shrinking must match release"
    }
    check(inputs.properties["reviewProguard"] == inputs.properties["releaseProguard"]) {
      "review ProGuard inputs must match release"
    }
  }
}

tasks.matching { it.name == "assembleDevReview" }.configureEach {
  dependsOn(verifyReviewBuildTypeParity)
}

val verifyProductionReleaseConfiguration = tasks.register("verifyProductionReleaseConfiguration") {
  group = "verification"
  description = "Fails closed unless a production release has exact HTTPS origins and signing custody."
  outputs.upToDateWhen { false }
  doLast {
    check(prodApiOrigin.toString() != disabledProductionApiUrl) {
      "afterchime.prodApiBaseUrl is required for a production release"
    }
    check(prodShellOrigin.toString() != disabledProductionShellUrl) {
      "afterchime.prodShellUrl is required for a production release"
    }
    check(productionSigningConfigured) {
      "afterchime production signing properties are incomplete"
    }
    check(file(requireNotNull(prodStoreFile.orNull)).isFile) {
      "afterchime.prodStoreFile does not exist"
    }
  }
}

tasks.matching { it.name == "preProdReleaseBuild" }.configureEach {
  dependsOn(verifyProductionReleaseConfiguration)
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
