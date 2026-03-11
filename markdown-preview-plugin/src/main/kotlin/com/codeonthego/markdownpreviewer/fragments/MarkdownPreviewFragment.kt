package com.codeonthego.markdownpreviewer.fragments

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.codeonthego.markdownpreviewer.MarkdownPreviewerPlugin
import com.codeonthego.markdownpreviewer.PreviewState
import com.codeonthego.markdownpreviewer.R
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeFileService
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MarkdownPreviewFragment : Fragment() {

    companion object {
        private const val PLUGIN_ID = MarkdownPreviewerPlugin.PLUGIN_ID
    }

    private var projectService: IdeProjectService? = null
    private var fileService: IdeFileService? = null

    // UI Components
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var btnSelectFile: Button
    private lateinit var btnSelectFromStorage: Button
    private lateinit var btnToggleView: Button
    private lateinit var currentFileText: TextView
    private lateinit var placeholderText: TextView
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var sourceScrollView: ScrollView
    private lateinit var sourceCodeText: TextView

    private var currentFile: File? = null
    private var currentContent: String? = null
    private var isShowingSource: Boolean = false
    private lateinit var markwon: Markwon
    private var pickFileLauncher: androidx.activity.result.ActivityResultLauncher<Intent>? = null

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(PLUGIN_ID, inflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register activity result launcher early in lifecycle
        pickFileLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) {
                    loadFileFromUri(uri)
                }
            }
        }

        // Get services from the plugin's service registry
        runCatching {
            val serviceRegistry = PluginFragmentHelper.getServiceRegistry(PLUGIN_ID)
            projectService = serviceRegistry?.get(IdeProjectService::class.java)
            fileService = serviceRegistry?.get(IdeFileService::class.java)
        }

        // Initialize Markwon with extensions
        markwon = Markwon.builder(requireContext())
            .usePlugin(TablePlugin.create(requireContext()))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TaskListPlugin.create(requireContext()))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(ImagesPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_markdown_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupWebView()
        setupClickListeners()
        checkPendingFile()
    }

    fun checkPendingFile() {
        PreviewState.consumePendingFile()?.let { path ->
            loadFile(File(path))
        }
    }

    private fun initializeViews(view: View) {
        webView = view.findViewById(R.id.web_view)
        progressBar = view.findViewById(R.id.progress_bar)
        statusContainer = view.findViewById(R.id.status_container)
        statusText = view.findViewById(R.id.tv_status)
        btnSelectFile = view.findViewById(R.id.btn_select_file)
        btnSelectFromStorage = view.findViewById(R.id.btn_select_storage)
        btnToggleView = view.findViewById(R.id.btn_toggle_view)
        currentFileText = view.findViewById(R.id.tv_current_file)
        placeholderText = view.findViewById(R.id.tv_placeholder)
        emptyStateContainer = view.findViewById(R.id.empty_state_container)
        sourceScrollView = view.findViewById(R.id.source_scroll_view)
        sourceCodeText = view.findViewById(R.id.tv_source_code)
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                hideProgress()
            }
        }

        // Set background color based on theme
        val isDarkMode = isNightMode()
        webView.setBackgroundColor(if (isDarkMode) Color.parseColor("#000000") else Color.WHITE)
    }

    private fun setupClickListeners() {
        btnSelectFile.setOnClickListener {
            showProjectFilePicker()
        }

        btnSelectFromStorage.setOnClickListener {
            openFilePicker()
        }

        btnToggleView.setOnClickListener {
            toggleSourcePreview()
        }
    }

    private fun toggleSourcePreview() {
        isShowingSource = !isShowingSource
        updateViewMode()
    }

    private fun updateViewMode() {
        if (isShowingSource) {
            // Show source code
            webView.visibility = View.GONE
            sourceScrollView.visibility = View.VISIBLE
            sourceCodeText.text = currentContent ?: ""
            btnToggleView.text = "Preview"
        } else {
            // Show preview
            sourceScrollView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            btnToggleView.text = "Source"
        }
    }

    private fun showProjectFilePicker() {
        val project = projectService?.getCurrentProject()
        if (project == null) {
            showToast("No project available")
            return
        }

        // Find all supported files in the project
        viewLifecycleOwner.lifecycleScope.launch {
            showProgress("Scanning project...")
            
            val files = withContext(Dispatchers.IO) {
                findSupportedFiles(project.rootDir)
            }
            
            hideProgress()
            
            if (files.isEmpty()) {
                showToast("No Markdown or HTML files found in project")
                return@launch
            }

            // Show file selection dialog
            showFileSelectionDialog(files, project.rootDir)
        }
    }

    private fun findSupportedFiles(rootDir: File): List<File> {
        val supportedFiles = mutableListOf<File>()
        
        rootDir.walkTopDown()
            .filter { it.isFile && MarkdownPreviewerPlugin.isSupportedFile(it) }
            .filter { !it.absolutePath.contains("/build/") && !it.absolutePath.contains("/.") }
            .forEach { supportedFiles.add(it) }
        
        return supportedFiles.sortedBy { it.name.lowercase() }
    }

    private fun showFileSelectionDialog(files: List<File>, rootDir: File) {
        val fileNames = files.map { file ->
            file.absolutePath.removePrefix(rootDir.absolutePath + "/")
        }.toTypedArray()

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Select File to Preview")
            .setItems(fileNames) { _, which ->
                loadFile(files[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "text/markdown",
                "text/x-markdown",
                "text/html",
                "text/plain"
            ))
        }
        pickFileLauncher?.launch(intent) ?: showToast("File picker not available")
    }

    private fun loadFileFromUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            showProgress("Loading file...")

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    // Get actual filename from ContentResolver
                    val fileName = getDisplayNameFromUri(uri) ?: "file"
                    
                    // Read content from URI
                    val content = requireContext().contentResolver.openInputStream(uri)?.use { 
                        it.bufferedReader().readText() 
                    } ?: throw Exception("Could not read file")

                    // Determine file type
                    val lowerName = fileName.lowercase()
                    val isMarkdown = lowerName.endsWith(".md") || 
                        lowerName.endsWith(".markdown") ||
                        lowerName.endsWith(".mdown") ||
                        lowerName.endsWith(".mkd") ||
                        lowerName.endsWith(".mkdn") ||
                        // Fallback: check if content looks like markdown
                        (!lowerName.endsWith(".html") && !lowerName.endsWith(".htm") && 
                         content.trim().startsWith("#"))

                    Triple(content, isMarkdown, fileName)
                }
            }

            result.fold(
                onSuccess = { (content, isMarkdown, fileName) ->
                    hideProgress()
                    currentContent = content
                    currentFileText.text = fileName
                    currentFileText.visibility = View.VISIBLE
                    placeholderText.visibility = View.GONE
                    emptyStateContainer.visibility = View.GONE
                    btnToggleView.visibility = View.VISIBLE
                    isShowingSource = false
                    webView.visibility = View.VISIBLE
                    sourceScrollView.visibility = View.GONE
                    btnToggleView.text = "Source"
                    renderContent(content, isMarkdown)
                },
                onFailure = { e ->
                    hideProgress()
                    showError("Failed to load file: ${e.message}")
                }
            )
        }
    }

    private fun getDisplayNameFromUri(uri: Uri): String? {
        // Try to get display name from ContentResolver
        return try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        cursor.getString(nameIndex)
                    } else null
                } else null
            }
        } catch (e: Exception) {
            // Fallback to path segment
            uri.lastPathSegment?.substringAfterLast("/")
        }
    }

    private fun loadFile(file: File) {
        if (!file.exists()) {
            showError("File not found: ${file.name}")
            return
        }

        if (!file.canRead()) {
            showError("Cannot read file: ${file.name}")
            return
        }

        currentFile = file

        viewLifecycleOwner.lifecycleScope.launch {
            showProgress("Loading ${file.name}...")

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    file.readText()
                }
            }

            result.fold(
                onSuccess = { content ->
                    hideProgress()
                    currentContent = content
                    currentFileText.text = file.name
                    currentFileText.visibility = View.VISIBLE
                    placeholderText.visibility = View.GONE
                    emptyStateContainer.visibility = View.GONE
                    btnToggleView.visibility = View.VISIBLE
                    isShowingSource = false
                    webView.visibility = View.VISIBLE
                    sourceScrollView.visibility = View.GONE
                    btnToggleView.text = "Source"
                    
                    val isMarkdown = MarkdownPreviewerPlugin.isMarkdownFile(file)
                    renderContent(content, isMarkdown)
                },
                onFailure = { e ->
                    hideProgress()
                    showError("Failed to load file: ${e.message}")
                }
            )
        }
    }

    private fun renderContent(content: String, isMarkdown: Boolean) {
        val html = if (isMarkdown) {
            convertMarkdownToHtml(content)
        } else {
            // Already HTML, just wrap with our styles
            wrapHtmlContent(content)
        }

        webView.loadDataWithBaseURL(
            null,
            html,
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun convertMarkdownToHtml(markdown: String): String {
        // Use Markwon to convert to HTML-like content
        // We'll render to a Spanned first, then create styled HTML
        val styled = markwon.toMarkdown(markdown)
        
        // Build HTML with proper styling
        val isDarkMode = isNightMode()
        val textColor = if (isDarkMode) "#E0E0E0" else "#121212"
        val bgColor = if (isDarkMode) "#000000" else "#FFFFFF"
        val codeBlockBg = if (isDarkMode) "#0D0D0D" else "#F5F5F5"
        val codeBorder = if (isDarkMode) "#1A1A1A" else "#E0E0E0"
        val linkColor = if (isDarkMode) "#64B5F6" else "#1976D2"
        val blockquoteBorder = if (isDarkMode) "#616161" else "#BDBDBD"
        val blockquoteBg = if (isDarkMode) "#0A0A0A" else "#FAFAFA"
        val tableBorder = if (isDarkMode) "#1A1A1A" else "#DDDDDD"
        val tableHeaderBg = if (isDarkMode) "#121212" else "#F0F0F0"
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    * {
                        box-sizing: border-box;
                    }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
                        font-size: 16px;
                        line-height: 1.6;
                        color: $textColor;
                        background-color: $bgColor;
                        padding: 16px;
                        margin: 0;
                        word-wrap: break-word;
                    }
                    h1, h2, h3, h4, h5, h6 {
                        margin-top: 24px;
                        margin-bottom: 16px;
                        font-weight: 600;
                        line-height: 1.25;
                    }
                    h1 { font-size: 2em; border-bottom: 1px solid $codeBorder; padding-bottom: 0.3em; }
                    h2 { font-size: 1.5em; border-bottom: 1px solid $codeBorder; padding-bottom: 0.3em; }
                    h3 { font-size: 1.25em; }
                    h4 { font-size: 1em; }
                    p {
                        margin-top: 0;
                        margin-bottom: 16px;
                    }
                    a {
                        color: $linkColor;
                        text-decoration: none;
                    }
                    a:hover {
                        text-decoration: underline;
                    }
                    code {
                        font-family: 'SF Mono', Monaco, 'Cascadia Code', Consolas, monospace;
                        font-size: 0.875em;
                        background-color: $codeBlockBg;
                        padding: 0.2em 0.4em;
                        border-radius: 4px;
                    }
                    pre {
                        background-color: $codeBlockBg;
                        border: 1px solid $codeBorder;
                        border-radius: 6px;
                        padding: 16px;
                        overflow-x: auto;
                        font-size: 0.875em;
                        line-height: 1.45;
                    }
                    pre code {
                        background-color: transparent;
                        padding: 0;
                        border-radius: 0;
                    }
                    blockquote {
                        margin: 0 0 16px 0;
                        padding: 0 16px;
                        border-left: 4px solid $blockquoteBorder;
                        background-color: $blockquoteBg;
                        color: ${if (isDarkMode) "#AAAAAA" else "#666666"};
                    }
                    ul, ol {
                        margin-top: 0;
                        margin-bottom: 16px;
                        padding-left: 2em;
                    }
                    li {
                        margin-bottom: 4px;
                    }
                    table {
                        border-collapse: collapse;
                        width: 100%;
                        margin-bottom: 16px;
                    }
                    th, td {
                        border: 1px solid $tableBorder;
                        padding: 8px 12px;
                        text-align: left;
                    }
                    th {
                        background-color: $tableHeaderBg;
                        font-weight: 600;
                    }
                    img {
                        max-width: 100%;
                        height: auto;
                    }
                    hr {
                        border: none;
                        border-top: 1px solid $codeBorder;
                        margin: 24px 0;
                    }
                    .task-list-item {
                        list-style-type: none;
                    }
                    .task-list-item input[type="checkbox"] {
                        margin-right: 8px;
                    }
                </style>
            </head>
            <body>
                ${convertMarkdownToBasicHtml(markdown)}
            </body>
            </html>
        """.trimIndent()
    }

    private fun convertMarkdownToBasicHtml(markdown: String): String {
        var html = markdown
        
        // Code blocks (fenced with ```) - must be done first
        html = html.replace(Regex("```(\\w*)\\n([\\s\\S]*?)```")) { match ->
            val lang = match.groupValues[1]
            val code = match.groupValues[2].trim()
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            "\n<pre><code class=\"language-$lang\">$code</code></pre>\n"
        }
        
        // Inline code
        html = html.replace(Regex("`([^`]+)`")) { match ->
            val code = match.groupValues[1]
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            "<code>$code</code>"
        }
        
        // Tables
        html = convertTables(html)
        
        // Headers
        html = html.replace(Regex("^######\\s+(.*)$", RegexOption.MULTILINE)) { "<h6>${it.groupValues[1]}</h6>" }
        html = html.replace(Regex("^#####\\s+(.*)$", RegexOption.MULTILINE)) { "<h5>${it.groupValues[1]}</h5>" }
        html = html.replace(Regex("^####\\s+(.*)$", RegexOption.MULTILINE)) { "<h4>${it.groupValues[1]}</h4>" }
        html = html.replace(Regex("^###\\s+(.*)$", RegexOption.MULTILINE)) { "<h3>${it.groupValues[1]}</h3>" }
        html = html.replace(Regex("^##\\s+(.*)$", RegexOption.MULTILINE)) { "<h2>${it.groupValues[1]}</h2>" }
        html = html.replace(Regex("^#\\s+(.*)$", RegexOption.MULTILINE)) { "<h1>${it.groupValues[1]}</h1>" }
        
        // Bold and italic
        html = html.replace(Regex("\\*\\*\\*(.+?)\\*\\*\\*")) { "<strong><em>${it.groupValues[1]}</em></strong>" }
        html = html.replace(Regex("___(.+?)___")) { "<strong><em>${it.groupValues[1]}</em></strong>" }
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*")) { "<strong>${it.groupValues[1]}</strong>" }
        html = html.replace(Regex("__(.+?)__")) { "<strong>${it.groupValues[1]}</strong>" }
        html = html.replace(Regex("\\*([^*]+)\\*")) { "<em>${it.groupValues[1]}</em>" }
        html = html.replace(Regex("_([^_]+)_")) { "<em>${it.groupValues[1]}</em>" }
        
        // Strikethrough
        html = html.replace(Regex("~~(.+?)~~")) { "<del>${it.groupValues[1]}</del>" }
        
        // Links
        html = html.replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")) { 
            "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" 
        }
        
        // Images
        html = html.replace(Regex("!\\[([^\\]]*)]\\(([^)]+)\\)")) { 
            "<img src=\"${it.groupValues[2]}\" alt=\"${it.groupValues[1]}\">" 
        }
        
        // Blockquotes
        html = html.replace(Regex("^>\\s+(.*)$", RegexOption.MULTILINE)) { 
            "<blockquote>${it.groupValues[1]}</blockquote>" 
        }
        
        // Horizontal rules
        html = html.replace(Regex("^(-{3,}|\\*{3,}|_{3,})$", RegexOption.MULTILINE)) { "<hr>" }
        
        // Task lists (before regular lists)
        html = html.replace(Regex("^\\s*-\\s+\\[x\\]\\s+(.*)$", RegexOption.MULTILINE)) { 
            "<li class=\"task-list-item\"><input type=\"checkbox\" checked disabled> ${it.groupValues[1]}</li>" 
        }
        html = html.replace(Regex("^\\s*-\\s+\\[\\s\\]\\s+(.*)$", RegexOption.MULTILINE)) { 
            "<li class=\"task-list-item\"><input type=\"checkbox\" disabled> ${it.groupValues[1]}</li>" 
        }
        
        // Unordered lists
        html = html.replace(Regex("^\\s*[-*+]\\s+(.*)$", RegexOption.MULTILINE)) { 
            "<li>${it.groupValues[1]}</li>" 
        }
        
        // Ordered lists
        html = html.replace(Regex("^\\s*\\d+\\.\\s+(.*)$", RegexOption.MULTILINE)) { 
            "<li>${it.groupValues[1]}</li>" 
        }
        
        // Wrap consecutive <li> items in <ul>
        html = html.replace(Regex("((?:<li[^>]*>.*?</li>\\s*)+)")) { match ->
            val content = match.groupValues[1]
            if (content.contains("task-list-item")) {
                "<ul style=\"list-style: none; padding-left: 0;\">$content</ul>"
            } else {
                "<ul>$content</ul>"
            }
        }
        
        // Paragraphs (wrap remaining text blocks)
        val lines = html.split("\n")
        val result = StringBuilder()
        var inParagraph = false
        
        for (line in lines) {
            val trimmed = line.trim()
            val isBlockElement = trimmed.startsWith("<h") || 
                trimmed.startsWith("<ul") ||
                trimmed.startsWith("<ol") ||
                trimmed.startsWith("<table") ||
                trimmed.startsWith("<blockquote") ||
                trimmed.startsWith("<pre") ||
                trimmed.startsWith("<hr") ||
                trimmed.startsWith("</")
            
            when {
                trimmed.isEmpty() -> {
                    if (inParagraph) {
                        result.append("</p>\n")
                        inParagraph = false
                    }
                    result.append("\n")
                }
                isBlockElement -> {
                    if (inParagraph) {
                        result.append("</p>\n")
                        inParagraph = false
                    }
                    result.append(trimmed).append("\n")
                }
                else -> {
                    if (!inParagraph) {
                        result.append("<p>")
                        inParagraph = true
                    } else {
                        result.append("<br>")
                    }
                    result.append(trimmed)
                }
            }
        }
        if (inParagraph) {
            result.append("</p>")
        }
        
        return result.toString()
    }
    
    private fun convertTables(markdown: String): String {
        val lines = markdown.split("\n")
        val result = StringBuilder()
        var inTable = false
        var headerProcessed = false
        
        for (i in lines.indices) {
            val line = lines[i].trim()
            
            // Check if this is a table row (contains |)
            if (line.startsWith("|") && line.endsWith("|")) {
                // Check if next line is separator (|---|---|)
                val isHeader = i + 1 < lines.size && 
                    lines[i + 1].trim().matches(Regex("\\|[-:\\s|]+\\|"))
                
                // Check if this line IS a separator
                val isSeparator = line.matches(Regex("\\|[-:\\s|]+\\|"))
                
                if (isSeparator) {
                    // Skip separator line
                    continue
                }
                
                if (!inTable) {
                    result.append("<table>\n")
                    inTable = true
                    headerProcessed = false
                }
                
                val cells = line.split("|")
                    .filter { it.isNotBlank() }
                    .map { it.trim() }
                
                if (isHeader && !headerProcessed) {
                    result.append("<thead><tr>")
                    cells.forEach { cell -> result.append("<th>$cell</th>") }
                    result.append("</tr></thead>\n<tbody>\n")
                    headerProcessed = true
                } else {
                    result.append("<tr>")
                    cells.forEach { cell -> result.append("<td>$cell</td>") }
                    result.append("</tr>\n")
                }
            } else {
                if (inTable) {
                    result.append("</tbody></table>\n")
                    inTable = false
                    headerProcessed = false
                }
                result.append(line).append("\n")
            }
        }
        
        if (inTable) {
            result.append("</tbody></table>\n")
        }
        
        return result.toString()
    }

    private fun wrapHtmlContent(html: String): String {
        // If already has <html> tag, return as-is with dark mode support injected
        if (html.lowercase().contains("<html")) {
            return injectDarkModeStyles(html)
        }

        val isDarkMode = isNightMode()
        val textColor = if (isDarkMode) "#E0E0E0" else "#121212"
        val bgColor = if (isDarkMode) "#000000" else "#FFFFFF"

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        font-size: 16px;
                        line-height: 1.6;
                        color: $textColor;
                        background-color: $bgColor;
                        padding: 16px;
                        margin: 0;
                    }
                    img { max-width: 100%; height: auto; }
                </style>
            </head>
            <body>
                $html
            </body>
            </html>
        """.trimIndent()
    }

    private fun injectDarkModeStyles(html: String): String {
        if (!isNightMode()) return html

        val darkModeStyle = """
            <style>
                body { 
                    background-color: #000000 !important; 
                    color: #E0E0E0 !important; 
                }
            </style>
        """.trimIndent()

        return if (html.lowercase().contains("<head>")) {
            html.replace(Regex("<head>", RegexOption.IGNORE_CASE)) { 
                "<head>$darkModeStyle" 
            }
        } else if (html.lowercase().contains("<html>")) {
            html.replace(Regex("<html>", RegexOption.IGNORE_CASE)) { 
                "<html><head>$darkModeStyle</head>" 
            }
        } else {
            "$darkModeStyle$html"
        }
    }

    private fun isNightMode(): Boolean {
        return (resources.configuration.uiMode and 
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) == 
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun showProgress(message: String) {
        progressBar.visibility = View.VISIBLE
        statusContainer.visibility = View.VISIBLE
        statusText.text = message
    }

    private fun hideProgress() {
        progressBar.visibility = View.GONE
        statusContainer.visibility = View.GONE
    }

    private fun showError(message: String) {
        statusContainer.visibility = View.VISIBLE
        statusText.text = message
        statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error_text))
        statusContainer.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.status_error_background))
    }

    private fun showToast(message: String) {
        activity?.runOnUiThread {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
