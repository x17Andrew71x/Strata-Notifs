package com.techfullymade.afterchime

import android.net.Uri
import com.techfullymade.afterchime.network.NetworkOriginPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DevelopmentOriginPolicyTest {
  @Test
  fun `development placeholders remain inert while loopback API access stays bounded`() {
    assertFalse(ShellOriginPolicy.isAllowed(Uri.parse("https://shell-disabled.invalid/")))
    assertFalse(ShellOriginPolicy.isAllowed(Uri.parse("https://production-disabled.invalid/")))
    assertNotNull(NetworkOriginPolicy.validatedBaseUrl("http://10.0.2.2:3000"))
  }
}
