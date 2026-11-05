plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.ksp) apply false
}

buildscript {
  configurations.configureEach {
    resolutionStrategy.force(
      "com.google.guava:guava:33.7.2-jre",
      "org.apache.commons:commons-lang3:3.21.0",
      "org.apache.httpcomponents:httpclient:4.5.14",
      "org.apache.httpcomponents:httpmime:4.5.14",
      "org.bitbucket.b_c:jose4j:0.9.6",
      "org.bouncycastle:bcpkix-jdk18on:1.85",
      "org.bouncycastle:bcprov-jdk18on:1.85",
      "org.bouncycastle:bcutil-jdk18on:1.85",
      "org.jdom:jdom2:2.0.6.1",
      "org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21",
    )
  }
}

subprojects {
  dependencyLocking {
    lockAllConfigurations()
  }

  configurations.configureEach {
    resolutionStrategy.force(
      "com.google.guava:guava:33.7.2-jre",
      "org.apache.commons:commons-lang3:3.21.0",
      "org.apache.httpcomponents:httpclient:4.5.14",
      "org.apache.httpcomponents:httpmime:4.5.14",
      "org.bitbucket.b_c:jose4j:0.9.6",
      "org.bouncycastle:bcpkix-jdk18on:1.85",
      "org.bouncycastle:bcprov-jdk18on:1.85",
      "org.bouncycastle:bcutil-jdk18on:1.85",
      "org.jdom:jdom2:2.0.6.1",
    )
  }
}
