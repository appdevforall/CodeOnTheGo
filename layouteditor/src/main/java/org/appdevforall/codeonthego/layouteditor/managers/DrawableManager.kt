package org.appdevforall.codeonthego.layouteditor.managers

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil.getLastSegmentFromPath
import org.appdevforall.codeonthego.layouteditor.utils.Utils
import java.io.File

object DrawableManager {
    private val items = HashMap<String?, String?>()

    fun loadFromFiles(files: Array<File>) {
        items.clear()

        for (f in files) {
            val path = f.path
            var name = getLastSegmentFromPath(path)
            val dotIndex = name.lastIndexOf(".")
            if (dotIndex > 0) {
                name = name.substring(0, dotIndex)
            }
            items.put(name, path)
        }
    }

    @JvmStatic
    fun contains(name: String?): Boolean {
        return items.containsKey(name)
    }

    @JvmStatic
    fun getDrawable(context: Context?, key: String?): Drawable? {
        val path = items[key] ?: return null
        return if (path.endsWith(".xml"))
            Utils.getVectorDrawableAsync(context, Uri.fromFile(File(items[key].toString())))
        else
            Drawable.createFromPath(items[key])
    }

    fun keySet(): MutableSet<String?> {
        return items.keys
    }

    fun clear() {
        items.clear()
    }
}
