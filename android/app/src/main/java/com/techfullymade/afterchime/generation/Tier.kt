package com.techfullymade.afterchime.generation

enum class Tier {
  COMMON,
  UNCOMMON,
  RARE,
  EXCEPTIONAL,
  SINGULAR,
  ;

  companion object {
    /** Maps a uniform 0..999 deterministic roll to the fixed v1 rarity distribution. */
    fun fromRoll(roll: Int): Tier {
      require(roll in 0..999)
      return when (roll) {
        in 0..599 -> COMMON
        in 600..849 -> UNCOMMON
        in 850..959 -> RARE
        in 960..994 -> EXCEPTIONAL
        else -> SINGULAR
      }
    }
  }
}
