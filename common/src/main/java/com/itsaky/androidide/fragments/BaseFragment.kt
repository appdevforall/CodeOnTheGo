/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.fragments

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.StringRes
import androidx.core.provider.DocumentsContractCompat
import androidx.core.provider.DocumentsContractCompat.buildDocumentUriUsingTree
import androidx.core.provider.DocumentsContractCompat.getTreeDocumentId
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import com.itsaky.androidide.common.R
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.utils.Environment.PROJECTS_FOLDER
import com.itsaky.androidide.utils.FileUtil
import com.itsaky.androidide.utils.flashError
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.io.File

open class BaseFragment @JvmOverloads constructor(contentLayoutId: Int = 0) :
    Fragment(contentLayoutId) {

    private var callback: OnDirectoryPickedCallback? = null
    private val allowedAuthorities =
        setOf(ANDROID_DOCS_AUTHORITY, ANDROIDIDE_DOCS_AUTHORITY)

    protected val viewLifecycleScope =
        CoroutineScope(Dispatchers.Default + CoroutineName(javaClass.simpleName))

    companion object {
        const val ANDROID_DOCS_AUTHORITY = "com.android.externalstorage.documents"
        const val ANDROIDIDE_DOCS_AUTHORITY = "com.itsaky.androidide.documents"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewLifecycleScope.cancel("${javaClass.simpleName} is being destroyed")
    }

    private val startForResult =
        registerForActivityResult(StartActivityForResult()) {
            val context = requireContext()
            val uri = it?.data?.data ?: return@registerForActivityResult
            val pickedDir = DocumentFile.fromTreeUri(context, uri)

            if (pickedDir == null) {
                flashError(string.err_invalid_data_by_intent)
                return@registerForActivityResult
            }

            if (!pickedDir.exists()) {
                flashError(getString(string.msg_picked_isnt_dir))
                return@registerForActivityResult
            }

            val docUri = buildDocumentUriUsingTree(uri, getTreeDocumentId(uri)!!)!!
            val docId = DocumentsContractCompat.getDocumentId(docUri)!!
            val authority = docUri.authority

            if (!allowedAuthorities.contains(authority)) {
                flashError(getString(string.err_authority_not_allowed, authority))
                return@registerForActivityResult
            }

            val dir =
                if (authority == ANDROIDIDE_DOCS_AUTHORITY) {
                    File(docId)
                } else {
                    val split = docId.split(':')
                    if ("primary" != split[0]) {
                        flashError(getString(string.msg_select_from_primary_storage))
                        return@registerForActivityResult
                    }

                    File(Environment.getExternalStorageDirectory(), split[1])
                }

            if (!dir.exists() || !dir.isDirectory) {
                flashError(getString(string.err_invalid_data_by_intent))
                return@registerForActivityResult
            }

            if (callback != null) {
                callback!!.onDirectoryPicked(dir)
            }
        }

    protected fun appendClickableSpan(
        sb: SpannableStringBuilder,
        @StringRes textRes: Int,
        span: ClickableSpan,
    ) {
        val str = getString(textRes)
        val split = str.split("@@", limit = 3)
        if (split.size != 3) {
            // Not a valid format
            sb.append(str)
            sb.append('\n')
            return
        }
        sb.append(split[0])
        sb.append(split[1], span, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(split[2])
        sb.append('\n')
    }

    protected fun pickDirectory(dirCallback: OnDirectoryPickedCallback?) {
        this.callback = dirCallback
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

            // Get the projects folder path
            val projectsDir = File(FileUtil.getExternalStorageDir(), PROJECTS_FOLDER)

            // Ensure the directory exists
            projectsDir.mkdirs()

            // Get the external storage path to construct the proper URI
            val externalStoragePath = Environment.getExternalStorageDirectory().path
            val relativePath = projectsDir.path.substring(externalStoragePath.length).trim('/')

            // Create the URI in the format expected by the document provider
            val initialUri =
                Uri.parse("content://com.android.externalstorage.documents/document/primary:$relativePath")

            // Set the initial URI for the file picker
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)

            this.startForResult.launch(intent)
        } catch (e: Exception) {
            requireActivity().flashError(getString(R.string.msg_dir_picker_failed, e.message))
        }
    }

    protected fun deleteDirectory(dirCallback: OnDirectoryPickedCallback?) {
        this.callback = dirCallback
        try {
            this.startForResult.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
        } catch (e: Exception) {
            requireActivity().flashError(getString(R.string.msg_dir_picker_failed, e.message))
        }
    }


    fun interface OnDirectoryPickedCallback {
        fun onDirectoryPicked(file: File)
    }
}
