package com.codeonthego.xkcdrandom.fragments

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.codeonthego.xkcdrandom.R
import com.codeonthego.xkcdrandom.XkcdRandomPlugin
import com.codeonthego.xkcdrandom.cache.XkcdDiskCache
import com.codeonthego.xkcdrandom.net.XkcdApiClient
import com.codeonthego.xkcdrandom.net.XkcdComic
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The "XKCD" tab body.
 *
 * Lifecycle:
 *   - onCreateView: inflate the plugin's layout (note the
 *     PluginFragmentHelper-provided inflater — see [onGetLayoutInflater])
 *   - onViewCreated: paint whatever's in the disk cache, then on first
 *     show kick off a fresh fetch (so the user sees a comic without
 *     having to tap)
 *   - tap on the panel: fetch a new random comic
 *
 * Multi-tap gestures (double = copy URL, triple = copy image) land in
 * commit 3 — they delegate to a small TapCountClassifier that this
 * fragment will host.
 */
class XkcdPanelFragment : Fragment() {

    private val api = XkcdApiClient()
    private lateinit var cache: XkcdDiskCache

    // Bound view references — populated in onViewCreated, cleared in
    // onDestroyView so we don't leak views across configuration changes.
    private var imageCard: FrameLayout? = null
    private var imageView: ImageView? = null
    private var captionView: TextView? = null
    private var altView: TextView? = null
    private var legendView: TextView? = null
    private var progressView: ProgressBar? = null
    private var emptyView: TextView? = null

    /** The comic we're currently displaying — used by commit 3's clipboard handlers. */
    @Volatile
    private var currentComic: XkcdComic? = null

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        // Plugins must wrap the inflater so R.layout.* resolves against
        // the plugin's APK resources, not the host IDE's. Without this
        // you get a Resources$NotFoundException at inflate time.
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(XkcdRandomPlugin.PLUGIN_ID, inflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cache = XkcdDiskCache(requireContext().applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_xkcd_panel, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        imageCard = view.findViewById(R.id.xkcd_image_card)
        imageView = view.findViewById(R.id.xkcd_image)
        captionView = view.findViewById(R.id.xkcd_caption)
        altView = view.findViewById(R.id.xkcd_alt)
        legendView = view.findViewById(R.id.xkcd_legend)
        progressView = view.findViewById(R.id.xkcd_progress)
        emptyView = view.findViewById(R.id.xkcd_empty)

        // Tap anywhere on the panel rolls a new comic. Commit 3 will
        // upgrade this from setOnClickListener to a TapCountClassifier
        // that distinguishes single / double / triple.
        view.findViewById<View>(R.id.xkcd_root).setOnClickListener {
            loadRandomComic()
        }

        // Render whatever's cached so the panel isn't empty on cold
        // start. Then on the very first show, kick off a fresh fetch
        // (savedInstanceState == null means we haven't been here yet).
        renderFromCache()
        if (savedInstanceState == null) loadRandomComic()
    }

    override fun onDestroyView() {
        imageCard = null
        imageView = null
        captionView = null
        altView = null
        legendView = null
        progressView = null
        emptyView = null
        super.onDestroyView()
    }

    // --- rendering ---

    private fun renderFromCache() {
        val comic = cache.loadComic() ?: run {
            showEmptyState()
            return
        }
        val imageFile = cache.loadImageFile()
        if (imageFile == null) {
            // We have metadata but the image disappeared — treat as empty.
            showEmptyState()
            return
        }
        val bmp = BitmapFactory.decodeFile(imageFile.absolutePath) ?: run {
            showEmptyState()
            return
        }
        currentComic = comic
        showComic(comic, bmp)
    }

    private fun showLoading() {
        progressView?.visibility = View.VISIBLE
        emptyView?.visibility = View.GONE
    }

    private fun showEmptyState() {
        progressView?.visibility = View.GONE
        imageCard?.visibility = View.GONE
        captionView?.visibility = View.GONE
        altView?.visibility = View.GONE
        emptyView?.visibility = View.VISIBLE
        emptyView?.setText(R.string.empty_offline)
    }

    private fun showComic(comic: XkcdComic, bmp: android.graphics.Bitmap) {
        progressView?.visibility = View.GONE
        emptyView?.visibility = View.GONE
        imageCard?.visibility = View.VISIBLE
        imageView?.setImageBitmap(bmp)
        captionView?.apply {
            visibility = View.VISIBLE
            text = getString(R.string.comic_caption, comic.num, comic.title)
        }
        altView?.apply {
            visibility = View.VISIBLE
            text = getString(R.string.comic_alt_prefix, comic.alt)
        }
    }

    // --- networking ---

    /**
     * Fetch a new random comic, then update the UI. All network IO is
     * on Dispatchers.IO; the callback hops back to the main thread via
     * the lifecycleScope's Main dispatcher.
     */
    private fun loadRandomComic() {
        showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchAndCacheRandom() }
            // viewLifecycleOwner.lifecycleScope auto-cancels in onDestroyView,
            // so we don't need an extra isAdded check before touching views.
            val (comic, bytes) = result ?: run {
                // Fetch failed — fall back to whatever we last cached
                // (so a transient network blip doesn't blank the panel).
                if (currentComic == null) showEmptyState() else {
                    progressView?.visibility = View.GONE
                }
                return@launch
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp == null) {
                showEmptyState()
                return@launch
            }
            currentComic = comic
            showComic(comic, bmp)
        }
    }

    /** Returns (comic, pngBytes) on success, null on any IO/parse failure. */
    private fun fetchAndCacheRandom(): Pair<XkcdComic, ByteArray>? {
        val comic = api.fetchRandom() ?: return null
        val bytes = api.openImageStream(comic.imageUrl)?.use { stream ->
            // Bounded read — see XkcdDiskCache.MAX_IMAGE_BYTES. We read
            // into an in-memory buffer because xkcd images are small and
            // BitmapFactory.decodeStream consumes the stream once.
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > XkcdDiskCache.MAX_IMAGE_BYTES) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return null
        cache.save(comic, bytes)
        return comic to bytes
    }
}
