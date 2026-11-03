package com.techfullymade.afterchime

import androidx.test.core.app.ApplicationProvider
import com.techfullymade.afterchime.identity.AtomicCredentialEnvelopeStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetiredDevelopmentToolStateTest {
  @Test
  fun `retired private credentials and identity state are removed idempotently`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val credentialFile = listOf("afterchime-", "development-", "feedback-credentials.enc").joinToString("")
    val initializationFile =
      listOf("afterchime-", "development-", "feedback-credentials.initialized").joinToString("")
    val identityStore = listOf("development_", "feedback_", "identity").joinToString("")
    val storage = AtomicCredentialEnvelopeStorage(context, credentialFile, initializationFile)
    assertTrue(storage.replace(byteArrayOf(1, 2, 3)))
    assertTrue(storage.markInitialized())
    assertTrue(context.getSharedPreferences(identityStore, 0).edit().putString("legacy", "value").commit())
    var deletedKeys = 0

    repeat(2) {
      assertTrue(
        removeRetiredDevelopmentToolState(context) {
          deletedKeys += 1
          true
        },
      )
    }

    assertNull(storage.read())
    assertFalse(storage.isInitialized())
    assertTrue(context.getSharedPreferences(identityStore, 0).all.isEmpty())
    assertEquals(1, deletedKeys)
  }
}
