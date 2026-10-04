package com.techfullymade.afterchime.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
  @Test
  fun `root destinations have exact membership and order`() {
    assertEquals(
      listOf(RootDestination.TODAY, RootDestination.MUSEUM, RootDestination.COMMUNITY, RootDestination.MORE),
      AfterchimeNavGraph.roots,
    )
    assertEquals(listOf("today", "museum", "community", "more"), AfterchimeNavGraph.roots.map { it.route })
  }

  @Test
  fun `every root can be selected and clears nested location`() {
    RootDestination.entries.forEach { root ->
      val selected = AfterchimeNavGraph.initial()
        .selectRoot(root)
        .openNested("${root.route}/detail")
        .selectRoot(root)

      assertEquals(root, selected.selectedRoot)
      assertNull(selected.nestedRoute)
      assertTrue(selected.isAtRoot)
    }
  }

  @Test
  fun `back from nested detail returns to the selected root`() {
    RootDestination.entries.forEach { root ->
      val nested = AfterchimeNavGraph.initial()
        .selectRoot(root)
        .openNested("${root.route}/detail")

      assertEquals("${root.route}/detail", nested.nestedRoute)
      assertFalse(nested.isAtRoot)
      assertEquals(AfterchimeNavGraph.initial().selectRoot(root), nested.back())
    }
  }

  @Test
  fun `snapshot restore preserves selected root and nested route`() {
    val current = AfterchimeNavGraph.initial()
      .selectRoot(RootDestination.MUSEUM)
      .openNested("museum/specimen/42")

    assertEquals(current, AfterchimeNavGraph.restore(current.snapshot()))
  }

  @Test
  fun `saved state round trip preserves selected root and nested route exactly`() {
    val current = AfterchimeNavGraph.initial()
      .selectRoot(RootDestination.MUSEUM)
      .openNested("museum/specimen/42")

    assertEquals(
      mapOf(
        AfterchimeNavGraph.SAVED_ROOT_ROUTE_KEY to "museum",
        AfterchimeNavGraph.SAVED_NESTED_ROUTE_KEY to "museum/specimen/42",
      ),
      current.encodeSavedState(),
    )
    assertEquals(current, AfterchimeNavGraph.decodeSavedState(current.encodeSavedState()))
  }

  @Test
  fun `saved state round trip preserves root-only location`() {
    val current = AfterchimeNavGraph.initial().selectRoot(RootDestination.COMMUNITY)

    assertEquals(mapOf(AfterchimeNavGraph.SAVED_ROOT_ROUTE_KEY to "community"), current.encodeSavedState())
    assertEquals(current, AfterchimeNavGraph.decodeSavedState(current.encodeSavedState()))
  }

  @Test
  fun `saved state decoder rejects unknown keys`() {
    assertThrows(IllegalArgumentException::class.java) {
      AfterchimeNavGraph.decodeSavedState(mapOf("root_route" to "today"))
    }
  }

  @Test
  fun `saved state decoder rejects missing or blank root`() {
    listOf(
      emptyMap<String, Any?>(),
      mapOf(AfterchimeNavGraph.SAVED_ROOT_ROUTE_KEY to " "),
      mapOf(AfterchimeNavGraph.SAVED_ROOT_ROUTE_KEY to "unknown"),
    ).forEach { malformed ->
      assertThrows(IllegalArgumentException::class.java) {
        AfterchimeNavGraph.decodeSavedState(malformed)
      }
    }
  }

  @Test
  fun `saved state decoder rejects non String values`() {
    listOf(
      mapOf(AfterchimeNavGraph.SAVED_ROOT_ROUTE_KEY to 42),
      mapOf(AfterchimeNavGraph.SAVED_ROOT_ROUTE_KEY to "today", AfterchimeNavGraph.SAVED_NESTED_ROUTE_KEY to 42),
      mapOf(AfterchimeNavGraph.SAVED_ROOT_ROUTE_KEY to "today", AfterchimeNavGraph.SAVED_NESTED_ROUTE_KEY to null),
    ).forEach { malformed ->
      assertThrows(IllegalArgumentException::class.java) {
        AfterchimeNavGraph.decodeSavedState(malformed)
      }
    }
  }

  @Test
  fun `saved state decoder rejects blank whitespace and root nested routes`() {
    listOf("", " ", "\tmuseum", "museum ", "museum").forEach { nestedRoute ->
      assertThrows(IllegalArgumentException::class.java) {
        AfterchimeNavGraph.decodeSavedState(
          mapOf(
            AfterchimeNavGraph.SAVED_ROOT_ROUTE_KEY to "today",
            AfterchimeNavGraph.SAVED_NESTED_ROUTE_KEY to nestedRoute,
          ),
        )
      }
    }
  }

  @Test
  fun `all roots have explicit TalkBack labels`() {
    assertEquals(
      listOf("Today", "Museum", "Community", "More"),
      AfterchimeNavGraph.roots.map { it.talkBackLabel },
    )
    assertTrue(AfterchimeNavGraph.roots.all { it.talkBackLabel.isNotBlank() })
  }

  @Test
  fun `nested routes are canonical and belong to their selected root`() {
    assertThrows(IllegalArgumentException::class.java) {
      AfterchimeNavGraph.initial().openNested("museum/specimen/42")
    }
    assertThrows(IllegalArgumentException::class.java) {
      AfterchimeNavGraph.initial().selectRoot(RootDestination.MUSEUM)
        .openNested("museum/specimen/42?extra=private")
    }
    assertThrows(IllegalArgumentException::class.java) {
      AfterchimeNavGraph.restore(NavigationSnapshot("today", "museum/specimen/42"))
    }
  }

  @Test
  fun `invalid routes and snapshots are rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      AfterchimeNavGraph.initial().openNested(" ")
    }
    assertThrows(IllegalArgumentException::class.java) {
      AfterchimeNavGraph.initial().openNested(RootDestination.MUSEUM.route)
    }
    assertThrows(IllegalArgumentException::class.java) {
      AfterchimeNavGraph.restore(NavigationSnapshot("unknown"))
    }
  }
}
