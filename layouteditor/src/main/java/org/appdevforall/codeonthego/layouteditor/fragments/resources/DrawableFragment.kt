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
import com.blankj.utilcode.util.ToastUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import org.appdevforall.codeonthego.layouteditor.managers.ProjectManager.Companion.instance
import org.appdevforall.codeonthego.layouteditor.tools.ImageConverter
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil.getLastSegmentFromPath
import org.appdevforall.codeonthego.layouteditor.utils.NameErrorChecker
import org.appdevforall.codeonthego.layouteditor.utils.Utils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

class DrawableFragment : Fragment {
    private var binding: FragmentResourcesBinding? = null
    private var adapter: DrawableResourceAdapter? = null
    var dpiAdapter: DPIsListAdapter? = null
    private var project: ProjectFile? = null
    private var mRecyclerView: RecyclerView? = null
    var drawableList: MutableList<DrawableFile> = ArrayList()
    private var dpiList: MutableList<String?>? = null

    private val logger = LoggerFactory.getLogger(DrawableFragment::class.java)

    constructor(drawableList: MutableList<DrawableFile>) {
        this.drawableList = drawableList
        dpiList = ArrayList<String?>()
        dpiList!!.add("ldpi")
        dpiList!!.add("mdpi")
        dpiList!!.add("hdpi")
        dpiList!!.add("xhdpi")
        dpiList!!.add("xxhdpi")
        dpiList!!.add("xxxhdpi")
    }

    constructor()

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
        adapter = DrawableResourceAdapter(drawableList)
        mRecyclerView!!.setAdapter(adapter)
        mRecyclerView!!.setLayoutManager(
            LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
        )
    }

    fun loadDrawables() {
        val appContext = requireContext().applicationContext

        lifecycleScope.launch {

            try {
                val result = withContext(Dispatchers.IO) {

                    val list = mutableListOf<DrawableFile>()
                    val baseDrawableFolder = File(project!!.path + "/drawable/")

                    if (baseDrawableFolder.exists()) {
                        val drawables = FileUtils.listFiles(
                            baseDrawableFolder,
                            arrayOf("png", "jpg", "jpeg", "gif", "xml"),
                            false
                        )

                        for (file in drawables) {
                            var version = 0
                            val name = file.name

                            val drawable = if (name.endsWith(".xml")) {
                                Utils.getVectorDrawableAsync(
                                    appContext,          // <-- FIX
                                    Uri.fromFile(file)
                                )
                            } else {
                                Drawable.createFromPath(file.path)
                            }

                            dpiList?.forEachIndexed { i, dpi ->
                                val dpiFolder = File(project!!.path + "/drawable-$dpi/")
                                if (dpiFolder.exists()) {
                                    val matching = File(dpiFolder, name)
                                    if (matching.exists()) {
                                        Drawable.createFromPath(matching.path)
                                        version = i
                                    }
                                }
                            }

                            drawable?.let {
                                list.add(
                                    DrawableFile(
                                        version + 1,
                                        it,
                                        file.path
                                    )
                                )
                            }
                        }
                    }

                    list
                }

                drawableList.clear()
                drawableList.addAll(result)
                adapter?.notifyDataSetChanged()

            } catch (e: Exception) {
                logger.error("Error loading drawables", e)
            }
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
            lifecycleScope.launch {
                val newDrawableFile = withContext(Dispatchers.IO) {
                    val drawablePath = project!!.drawablePath
                    var version = 0

                    if (!lastSegment.endsWith(".xml") && dpiAdapter != null) {
                        val selectedDPIs = dpiAdapter!!.selectedItems

                        selectedDPIs.forEachIndexed { i, dpi ->
                            try {
                                ImageConverter.convertToDrawableDpis(
                                    editText.text.toString() + extension,
                                    BitmapFactory.decodeFile(path),
                                    selectedDPIs
                                )
                            } catch (_: IOException) { }
                            version = i
                        }
                    }

                    val toPath = drawablePath + editText.text.toString() + extension
                    FileUtil.copyFile(uri, toPath, requireContext())

                    val drawable =
                        if (lastSegment.endsWith(".xml"))
                            Utils.getVectorDrawableAsync(requireContext(), Uri.fromFile(File(toPath)))
                        else
                            Drawable.createFromPath(toPath)

                    DrawableFile(version + 1, drawable!!, toPath)
                }

                drawableList.add(newDrawableFile)
                adapter?.notifyDataSetChanged()
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

}
