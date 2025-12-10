package org.appdevforall.codeonthego.layouteditor.fragments.resources

import android.content.Context
import android.content.DialogInterface
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.ToastUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

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
        val executorService = Executors.newSingleThreadExecutor()
        val future =
            executorService.submit<Void?> {
                var version = 0
                val drawableFolder = File(project!!.path + "/drawable/")
                if (drawableFolder.exists()) {
                    val drawables =
                        FileUtils.listFiles(
                            drawableFolder,
                            arrayOf("png", "jpg", "jpeg", "gif", "xml"),
                            false
                        )
                    for (drawable in drawables) {
                        val drawableName = drawable.name
                        val drawableObj =
                            if (drawableName.endsWith(".xml"))
                                Utils.getVectorDrawableAsync(
                                    requireContext(),
                                    Uri.fromFile(drawable)
                                )
                            else
                                Drawable.createFromPath(drawable.path)
                        for (i in dpiList!!.indices) {
                            val dpiFolder =
                                File(project!!.path + "/drawable-" + dpiList!![i] + "/")
                            if (dpiFolder.exists()) {
                                val matchingFile = File(dpiFolder, drawableName)
                                if (matchingFile.exists()) {
                                    Drawable.createFromPath(matchingFile.path)
                                    version = i
                                }
                            }
                        }
                        drawableList.add(
                            DrawableFile(
                                version + 1,
                                drawableObj!!,
                                drawable.path
                            )
                        )
                    }
                }
                null
            }

        try {
            future.get()
        } catch (e: ExecutionException) {
            logger.error("Error loading drawable", e)
        } catch (e: InterruptedException) {
            logger.error("Interruption while loading drawable", e)
        } finally {
            executorService.shutdown()
        }
    }

    fun addDrawable(uri: Uri) {
        val path = FileUtil.convertUriToFilePath(requireContext(), uri)
        if (TextUtils.isEmpty(path)) {
            ToastUtils.showLong(R.string.invalid_data_intent)
            return
        }
        // File name with extension
        val lastSegment = getLastSegmentFromPath(path)

        // File name without extension
        val fileName = lastSegment.substring(0, lastSegment.lastIndexOf("."))

        // Extension
        val extension =
            lastSegment.substring(lastSegment.lastIndexOf("."), lastSegment.length)
        val builder = MaterialAlertDialogBuilder(requireContext())
        val dialogBinding =
            DialogSelectDpisBinding.inflate(builder.create().layoutInflater)
        val editText = dialogBinding.textinputEdittext
        val inputLayout = dialogBinding.textinputLayout
        inputLayout.setHint(R.string.msg_enter_new_name)
        editText.setText(fileName)

        if (!lastSegment.endsWith(".xml")) {
            dpiAdapter = DPIsListAdapter(Drawable.createFromPath(path)!!)
            dialogBinding.listDpi.setAdapter(dpiAdapter)
            dialogBinding.listDpi.setLayoutManager(GridLayoutManager(requireActivity(), 2))
        }
        dialogBinding.listDpi.visibility =
            if (lastSegment.endsWith(".xml")) View.GONE else View.VISIBLE

        builder.setView(dialogBinding.getRoot())
        builder.setTitle(R.string.add_drawable)
        builder.setNegativeButton(
            R.string.cancel
        ) { di: DialogInterface?, which: Int -> }
        builder.setPositiveButton(
            R.string.add
        ) { di: DialogInterface?, which: Int ->
            val drawablePath = project!!.drawablePath
            var version = 0
            if (!lastSegment.endsWith(".xml") && dpiAdapter != null) {
                val selectedDPIs = dpiAdapter!!.selectedItems
                for (i in selectedDPIs.indices) {
                    try {
                        ImageConverter.convertToDrawableDpis(
                            editText.getText().toString() + extension,
                            BitmapFactory.decodeFile(path),
                            selectedDPIs
                        )
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                    version = i
                }
            }
            val toPath = drawablePath + editText.getText().toString() + extension
            FileUtil.copyFile(uri, toPath, requireContext())

            val drawable =
                if (lastSegment.endsWith(".xml"))
                    Utils.getVectorDrawableAsync(requireContext(), Uri.fromFile(File(toPath)))
                else
                    Drawable.createFromPath(toPath)
            val drawableFile = DrawableFile(version + 1, drawable!!, toPath)
            drawableList.add(drawableFile)
            adapter!!.notifyDataSetChanged()
        }

        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()

        editText.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(p1: CharSequence?, p2: Int, p3: Int, p4: Int) {}

                override fun onTextChanged(p1: CharSequence?, p2: Int, p3: Int, p4: Int) {}

                override fun afterTextChanged(p1: Editable?) {
                    NameErrorChecker.checkForDrawable(
                        editText.getText().toString(), inputLayout, dialog, drawableList
                    )
                }
            })

        NameErrorChecker.checkForDrawable(fileName, inputLayout, dialog, drawableList)

        editText.requestFocus()
        val inputMethodManager =
            dialogBinding.getRoot().context
                .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)

        if (editText.getText().toString() != "") {
            editText.setSelection(0, editText.getText().toString().length)
        }
    }
}
