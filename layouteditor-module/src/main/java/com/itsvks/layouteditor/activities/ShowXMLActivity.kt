package com.itsvks.layouteditor.activities

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.blankj.utilcode.util.ClipboardUtils
import com.itsvks.layouteditor.BaseActivity
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.databinding.ActivityShowXMLBinding
import com.itsvks.layouteditor.utils.SBUtils.Companion.make
import com.itsvks.layouteditor.utils.Utils
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IThemeSource

class ShowXMLActivity : BaseActivity() {
  private var binding: ActivityShowXMLBinding? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityShowXMLBinding.inflate(layoutInflater)

    setContentView(binding!!.getRoot())
    setSupportActionBar(binding!!.topAppBar)
    supportActionBar!!.setTitle(R.string.xml_preview)

    binding!!.topAppBar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

    binding!!.editor.apply {
      setText(intent.getStringExtra(EXTRA_KEY_XML))
      typefaceText = jetBrainsMono()
      typefaceLineNumber = jetBrainsMono()
      isEditable = false
    }
    try {
      loadDefaultThemes()
      ThemeRegistry.getInstance().setTheme("darcula")
      loadDefaultLanguages()

      ensureTextmateTheme()

      val editor = binding!!.editor
      val language = TextMateLanguage.create("text.xml", true)
      editor.setEditorLanguage(language)
    } catch (e: Exception) {
      e.printStackTrace()
    }

    binding!!.fab.setOnClickListener {
      ClipboardUtils.copyText(binding!!.editor.text.toString())
      make(binding!!.getRoot(), getString(R.string.copied))
        .setAnchorView(binding!!.fab)
        .setSlideAnimation()
        .show()
    }

    binding!!.editor.setOnScrollChangeListener { _, _, y, _, oldY ->
      if (y > oldY + 20 && binding!!.fab.isExtended) {
        binding!!.fab.shrink()
      }
      if (y < oldY - 20 && !binding!!.fab.isExtended) {
        binding!!.fab.extend()
      }
      if (y == 0) {
        binding!!.fab.extend()
      }
    }
  }

  override fun onDestroy() {
    binding = null
    super.onDestroy()
  }

  @Throws(Exception::class)
  private fun loadDefaultThemes() {
    // add assets file provider
    FileProviderRegistry.getInstance()
      .addFileProvider(AssetsFileResolver(applicationContext.assets))

    val themeRegistry = ThemeRegistry.getInstance()
    val path = "editor/textmate/darcula.json"
    themeRegistry.loadTheme(
      ThemeModel(
        IThemeSource.fromInputStream(
          FileProviderRegistry.getInstance().tryGetInputStream(path), path, null
        ), "darcula"
      )
    )

    themeRegistry.setTheme("darcula")
  }

  private fun loadDefaultLanguages() {
    GrammarRegistry.getInstance().loadGrammars("editor/textmate/languages.json")
  }

  @Throws(Exception::class)
  private fun ensureTextmateTheme() {
    val editor = binding!!.editor
    var editorColorScheme: EditorColorScheme? = editor.colorScheme
    if (editorColorScheme !is TextMateColorScheme) {
      editorColorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
      editor.colorScheme = editorColorScheme
    }
  }

  private fun jetBrainsMono(): Typeface? {
    return ResourcesCompat.getFont(this, R.font.jetbrains_mono_regular)
  }

  companion object {
    const val EXTRA_KEY_XML: String = "xml"
  }
}
