package com.techfullymade.afterchime.gameplay

/**
 * Shared rolling per-source eligibility rule for notification-powered features.
 *
 * Inputs are returned unchanged so callers can aggregate only the events accepted for gameplay.
 */
object NotificationEnergyPolicy {
  fun <T, SourceKey> accepted(
    inputs: Iterable<T>,
    sourceKey: (T) -> SourceKey,
    occurredAtEpochMillis: (T) -> Long,
  ): List<T> {
    val latestAcceptedBySource = mutableMapOf<SourceKey, Long>()
    val accepted = mutableListOf<T>()
    for (input in inputs.sortedBy(occurredAtEpochMillis)) {
      val source = sourceKey(input)
      val occurredAt = occurredAtEpochMillis(input)
      val previous = latestAcceptedBySource[source]
      if (
        previous == null ||
        occurredAt - previous >= GameBalance.SOURCE_NOTIFICATION_COOLDOWN_MILLIS
      ) {
        latestAcceptedBySource[source] = occurredAt
        accepted += input
      }
    }
    return accepted
  }
}
