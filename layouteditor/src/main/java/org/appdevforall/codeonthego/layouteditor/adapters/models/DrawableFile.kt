package org.appdevforall.codeonthego.layouteditor.adapters.models

import android.graphics.drawable.Drawable
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil

data class DrawableFile(var versions: Int, var drawable: Drawable, var path: String) {
  var name: String = FileUtil.getLastSegmentFromPath(path)
}
