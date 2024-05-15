package com.itsvks.layouteditor.editor

class APILevel(val level: Level) {
  enum class Level {
    OREO,  // Android 8.0 & 8.1
    PIE,  // Android 9
    QUEEN_CAKE,  // Android 10
    RED_VELVET_CAKE,  // Android 11
    SNOW_CONE // Android 12
  }
}
