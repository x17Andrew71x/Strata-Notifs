package com.techfullymade.afterchime

import android.net.Uri

internal object ShellOriginPolicy {
  private val disabledHosts = setOf("shell-disabled.invalid", "production-disabled.invalid")

  fun isAllowed(uri: Uri): Boolean =
    isExactHttpsShellOrigin(uri) && uri.host !in disabledHosts
}
