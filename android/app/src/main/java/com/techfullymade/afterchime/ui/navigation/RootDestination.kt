package com.techfullymade.afterchime.ui.navigation

/** The stable, ordered top-level destinations shown by the app shell. */
enum class RootDestination(
  val route: String,
  val talkBackLabel: String,
) {
  TODAY("today", "Today"),
  MUSEUM("museum", "Museum"),
  COMMUNITY("community", "Community"),
  MORE("more", "More"),
  ;

  companion object {
    fun fromRoute(route: String): RootDestination? = entries.firstOrNull { it.route == route }
  }
}
