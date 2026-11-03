package com.techfullymade.afterchime

import android.net.Uri

internal object ShellOriginPolicy {
  fun isAllowed(uri: Uri): Boolean = isExactHttpsShellOrigin(uri)
}
