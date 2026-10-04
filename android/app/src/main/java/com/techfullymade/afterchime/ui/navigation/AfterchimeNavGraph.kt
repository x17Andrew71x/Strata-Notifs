package com.techfullymade.afterchime.ui.navigation

/** A content-free description of the current navigation location. */
data class NavigationSnapshot(
  val selectedRootRoute: String,
  val nestedRoute: String? = null,
)

/**
 * Pure navigation-domain state for the app shell. Nested destinations replace the current
 * nested route; Back returns to the selected root. Rendering and host integration are separate.
 */
@ConsistentCopyVisibility
data class AfterchimeNavGraph private constructor(
  val selectedRoot: RootDestination,
  val nestedRoute: String? = null,
) {
  val isAtRoot: Boolean
    get() = nestedRoute == null

  fun selectRoot(root: RootDestination): AfterchimeNavGraph =
    copy(selectedRoot = root, nestedRoute = null)

  fun openNested(route: String): AfterchimeNavGraph {
    validateNestedRoute(route, selectedRoot)
    return copy(nestedRoute = route)
  }

  fun back(): AfterchimeNavGraph =
    if (isAtRoot) this else copy(nestedRoute = null)

  fun snapshot(): NavigationSnapshot = NavigationSnapshot(
    selectedRootRoute = selectedRoot.route,
    nestedRoute = nestedRoute,
  )

  /** Encode only the selected root and optional nested route for a primitive Bundle/Saver boundary. */
  fun encodeSavedState(): Map<String, String> = buildMap {
    put(SAVED_ROOT_ROUTE_KEY, selectedRoot.route)
    nestedRoute?.let { put(SAVED_NESTED_ROUTE_KEY, it) }
  }

  companion object {
    const val SAVED_ROOT_ROUTE_KEY = "selected_root_route"
    const val SAVED_NESTED_ROUTE_KEY = "nested_route"
    private val savedStateKeys = setOf(SAVED_ROOT_ROUTE_KEY, SAVED_NESTED_ROUTE_KEY)

    val roots: List<RootDestination> = RootDestination.entries.toList()

    fun initial(): AfterchimeNavGraph = AfterchimeNavGraph(RootDestination.TODAY)

    fun restore(snapshot: NavigationSnapshot): AfterchimeNavGraph {
      val root = requireNotNull(RootDestination.fromRoute(snapshot.selectedRootRoute)) {
        "Unknown root route: ${snapshot.selectedRootRoute}"
      }
      snapshot.nestedRoute?.let { validateNestedRoute(it, root) }
      return AfterchimeNavGraph(root, snapshot.nestedRoute)
    }

    /** Decode a strict, content-free primitive map; malformed or extended state fails closed. */
    fun decodeSavedState(savedState: Map<String, *>): AfterchimeNavGraph {
      require(savedState.keys.all { it in savedStateKeys }) { "Unknown saved-state key" }
      val rootRoute = savedState[SAVED_ROOT_ROUTE_KEY] as? String
      require(!rootRoute.isNullOrBlank()) { "Missing or blank root route" }
      val nestedRoute = if (SAVED_NESTED_ROUTE_KEY in savedState) {
        savedState[SAVED_NESTED_ROUTE_KEY] as? String
          ?: throw IllegalArgumentException("Nested route must be a String")
      } else {
        null
      }
      return restore(NavigationSnapshot(rootRoute, nestedRoute))
    }

    private val nestedRoutePattern = Regex("[a-z][a-z0-9_-]*(?:/[a-z0-9_-]+)+")

    private fun validateNestedRoute(route: String, root: RootDestination) {
      require(route.length <= 128) { "Nested route is too long" }
      require(route.isNotBlank()) { "Nested route must not be blank" }
      require(route == route.trim()) { "Nested route must not have surrounding whitespace" }
      require(nestedRoutePattern.matches(route)) { "Nested route must be canonical" }
      require(route.substringBefore('/') == root.route) { "Nested route must belong to its selected root" }
    }
  }
}
