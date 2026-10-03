package com.techfullymade.afterchime.capture

enum class CoarseNotificationCategory {
  ALARM,
  CALL,
  EMAIL,
  EVENT,
  MESSAGE,
  NAVIGATION,
  OTHER,
  PROGRESS,
  REMINDER,
  SOCIAL,
  TRANSPORT,
  WORKOUT,
}

data class ReducedNotification(
  val occurredAtEpochMillis: Long,
  val localHour: Int,
  val category: CoarseNotificationCategory,
  val sourceToken: String,
  val sourceColourRgb: Int,
) {
  init {
    require(localHour in 0..23)
    require(sourceToken.matches(SOURCE_TOKEN_PATTERN))
    require(sourceColourRgb in 0..0xFFFFFF)
  }

  private companion object {
    val SOURCE_TOKEN_PATTERN = Regex("[0-9a-f]{64}")
  }
}
