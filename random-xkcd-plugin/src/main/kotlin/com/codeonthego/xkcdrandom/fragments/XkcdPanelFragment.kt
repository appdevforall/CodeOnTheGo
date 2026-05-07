package com.codeonthego.xkcdrandom.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.codeonthego.xkcdrandom.R
import com.codeonthego.xkcdrandom.XkcdRandomPlugin
import com.codeonthego.xkcdrandom.cache.XkcdDiskCache
import com.codeonthego.xkcdrandom.net.XkcdApiClient
import com.codeonthego.xkcdrandom.net.XkcdComic
import com.codeonthego.xkcdrandom.ui.TapCountClassifier
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The "XKCD" tab body.
 *
 * Reading order:
 *   - onCreateView / onViewCreated: standard fragment setup, with the
 *     PluginFragmentHelper-wrapped inflater that lets us resolve our
 *     own R.layout.* against the plugin's APK.
 *   - the OnTouchListener: where ACTION_UP feeds the
 *     [TapCountClassifier] and decides to roll a new comic / copy URL /
 *     copy image.
 *   - loadRandomComic(): coroutine-based fetch + render.
 *
 * Why we don't use [android.view.GestureDetector]:
 *   - GestureDetector resolves single/double tap but not triple. The
 *     ticket explicitly calls for triple-tap to copy the image.
 *   - A small purpose-built [TapCountClassifier] reads cleaner in the
 *     Tier-3 walkthrough.
 */
class XkcdPanelFragment : Fragment() {

    private val api = XkcdApiClient()
    private lateinit var cache: XkcdDiskCache
    private val tapClassifier = TapCountClassifier()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Bound view references — populated in onViewCreated, cleared in
    // onDestroyView so we don't leak views across configuration changes.
    private var imageCard: FrameLayout? = null
    private var imageView: ImageView? = null
    private var captionView: TextView? = null
    private var altView: TextView? = null
    private var legendView: TextView? = null
    private var progressView: ProgressBar? = null
    private var emptyView: TextView? = null

    /** The comic we're currently displaying — used by the clipboard handlers. */
    @Volatile
    private var currentComic: XkcdComic? = null

    /** In-flight fetch, so rapid SINGLE-tap bursts don't fan out into N parallel fetches. */
    private var loadJob: Job? = null

    /** Pending tap-window timeout — cancelled if we resolve early. */
    private val resolveBurstRunnable = Runnable { handleClassification(tapClassifier.resolve()) }

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

        // Tap dispatch: ACTION_UP feeds the classifier *only* if the
        // gesture didn't move beyond the system touch slop — otherwise
        // every fling/scroll on a tall comic would also fire a tap.
        val root = view.findViewById<View>(R.id.xkcd_root)
        val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dx * dx + dy * dy <= touchSlop * touchSlop) {
                        handleTap()
                        root.performClick()  // accessibility-friendly
                    }
                }
            }
            // We never consume the event here; let the ScrollView keep
            // its scroll behavior so long content is still scrollable.
            false
        }

        // Render whatever's cached so the panel isn't empty on cold
        // start. Then on the very first show, kick off a fresh fetch
        // (savedInstanceState == null means we haven't been here yet).
        renderFromCache()
        if (savedInstanceState == null) loadRandomComic()
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacks(resolveBurstRunnable)
        imageCard = null
        imageView = null
        captionView = null
        altView = null
        legendView = null
        progressView = null
        emptyView = null
        super.onDestroyView()
    }

    // --- gesture handling ---

    private fun handleTap() {
        val now = SystemClock.uptimeMillis()
        val burstClosedEarly = tapClassifier.onTap(now)
        if (burstClosedEarly) {
            // Triple-tap: resolve immediately for snappy feedback.
            mainHandler.removeCallbacks(resolveBurstRunnable)
            handleClassification(tapClassifier.resolve())
            return
        }
        // Otherwise, wait one window for more taps. Re-arm the timeout
        // on every tap so the burst only fires after the user pauses.
        mainHandler.removeCallbacks(resolveBurstRunnable)
        mainHandler.postDelayed(resolveBurstRunnable, TapCountClassifier.DEFAULT_WINDOW_MS)
    }

    private fun handleClassification(c: TapCountClassifier.Classification?) {
        // Guard against the deferred Handler runnable firing after the
        // view has been torn down — would otherwise touch viewLifecycleOwner.
        if (!isAdded || view == null) return
        when (c) {
            TapCountClassifier.Classification.SINGLE -> loadRandomComic()
            TapCountClassifier.Classification.DOUBLE -> copyUrlToClipboard()
            TapCountClassifier.Classification.TRIPLE -> copyImageToClipboard()
            null -> { /* nothing to do */ }
        }
    }

    // --- clipboard ---

    private fun copyUrlToClipboard() {
        val comic = currentComic ?: return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("xkcd-url", comic.pageUrl))
        toast(getString(R.string.toast_url_copied, comic.pageUrl))
    }

    /**
     * Triple-tap → push the cached PNG to the clipboard as image/png.
     *
     * Plugin manifest providers don't get registered at runtime (plugins
     * are loaded via DexClassLoader, not installed as apps), so we route
     * through the host IDE's existing FileProvider authority. The host's
     * file_provider_paths.xml exposes `filesDir` (`<files-path … path="." />`),
     * so we copy the cached PNG to `filesDir/xkcd_share/last.png` and
     * grant a content URI from there.
     */
    private fun copyImageToClipboard() {
        val source = cache.loadImageFile()
        if (source == null) {
            toast(getString(R.string.toast_image_copy_failed))
            return
        }
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            // Up to 5 MB of file copy — off the main thread.
            val target = withContext(Dispatchers.IO) {
                val shareDir = File(ctx.filesDir, "xkcd_share").apply { mkdirs() }
                val out = File(shareDir, "last.png")
                try {
                    source.copyTo(out, overwrite = true)
                    out
                } catch (_: Exception) {
                    null
                }
            }
            if (target == null) {
                toast(getString(R.string.toast_image_copy_failed))
                return@launch
            }
            val authority = "${ctx.packageName}.providers.fileprovider"
            val uri = FileProvider.getUriForFile(ctx, authority, target)
            // ClipData.newUri queries the ContentResolver for the URI's
            // MIME type (image/png for our PNG), so the resulting clip
            // advertises image/* to paste targets.
            val clip = ClipData.newUri(ctx.contentResolver, "xkcd-image", uri)
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            toast(getString(R.string.toast_image_copied))
        }
    }

    private fun toast(text: String) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    // --- rendering ---

    private fun renderFromCache() {
        val comic = cache.loadComic() ?: run {
            showEmptyState()
            return
        }
        val imageFile = cache.loadImageFile()
        if (imageFile == null) {
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
     * Fetch a new random comic, then update the UI. All network IO and
     * bitmap decoding are on Dispatchers.IO; the callback hops back to
     * the main thread via the lifecycleScope's Main dispatcher.
     *
     * Skips if a previous fetch is still in flight (rapid taps no-op).
     */
    private fun loadRandomComic() {
        if (loadJob?.isActive == true) return
        // Avoid the cached-comic flash on cold start: only blank the
        // panel if we have nothing to show yet.
        if (currentComic == null) showLoading()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchDecodeAndCache() }
            val (comic, bmp) = result ?: run {
                if (currentComic == null) showEmptyState() else {
                    progressView?.visibility = View.GONE
                    toast(getString(R.string.toast_fetch_failed))
                }
                return@launch
            }
            currentComic = comic
            showComic(comic, bmp)
        }
    }

    /** Returns (comic, bitmap) on success, null on any IO/parse failure. */
    private suspend fun fetchDecodeAndCache(): Pair<XkcdComic, android.graphics.Bitmap>? {
        val comic = api.fetchRandom() ?: return null
        val bytes = api.openImageStream(comic.imageUrl)?.use { stream ->
            // Bounded read — see XkcdDiskCache.MAX_IMAGE_BYTES. We read
            // into an in-memory buffer because xkcd images are small
            // and BitmapFactory.decodeStream consumes the stream once.
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                // Cooperative cancellation — the read loop runs on
                // Dispatchers.IO; let lifecycleScope cancellation
                // interrupt mid-download.
                coroutineContext.ensureActive()
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > XkcdDiskCache.MAX_IMAGE_BYTES) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return null
        cache.save(comic, bytes)
        // Decode here on Dispatchers.IO, not back on Main — a 2 MB PNG
        // decode is enough to drop a frame on low-end devices.
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return comic to bmp
    }
}
