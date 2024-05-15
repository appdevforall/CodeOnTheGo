package com.itsvks.layouteditor.managers

import android.view.View
import android.view.ViewGroup
import org.jetbrains.annotations.Contract

/** This is IdManager class which is used for managing ids  */
object IdManager {
  /** This is hashmap for storing view and its ids  */
  @get:JvmStatic
  val idMap = HashMap<View, String>()

  /**
   * This method is used to add new id to view
   *
   * @param view View to which id needs to be added
   * @param id String id which needs to be added
   */
  @JvmStatic
  fun addNewId(view: View, id: String) {
    if (!idMap.containsKey(view)) {
      view.id = View.generateViewId()
    }
    idMap[view] = id.replace("@+id/", "")
  }

  /**
   * This method is used to add id to view with specified name and id
   *
   * @param view View to which id needs to be added
   * @param idName String name of the id
   * @param id Integer of the id
   */
  @JvmStatic
  fun addId(view: View, idName: String, id: Int) {
    view.id = id
    idMap[view] = idName.replace("@+id/", "")
  }

  /**
   * This method is used to remove the id for specified view
   *
   * @param view View to which id needs to be removed
   * @param removeChilds Boolean value to remove childs of ViewGroup
   */
  @JvmStatic
  fun removeId(view: View, removeChilds: Boolean) {
    idMap.remove(view)
    if (removeChilds && view is ViewGroup) {
      for (i in 0 until view.childCount) {
        removeId(view.getChildAt(i), true)
      }
    }
  }

  /**
   * This method is used to check if id exists for specified name
   *
   * @param name String name of the id
   * @return Boolean value true/false
   */
  fun containsId(name: String): Boolean {
    val mName = name.replace("@id/", "")
    for (view in idMap.keys) {
      if (idMap[view] == mName) {
        return true
      }
    }
    return false
  }

  /** This method is used to clear the HashMap ids  */
  @JvmStatic
  fun clear() {
    idMap.clear()
  }

  /**
   * This method is used to get the view id for specified name
   *
   * @param name String name of the id
   * @return Integer of the view id
   */
  @JvmStatic
  fun getViewId(name: String): Int {
    val mName = name.replace("@id/", "")
    for (view in idMap.keys) {
      if (idMap[view] == mName) {
        return view.id
      }
    }
    return -1
  }

  /**
   * This method is used to get the list of ids
   *
   * @return List of ids
   */
  @JvmStatic
  @Contract(" -> new")
  fun getIds(): ArrayList<String> {
    return ArrayList(idMap.values)
  }
}
