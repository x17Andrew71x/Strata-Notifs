package com.techfullymade.afterchime.gameplay

import android.content.Context
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Versioned, append-only native selection data delivered by the authenticated hosted shell. */
data class FossilCatalogManifest(
  val revision: Int,
  val items: List<FossilCatalogItem>,
) {
  init {
    require(revision in 1..MAX_REVISION)
    require(items.size in 1..MAX_ITEMS)
    require(items.map(FossilCatalogItem::id).toSet().size == items.size)
    require(items.sumOf(FossilCatalogItem::selectionWeight) in 1..MAX_TOTAL_WEIGHT)
  }

  fun toJson(): JSONObject = JSONObject()
    .put("schemaVersion", SCHEMA_VERSION)
    .put("revision", revision)
    .put(
      "items",
      JSONArray(
        items.map { item ->
          JSONObject()
            .put("id", item.id)
            .put("tier", item.tier.name)
            .put("selectionWeight", item.selectionWeight)
            .put("family", item.family.name)
            .put(
              "visual",
              JSONObject()
                .put("hueDegrees", item.visual.hueDegrees)
                .put("strataCount", item.visual.strataCount)
                .put("inclusionDensityPercent", item.visual.inclusionDensityPercent)
                .put("reliefPercent", item.visual.reliefPercent)
                .put("rotationDegrees", item.visual.rotationDegrees),
            )
        },
      ),
    )

  companion object {
    const val SCHEMA_VERSION = 1
    const val MAX_ITEMS = 128
    private const val MAX_REVISION = 1_000_000
    private const val MAX_TOTAL_WEIGHT = 1_000_000
    private val MANIFEST_KEYS = setOf("schemaVersion", "revision", "items")
    private val ITEM_KEYS = setOf("id", "tier", "selectionWeight", "family", "visual")
    private val VISUAL_KEYS = setOf(
      "hueDegrees",
      "strataCount",
      "inclusionDensityPercent",
      "reliefPercent",
      "rotationDegrees",
    )

    fun parse(value: JSONObject): FossilCatalogManifest? {
      return try {
        if (value.keyNames() != MANIFEST_KEYS || value.opt("schemaVersion") != SCHEMA_VERSION) {
          return null
        }
        val revision = value.opt("revision") as? Int ?: return null
        val encodedItems = value.optJSONArray("items") ?: return null
        if (encodedItems.length() !in 1..MAX_ITEMS) return null
        val items = (0 until encodedItems.length()).map { index ->
          parseItem(encodedItems.optJSONObject(index) ?: return null) ?: return null
        }
        FossilCatalogManifest(revision = revision, items = items)
      } catch (_: IllegalArgumentException) {
        null
      } catch (_: JSONException) {
        null
      }
    }

    private fun parseItem(value: JSONObject): FossilCatalogItem? {
      if (value.keyNames() != ITEM_KEYS) return null
      val id = value.opt("id") as? String ?: return null
      val tier = enumValueOrNull<Tier>(value.opt("tier") as? String ?: return null) ?: return null
      val selectionWeight = value.opt("selectionWeight") as? Int ?: return null
      val family = enumValueOrNull<Family>(value.opt("family") as? String ?: return null) ?: return null
      val visual = value.optJSONObject("visual") ?: return null
      if (visual.keyNames() != VISUAL_KEYS) return null
      return FossilCatalogItem(
        id = id,
        tier = tier,
        selectionWeight = selectionWeight,
        family = family,
        visual = VisualParameters(
          hueDegrees = visual.opt("hueDegrees") as? Int ?: return null,
          strataCount = visual.opt("strataCount") as? Int ?: return null,
          inclusionDensityPercent = visual.opt("inclusionDensityPercent") as? Int ?: return null,
          reliefPercent = visual.opt("reliefPercent") as? Int ?: return null,
          rotationDegrees = visual.opt("rotationDegrees") as? Int ?: return null,
        ),
      )
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
      enumValues<T>().firstOrNull { it.name == value }
  }
}

interface FossilCatalogPersistence {
  fun read(): String?

  fun write(value: String): Boolean
}

class SharedPreferencesFossilCatalogPersistence(
  context: Context,
) : FossilCatalogPersistence {
  private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  override fun read(): String? = preferences.getString(MANIFEST_KEY, null)

  override fun write(value: String): Boolean = preferences.edit().putString(MANIFEST_KEY, value).commit()

  private companion object {
    const val PREFERENCES_NAME = "afterchime_fossil_catalog"
    const val MANIFEST_KEY = "manifest"
  }
}

/** Thread-safe catalogue snapshot used by local-only selection and persistence. */
class FossilCatalogStore(
  private val persistence: FossilCatalogPersistence,
  bundledManifest: FossilCatalogManifest = FossilCatalogManifest(
    revision = FossilCatalog.BUNDLED_REVISION,
    items = FossilCatalog.items,
  ),
) {
  private val lock = Any()
  private val bundled = bundledManifest

  @Volatile
  private var current: FossilCatalogManifest = loadPersisted() ?: bundled

  val revision: Int
    get() = current.revision

  val items: List<FossilCatalogItem>
    get() = current.items

  fun find(id: String): FossilCatalogItem? = FossilCatalog.find(current.items, id)

  fun itemForSpecimenId(specimenId: String): FossilCatalogItem? =
    FossilCatalog.itemForSpecimenId(current.items, specimenId)

  fun selectFor(localDate: LocalDate, localSecret: ByteArray): FossilCatalogItem =
    FossilCatalog.selectFor(current.items, localDate, localSecret)

  fun apply(payload: JSONObject): Boolean {
    val candidate = FossilCatalogManifest.parse(payload) ?: return false
    synchronized(lock) {
      val existing = current
      if (candidate.revision < existing.revision) return false
      if (candidate.revision == existing.revision) return candidate == existing
      if (!preservesExistingIdentities(existing, candidate)) return false
      if (!persistence.write(candidate.toJson().toString())) return false
      current = candidate
      return true
    }
  }

  private fun loadPersisted(): FossilCatalogManifest? {
    val encoded = persistence.read() ?: return null
    val candidate = try {
      FossilCatalogManifest.parse(JSONObject(encoded))
    } catch (_: JSONException) {
      null
    } ?: return null
    return candidate.takeIf { manifest ->
      manifest.revision >= bundled.revision && preservesExistingIdentities(bundled, manifest)
    }
  }

  private fun preservesExistingIdentities(
    existing: FossilCatalogManifest,
    candidate: FossilCatalogManifest,
  ): Boolean {
    val candidateById = candidate.items.associateBy(FossilCatalogItem::id)
    return existing.items.all { previous ->
      val next = candidateById[previous.id] ?: return@all false
      previous.copy(selectionWeight = next.selectionWeight) == next
    } && FossilCatalog.legacyAliases.values.all(candidateById::containsKey)
  }
}

private fun JSONObject.keyNames(): Set<String> {
  val names = mutableSetOf<String>()
  val iterator = keys()
  while (iterator.hasNext()) names += iterator.next()
  return names
}
