package com.techfullymade.afterchime

import android.content.Context
import com.techfullymade.afterchime.identity.AndroidCredentialCipher
import com.techfullymade.afterchime.identity.AtomicCredentialEnvelopeStorage

internal fun removeRetiredDevelopmentToolState(
  context: Context,
  deleteKey: () -> Boolean = {
    AndroidCredentialCipher(
      listOf("afterchime.", "development-", "feedback-credentials.v1").joinToString(""),
    ).deleteKey()
  },
): Boolean {
  val retirementState = context.getSharedPreferences("afterchime-retired-tool-state", Context.MODE_PRIVATE)
  if (retirementState.getBoolean("state-v1", false)) return true
  val credentialFile = listOf("afterchime-", "development-", "feedback-credentials.enc").joinToString("")
  val initializationFile =
    listOf("afterchime-", "development-", "feedback-credentials.initialized").joinToString("")
  val identityStore = listOf("development_", "feedback_", "identity").joinToString("")
  val storage = AtomicCredentialEnvelopeStorage(
    context = context,
    fileName = credentialFile,
    initializationFileName = initializationFile,
  )
  val envelopeRemoved = runCatching(storage::clear).getOrDefault(false)
  val initializationRemoved = runCatching(storage::clearInitialization).getOrDefault(false)
  val preferencesRemoved = runCatching { context.deleteSharedPreferences(identityStore) }.getOrDefault(false)
  val keyRemoved = runCatching(deleteKey).getOrDefault(false)
  val removed = envelopeRemoved && initializationRemoved && preferencesRemoved && keyRemoved
  return removed && retirementState.edit().putBoolean("state-v1", true).commit()
}
