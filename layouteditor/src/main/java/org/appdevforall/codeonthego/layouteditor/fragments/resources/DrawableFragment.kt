package org.appdevforall.codeonthego.layouteditor.fragments.resources

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat
import com.blankj.utilcode.util.ToastUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import org.appdevforall.codeonthego.layouteditor.ProjectFile
import org.appdevforall.codeonthego.layouteditor.R
import org.appdevforall.codeonthego.layouteditor.adapters.DPIsListAdapter
import org.appdevforall.codeonthego.layouteditor.adapters.DrawableResourceAdapter
import org.appdevforall.codeonthego.layouteditor.adapters.models.DrawableFile
import org.appdevforall.codeonthego.layouteditor.databinding.DialogSelectDpisBinding
import org.appdevforall.codeonthego.layouteditor.databinding.FragmentResourcesBinding
import org.appdevforall.codeonthego.layouteditor.databinding.TextinputlayoutBinding
import org.appdevforall.codeonthego.layouteditor.managers.ProjectManager.Companion.instance
import org.appdevforall.codeonthego.layouteditor.tools.ImageConverter
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil.getLastSegmentFromPath
import org.appdevforall.codeonthego.layouteditor.utils.NameErrorChecker
import org.appdevforall.codeonthego.layouteditor.utils.Utils
import org.greenrobot.eventbus.EventBus
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

class DrawableFragment(
    private var drawableList: MutableList<DrawableFile>
) : Fragment() {
    private var binding: FragmentResourcesBinding? = null
    private var mRecyclerView: RecyclerView? = null

    private var project: ProjectFile? = null

    private var adapter: DrawableResourceAdapter? = null
    var dpiAdapter: DPIsListAdapter? = null
    private var dpiList = mutableListOf("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")

    private val logger = LoggerFactory.getLogger(DrawableFragment::class.java)


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        binding = FragmentResourcesBinding.inflate(inflater, container, false)
        return binding!!.getRoot()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        project = instance.openedProject
        loadDrawables()
        mRecyclerView = binding!!.recyclerView
        // Create the adapter and set it to the RecyclerView
        adapter = DrawableResourceAdapter(
            drawableList,
            object : DrawableResourceAdapter.OnDrawableActionListener {
                override fun onRenameRequested(
                    position: Int,
                    holder: DrawableResourceAdapter.VH,
                    view: View
                ) {
                    showRenameDialog(position, holder)
                }

                override fun onDeleteRequested(position: Int) {
                    deleteDrawable(position)
                }
            })
        mRecyclerView!!.setAdapter(adapter)
        mRecyclerView!!.setLayoutManager(
            LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
        )
    }

    fun loadDrawables() {
        val appContext = requireContext().applicationContext

        lifecycleScope.launch {
            try {
                val result = loadDrawableFiles(appContext)

                drawableList.clear()
                drawableList.addAll(result)
                adapter?.notifyDataSetChanged()

            } catch (e: Exception) {
                logger.error("Error loading drawables", e)
            }
        }
    }

    private suspend fun loadDrawableFiles(context: Context): List<DrawableFile> {
        val currentProject = project ?: return emptyList()

        return withContext(Dispatchers.IO) {
            val projectDir = File(currentProject.path)
            val baseDrawableFolder = File(projectDir, "drawable")

            if (!baseDrawableFolder.exists()) {
                return@withContext emptyList()
            }

            val dpiVersionMap = buildDpiVersionMap(projectDir)

            val drawableFiles = FileUtils.listFiles(
                baseDrawableFolder,
                arrayOf("png", "jpg", "jpeg", "gif", "xml"),
                false
            )

            drawableFiles.mapNotNull { file ->
                createDrawableFile(context, file, dpiVersionMap)
            }
        }
    }

    private fun buildDpiVersionMap(projectDir: File): Map<String, Int> {
        val dpiMap = mutableMapOf<String, Int>()
        dpiList.forEachIndexed { index, dpi ->
            val dpiFolder = File(projectDir, "drawable-$dpi")
            dpiFolder.listFiles()?.forEach { fileInDpiFolder ->
                dpiMap[fileInDpiFolder.name] = index
            }
        }
        return dpiMap
    }

    private fun createDrawableFile(
        context: Context,
        file: File,
        dpiVersionMap: Map<String, Int>
    ): DrawableFile? {
        val drawable = if (file.extension.equals("xml", ignoreCase = true)) {
            Utils.getVectorDrawableAsync(context, Uri.fromFile(file))
        } else {
            Drawable.createFromPath(file.path)
        }

        val versionIndex = dpiVersionMap[file.name] ?: 0

        return drawable?.let {
            DrawableFile(
                versions = versionIndex + 1,
                drawable = it,
                path = file.path
            )
        }
    }

    fun addDrawable(uri: Uri) {
        val path = FileUtil.convertUriToFilePath(requireContext(), uri)
        if (path.isEmpty()) {
            ToastUtils.showLong(R.string.invalid_data_intent)
            return
        }

        val lastSegment = getLastSegmentFromPath(path)
        val fileName = lastSegment.substring(0, lastSegment.lastIndexOf("."))
        val extension = lastSegment.substring(lastSegment.lastIndexOf("."))

        val builder = MaterialAlertDialogBuilder(requireContext())
        val dialogBinding = DialogSelectDpisBinding.inflate(builder.create().layoutInflater)
        val editText = dialogBinding.textinputEdittext
        val inputLayout = dialogBinding.textinputLayout

        inputLayout.setHint(R.string.msg_enter_new_name)
        editText.setText(fileName)

        if (!lastSegment.endsWith(".xml")) {
            Drawable.createFromPath(path)?.let { drawable ->
                dpiAdapter = DPIsListAdapter(drawable)
            }
            dialogBinding.listDpi.adapter = dpiAdapter
            dialogBinding.listDpi.layoutManager = GridLayoutManager(requireActivity(), 2)
        }

        dialogBinding.listDpi.visibility =
            if (lastSegment.endsWith(".xml")) View.GONE else View.VISIBLE

        builder.setView(dialogBinding.root)
        builder.setTitle(R.string.add_drawable)
        builder.setNegativeButton(R.string.cancel, null)

        builder.setPositiveButton(R.string.add) { _, _ ->
            val drawableName = editText.text.toString()
            val selectedDPIs = dpiAdapter?.selectedItems ?: emptyList()
            val isXml = lastSegment.endsWith(".xml")

            lifecycleScope.launch {
                val newDrawableFile = withContext(Dispatchers.IO) {
                    val drawablePath = project?.drawablePath ?: return@withContext null

                    if (!isXml && selectedDPIs.isNotEmpty()) {

                        try {
                            ImageConverter.convertToDrawableDpis(
                                drawableName + extension,
                                BitmapFactory.decodeFile(path),
                                selectedDPIs
                            )
                        } catch (e: IOException) {
                            logger.error("Error converting drawable to different DPIs", e)
                        }
                    }

                    val toPath = drawablePath + drawableName + extension
                    FileUtil.copyFile(uri, toPath, requireContext())

                    val drawable =
                        if (isXml)
                            Utils.getVectorDrawableAsync(
                                requireContext(),
                                Uri.fromFile(File(toPath))
                            )
                        else
                            Drawable.createFromPath(toPath)

                    val version = selectedDPIs.size + 1
                    drawable?.let {
                        DrawableFile(version, it, toPath)
                    }
                }

                newDrawableFile?.let {
                    val insertPosition = drawableList.size
                    drawableList.add(it)
                    adapter?.notifyItemInserted(insertPosition)
                }
            }
        }

        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                NameErrorChecker.checkForDrawable(
                    editText.text.toString(),
                    inputLayout,
                    dialog,
                    drawableList
                )
            }
        })

        NameErrorChecker.checkForDrawable(fileName, inputLayout, dialog, drawableList)

        editText.requestFocus()
        val imm = dialogBinding.root.context
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)

        if (editText.text?.isNotEmpty() == true) {
            editText.setSelection(0, editText.text!!.length)
        }
    }

    private fun showRenameDialog(position: Int, holder: DrawableResourceAdapter.VH) {
        val drawableFile = drawableList[position]
        val segment = getLastSegmentFromPath(drawableFile.path)

        val fileName = segment.substring(0, segment.lastIndexOf("."))
        val extension = segment.substring(segment.lastIndexOf("."))

        val builder = MaterialAlertDialogBuilder(requireContext())
        val bind = TextinputlayoutBinding.inflate(builder.create().layoutInflater)
        val editText = bind.textinputEdittext

        editText.setText(fileName)
        builder.setTitle(R.string.rename_drawable)
        builder.setView(bind.root)
        builder.setNegativeButton(R.string.cancel, null)
        builder.setPositiveButton(R.string.rename) { _, _ ->
            lifecycleScope.launch {
                renameDrawable(position, holder, editText.text.toString(), extension)
            }
        }

        val dialog = builder.create()
        dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private suspend fun renameDrawable(
        position: Int,
        holder: DrawableResourceAdapter.VH,
        newName: String,
        extension: String
    ) = withContext(Dispatchers.IO) {

        val drawable = drawableList[position]
        val oldPath = drawable.path
        val oldName = drawable.name.substring(0, drawable.name.lastIndexOf("."))

        val oldFile = File(oldPath)
        val newPath = project!!.drawablePath + newName + extension
        val newFile = File(newPath)

        oldFile.renameTo(newFile)

        EventBus.getDefault().post(FileRenameEvent(oldFile, newFile))

        val projectRoot = File(project!!.path).parentFile!!
        renameDrawableReferences(
            projectRoot,
            oldName = oldName,
            newName = newName
        )

        val updatedDrawable =
            if (newName.endsWith(".xml") || newName.endsWith(".svg"))
                VectorDrawableCompat.createFromPath(newPath)
            else
                Drawable.createFromPath(newPath)

        drawable.path = newPath
        drawable.name = newName + extension

        withContext(Dispatchers.Main) {
            holder.drawableName.text = newName
            holder.drawable.setImageDrawable(updatedDrawable)
            adapter?.notifyItemChanged(position)
        }
    }

    private suspend fun renameDrawableReferences(
        projectRoot: File,
        oldName: String,
        newName: String
    ) = withContext(Dispatchers.IO) {

        val xmlFiles = projectRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() == "xml" }
            .toList()

        val codeFiles = projectRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()

        val xmlPattern = "@drawable/$oldName"
        val xmlReplacement = "@drawable/$newName"

        val codePattern = "R.drawable.$oldName"
        val codeReplacement = "R.drawable.$newName"

        // Update XML references
        xmlFiles.forEach { file ->
            val text = file.readText()
            if (text.contains(xmlPattern)) {
                file.writeText(text.replace(xmlPattern, xmlReplacement))
            }
        }

        // Update Kotlin/Java references
        codeFiles.forEach { file ->
            val text = file.readText()
            if (text.contains(codePattern)) {
                file.writeText(text.replace(codePattern, codeReplacement))
            }
        }
    }

    private fun deleteDrawable(position: Int) {
        val file = File(drawableList[position].path)
        file.delete()
        drawableList.removeAt(position)
        adapter?.notifyItemRemoved(position)
    }

}
