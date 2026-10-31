package com.techfullymade.afterchime.identity

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CredentialEnvelopeStorageIsolationTest {
  @Test
  fun `development feedback credentials use files isolated from the general online identity`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val general = AtomicCredentialEnvelopeStorage(context)
    val feedback = AtomicCredentialEnvelopeStorage(
      context = context,
      fileName = "afterchime-development-feedback-credentials.enc",
      initializationFileName = "afterchime-development-feedback-credentials.initialized",
    )
    assertTrue(general.clear())
    assertTrue(general.clearInitialization())
    assertTrue(feedback.clear())
    assertTrue(feedback.clearInitialization())

    assertTrue(general.replace(byteArrayOf(1, 2, 3)))
    assertTrue(feedback.replace(byteArrayOf(7, 8, 9)))
    assertTrue(general.markInitialized())
    assertTrue(feedback.markInitialized())

    assertArrayEquals(byteArrayOf(1, 2, 3), general.read())
    assertArrayEquals(byteArrayOf(7, 8, 9), feedback.read())

    assertTrue(feedback.clear())
    assertTrue(feedback.clearInitialization())
    assertNull(feedback.read())
    assertArrayEquals(byteArrayOf(1, 2, 3), general.read())
    assertTrue(general.clear())
    assertTrue(general.clearInitialization())
  }
}
