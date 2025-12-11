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
            name = name.substring(0, name.lastIndexOf("."))

            items.put(name, path)
        }
    }

    @JvmStatic
    fun contains(name: String?): Boolean {
        return items.containsKey(name)
    }

    @JvmStatic
    fun getDrawable(context: Context?, key: String?): Drawable? {
        return if (items[key]!!.endsWith(".xml"))
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
