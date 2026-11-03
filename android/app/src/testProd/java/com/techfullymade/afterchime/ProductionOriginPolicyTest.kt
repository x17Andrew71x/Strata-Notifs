package com.techfullymade.afterchime

import android.net.Uri
import com.techfullymade.afterchime.network.NetworkOriginPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ProductionOriginPolicyTest {
  @Test
  fun `production accepts only exact HTTPS shell and API origins`() {
    assertTrue(ShellOriginPolicy.isAllowed(Uri.parse("https://afterchime.example/")))
    assertFalse(ShellOriginPolicy.isAllowed(Uri.parse("http://afterchime.example/")))
    assertFalse(ShellOriginPolicy.isAllowed(Uri.parse("https://user@afterchime.example/")))
    assertFalse(ShellOriginPolicy.isAllowed(Uri.parse("https://afterchime.example/path")))
    assertNull(NetworkOriginPolicy.validatedBaseUrl("http://10.0.2.2:3000"))
    assertNotNull(NetworkOriginPolicy.validatedBaseUrl("https://afterchime.example"))
  }
}
