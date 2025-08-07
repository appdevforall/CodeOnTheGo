package com.itsvks.layouteditor.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.Companion.isPhotoPickerAvailable
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
import androidx.annotation.RequiresApi
import androidx.viewpager2.widget.ViewPager2
import com.blankj.utilcode.util.ToastUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsvks.layouteditor.BaseActivity
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.adapters.PagerAdapter
import com.itsvks.layouteditor.adapters.models.DrawableFile
import com.itsvks.layouteditor.databinding.ActivityResourceManagerBinding
import com.itsvks.layouteditor.fragments.resources.ColorFragment
import com.itsvks.layouteditor.fragments.resources.DrawableFragment
import com.itsvks.layouteditor.fragments.resources.FontFragment
import com.itsvks.layouteditor.fragments.resources.StringFragment
import com.itsvks.layouteditor.managers.ProjectManager
import com.itsvks.layouteditor.utils.Constants
import com.itsvks.layouteditor.utils.FilePicker
import com.itsvks.layouteditor.utils.FileUtil
import com.itsvks.layouteditor.utils.SBUtils
import com.itsvks.layouteditor.vectormaster.VectorMasterDrawable
import java.io.FileNotFoundException

class ResourceManagerActivity : BaseActivity() {
  private var binding: ActivityResourceManagerBinding? = null
  private var drawableList: List<DrawableFile> = ArrayList()
  private var photoPicker: FilePicker? = null
  private var fontPicker: FilePicker? = null
  private var xmlPicker: FilePicker? = null
  private var pickMedia: ActivityResultLauncher<PickVisualMediaRequest>? = null
  private var requestPermission: ActivityResultLauncher<String>? = null

  @SuppressLint("UseCompatLoadingForDrawables")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityResourceManagerBinding.inflate(
      layoutInflater
    )
    setContentView(binding!!.getRoot())
    setSupportActionBar(binding!!.topAppBar)
    supportActionBar!!.setTitle(R.string.res_manager)
    binding!!.topAppBar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    if (ProjectManager.instance.openedProject == null) {
      val extras = intent.extras
      if (extras != null && extras.containsKey(Constants.EXTRA_KEY_PROJECT)) {
        ProjectManager.instance
          .openProject(extras.getParcelable(Constants.EXTRA_KEY_PROJECT))
      }
    }
    // loadDrawables()
    val adapter = PagerAdapter(supportFragmentManager, lifecycle)
    adapter.setup(binding!!.pager, binding!!.tabLayout)
    adapter.addFragmentToAdapter(
      DrawableFragment(drawableList),
      getString(R.string.drawable),
      getDrawable(R.drawable.image_outline)!!
    )
    adapter.addFragmentToAdapter(
      ColorFragment(),
      getString(R.string.color),
      getDrawable(R.drawable.palette_outline)!!
    )
    adapter.addFragmentToAdapter(
      StringFragment(),
      getString(R.string.string),
      getDrawable(R.drawable.format_letter_case)!!
    )
    adapter.addFragmentToAdapter(
      FontFragment(),
      getString(R.string.font),
      getDrawable(R.drawable.format_font)!!
    )
    adapter.setupPager(ViewPager2.ORIENTATION_HORIZONTAL)
    adapter.setupMediatorWithIcon()
    requestPermission = registerForActivityResult(
      ActivityResultContracts.RequestPermission()
    ) { onRequestPermission(it) }
    photoPicker = object : FilePicker(this) {
      override fun onPickFile(uri: Uri?) {
        onPickPhoto(uri)
      }
    }
    fontPicker = object : FilePicker(this) {
      override fun onPickFile(uri: Uri?) {
        onPickFont(uri)
      }
    }
    xmlPicker = object : FilePicker(this) {
      override fun onPickFile(uri: Uri?) {
        onPickXml(uri)
      }
    }
    pickMedia =
      registerForActivityResult<PickVisualMediaRequest, Uri>(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        onPickPhoto(uri)
      }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_resource_manager, menu)
    return super.onCreateOptionsMenu(menu)
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    val id = item.itemId
    val fragment = supportFragmentManager.findFragmentByTag("f" + binding!!.pager.currentItem)
    if (id == R.id.menu_add) {
      if (fragment != null) {
        when (fragment) {
          is DrawableFragment -> {
            MaterialAlertDialogBuilder(this)
              .setTitle(R.string.select_drawable_type)
              .setAdapter(
                ArrayAdapter(
                  this,
                  android.R.layout.simple_list_item_1, arrayOf("Vector Drawable", "Image Drawable")
                )
              ) { _, w: Int ->
                when (w) {
                  0 -> xmlPicker!!.launch("text/xml")
                  1 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launchPhotoPicker()
                  }
                }
              }
              .show()
          }

          is ColorFragment -> {
            fragment.addColor()
          }

          is StringFragment -> {
            fragment.addString()
          }

          is FontFragment -> {
            fontPicker!!.launch("font/*")
          }
        }
      } else {
        SBUtils.make(binding!!.getRoot(), "Something went wrong..")
          .setSlideAnimation()
          .showAsError()
      }
    } else if (id == R.id.menu_viewxml) {
      if (fragment != null) {
        when (fragment) {
          is ColorFragment -> {
            val it = Intent().setClass(this, ShowXMLActivity::class.java)
            it.putExtra(ShowXMLActivity.EXTRA_KEY_XML, ProjectManager.instance.colorsXml)
            startActivity(it)
          }

          is StringFragment -> {
            val it = Intent().setClass(this, ShowXMLActivity::class.java)
            it.putExtra(
              ShowXMLActivity.EXTRA_KEY_XML, ProjectManager.instance.stringsXml
            )
            startActivity(it)
          }

          else -> {
            SBUtils.make(binding!!.getRoot(), "Unavailable for this fragment..")
              .setSlideAnimation()
              .showAsSuccess()
          }
        }
      } else {
        SBUtils.make(binding!!.getRoot(), "Something went wrong..")
          .setSlideAnimation()
          .showAsError()
      }
    }
    return super.onOptionsItemSelected(item)
  }

  @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
  private fun launchPhotoPicker() {
    if (isPhotoPickerAvailable(this)) {
      if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
        == PackageManager.PERMISSION_DENIED
      ) {
        requestPermission!!.launch(Manifest.permission.READ_MEDIA_IMAGES)
        return
      }
      // Launch the photo picker and allow the user to choose only images.
      pickMedia!!.launch(
        PickVisualMediaRequest.Builder()
          .setMediaType(ImageOnly)
          .build()
      )
    } else {
      photoPicker!!.launch("image/*")
    }
  }

  private fun onPickPhoto(uri: Uri?) {
    if (uri != null) {
      Log.d("PhotoPicker", "Selected URI: $uri")
      if (FileUtil.isDownloadsDocument(uri)) {
        SBUtils.make(binding!!.getRoot(), R.string.select_from_storage).showAsError()
        return
      }
      val fragment = supportFragmentManager.findFragmentByTag("f" + binding!!.pager.currentItem)
      if (fragment is DrawableFragment) {
        fragment.addDrawable(uri)
      }
    } else {
      Log.d("PhotoPicker", "No media selected")
      SBUtils.make(binding!!.getRoot(), "No image selected").setFadeAnimation().show()
    }
  }

  private fun onPickFont(uri: Uri?) {
    if (uri != null) {
      Log.d("FontPicker", "Selected URI: $uri")
      if (FileUtil.isDownloadsDocument(uri)) {
        SBUtils.make(binding!!.getRoot(), R.string.select_from_storage).showAsError()
        return
      }
      val fragment = supportFragmentManager.findFragmentByTag("f" + binding!!.pager.currentItem)
      if (fragment is FontFragment) {
        fragment.addFont(uri)
      }
    } else {
      Log.d("FontPicker", "No font selected")
      SBUtils.make(binding!!.getRoot(), "No font selected").setFadeAnimation().show()
    }
  }

  private fun onPickXml(uri: Uri?) {
    if (uri != null) {
      Log.d("DrawablePicker", "Selected URI: $uri")
      if (FileUtil.isDownloadsDocument(uri)) {
        SBUtils.make(binding!!.getRoot(), R.string.select_from_storage).showAsError()
        return
      }
      val fragment = supportFragmentManager.findFragmentByTag("f" + binding!!.pager.currentItem)
      if (fragment is DrawableFragment) {
        try {
          val drawable = VectorMasterDrawable(this)
          drawable.setInputStream(contentResolver.openInputStream(uri))
          if (drawable.isVector) fragment.addDrawable(uri) else SBUtils.make(
            binding!!.getRoot(), "Not a valid vector drawable"
          )
            .setFadeAnimation()
            .setType(SBUtils.Type.INFO)
            .show()
        } catch (e: FileNotFoundException) {
          e.printStackTrace()
          ToastUtils.showShort(e.toString())
        }
      }
    } else {
      Log.d("DrawablePicker", "No drawable selected")
      SBUtils.make(binding!!.getRoot(), "No drawable selected").setFadeAnimation().show()
    }
  }

  private fun onRequestPermission(isGranted: Boolean) {
    if (isGranted) SBUtils.make(findViewById(android.R.id.content), R.string.permission_granted)
      .setSlideAnimation()
      .showAsSuccess() else SBUtils.make(
      findViewById(android.R.id.content),
      R.string.permission_denied
    ).setSlideAnimation().showAsError()
  }
}
