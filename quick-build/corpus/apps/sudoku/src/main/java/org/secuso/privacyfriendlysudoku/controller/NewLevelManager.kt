package org.secuso.privacyfriendlysudoku.controller

// Scaffolding, not vendored: the real NewLevelManager is 525 lines of game-flow orchestration
// (pre-generating and caching levels, difficulty balancing). The vendored GeneratorService.java
// only reads PRE_SAVES_MIN via a static import.
object NewLevelManager {
    const val PRE_SAVES_MIN: Int = 3
}
