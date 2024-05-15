package com.itsvks.layouteditor.editor.initializer

class AttributeMap {
  private val attrs: MutableList<Attribute> = ArrayList()

  /**
   * Puts a key-value pair into the AttributeMap
   *
   * @param key the key of the attribute
   * @param value the value of the attribute
   */
  fun putValue(key: String, value: String) {
    if (contains(key)) {
      val index = getAttributeIndexFromKey(key)
      attrs[index].value = value
    } else {
      attrs.add(Attribute(key, value))
    }
  }

  /**
   * Removes a key-value pair from the AttributeMap
   *
   * @param key the key of the attribute to be removed
   */
  fun removeValue(key: String) {
    val index = getAttributeIndexFromKey(key)
    attrs.removeAt(index)
  }

  /**
   * Gets the value associated with the given key in the AttributeMap
   *
   * @param key the key of the attribute
   * @return the value of the attribute
   */
  fun getValue(key: String): String {
    val index = getAttributeIndexFromKey(key)
    val attr = attrs[index]
    return attr.value
  }

  /**
   * Gets a list of all the keys in the AttributeMap
   *
   * @return a list of all keys
   */
  fun keySet(): List<String> {
    val keys: MutableList<String> = ArrayList()

    for (attr in attrs) {
      keys.add(attr.key)
    }

    return keys
  }

  /**
   * Gets a list of all the values in the AttributeMap
   *
   * @return a list of all values
   */
  fun values(): List<String> {
    val values: MutableList<String> = ArrayList()

    for (attr in attrs) {
      values.add(attr.value)
    }

    return values
  }

  /**
   * Checks if the AttributeMap contains a key
   *
   * @param key the key to check for
   * @return true if the AttributeMap contains the key, false otherwise
   */
  fun contains(key: String): Boolean {
    for (attr in attrs) {
      if (attr.key == key) {
        return true
      }
    }

    return false
  }

  /**
   * Gets the index of the Attribute with the given key
   *
   * @param key the key of the Attribute
   * @return the index of the Attribute
   */
  private fun getAttributeIndexFromKey(key: String): Int {
    var index = 0

    for (attr in attrs) {
      if (attr.key == key) {
        return index
      }

      index++
    }

    return index
  }

  private class Attribute
  /**
   * Constructs an Attribute with the specified key-value pair
   *
   * @param key the key of the Attribute
   * @param value the value of the Attribute
   */(val key: String, var value: String)
}
