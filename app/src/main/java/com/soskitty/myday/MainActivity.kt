package com.soskitty.myday

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var entriesDir: File
    private lateinit var imagesDir: File

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = fileChooserCallback
            fileChooserCallback = null
            if (cb == null) return@registerForActivityResult
            val uris = ArrayList<Uri>()
            val intent = result.data
            if (result.resultCode == RESULT_OK && intent != null) {
                val clip = intent.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } else intent.data?.let { uris.add(it) }
            }
            if (uris.isEmpty()) {
                cb.onReceiveValue(null)
                return@registerForActivityResult
            }
            val copied = ArrayList<Uri>()
            val pickDir = File(cacheDir, "pick").apply { mkdirs() }
            uris.forEachIndexed { i, u ->
                try {
                    val f = File(pickDir, "pick_${System.currentTimeMillis()}_$i${guessExtension(u)}")
                    contentResolver.openInputStream(u)?.use { ins ->
                        f.outputStream().use { outs -> ins.copyTo(outs) }
                    }
                    copied.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", f))
                } catch (e: Exception) {
                    Log.w("MyDay", "copy picked image failed", e)
                }
            }
            cb.onReceiveValue(copied.toTypedArray())
        }

    private val exportZipLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                val dest = uri
                Thread {
                    val ok = runCatching { writeZip(dest) }.isSuccess
                    notifyJs("__onExportResult", ok)
                }.start()
            }
        }

    private val exportFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                val dest = treeUri
                Thread {
                    val ok = runCatching { writeFolder(dest) }.isSuccess
                    notifyJs("__onExportResult", ok)
                }.start()
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val src = uri
                Thread {
                    val (ok, count) = runCatching { readZip(src) }
                        .fold(onSuccess = { true to it }, onFailure = { e -> Log.w("MyDay", "import failed", e); false to 0 })
                    notifyJs("__onImportResult", ok, count)
                }.start()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entriesDir = File(filesDir, "entries").apply { mkdirs() }
        imagesDir = File(filesDir, "images").apply { mkdirs() }
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                if (fileChooserCallback != null) {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                }
                fileChooserCallback = callback
                val accept = params?.acceptTypes?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "image/*"
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = accept
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                fileChooserLauncher.launch(intent)
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url?.toString() ?: return null
                if (url.startsWith("img://")) {
                    val name = url.removePrefix("img://").substringBefore('?').substringBefore('#')
                    val f = File(imagesDir, name)
                    return if (f.exists()) {
                        val mime = when (f.extension.lowercase()) {
                            "png" -> "image/png"
                            "webp" -> "image/webp"
                            "gif" -> "image/gif"
                            else -> "image/jpeg"
                        }
                        WebResourceResponse(mime, null, FileInputStream(f))
                    } else {
                        WebResourceResponse("image/jpeg", null, ByteArrayInputStream(ByteArray(0)))
                    }
                }
                return null
            }
        }
        webView.addJavascriptInterface(JsBridge(), "Android")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onBackPressed() {
        webView.evaluateJavascript("__handleBack()") { r ->
            if (r != "true") moveTaskToBack(true)
        }
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun getAllEntries(): String = try {
            loadEntriesJsonArray()
        } catch (e: Exception) {
            "[]"
        }

        @JavascriptInterface
        fun saveEntry(date: String, json: String): Boolean {
            if (!date.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) return false
            return try {
                JSONObject(json)
                File(entriesDir, "$date.json").writeText(json)
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun deleteEntry(date: String): Boolean {
            if (!date.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) return false
            val f = File(entriesDir, "$date.json")
            val deleted = f.delete()
            imagesDir.listFiles()?.filter { it.name.startsWith("${date}_") }?.forEach { it.delete() }
            return deleted
        }

        @JavascriptInterface
        fun saveImage(name: String, base64: String): Boolean {
            if (!name.matches(Regex("""[\w\-.]+\.(jpg|jpeg|png|webp)"""))) return false
            return try {
                val bytes = Base64.decode(base64, Base64.NO_WRAP or Base64.NO_PADDING)
                File(imagesDir, name).outputStream().use { it.write(bytes) }
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun deleteImage(name: String): Boolean {
            if (!name.matches(Regex("""[\w\-.]+\.(jpg|jpeg|png|webp)"""))) return false
            return File(imagesDir, name).delete()
        }

        @JavascriptInterface
        fun clearAll() {
            entriesDir.listFiles()?.forEach { it.delete() }
            imagesDir.listFiles()?.forEach { it.delete() }
        }

        @JavascriptInterface
        fun getStats(): String = try {
            val files = entriesDir.listFiles()?.filter { it.name.endsWith(".json") } ?: emptyArray()
            val imgs = imagesDir.listFiles()?.filter { it.isFile } ?: emptyArray()
            val bytes = files.sumOf { it.length() } + imgs.sumOf { it.length() }
            "{\"entries\":${files.size},\"images\":${imgs.size},\"bytes\":$bytes}"
        } catch (e: Exception) {
            "{\"entries\":0,\"images\":0,\"bytes\":0}"
        }

        @JavascriptInterface
        fun exportZip() = runOnUiThread {
            exportZipLauncher.launch("MyDay_${stamp()}.zip")
        }

        @JavascriptInterface
        fun exportFolder() = runOnUiThread {
            exportFolderLauncher.launch(null)
        }

        @JavascriptInterface
        fun importData() = runOnUiThread {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        @JavascriptInterface
        fun shareZip() = Thread { buildShare() }.start()

        @JavascriptInterface
        fun setTheme(dark: Boolean) = runOnUiThread { applyTheme(dark) }

        @JavascriptInterface
        fun toast(msg: String) = runOnUiThread {
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadEntriesJsonArray(): String {
        val files = entriesDir.listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.name } ?: return "[]"
        val sb = StringBuilder("[").apply { ensureCapacity(files.size * 512) }
        files.forEachIndexed { i, f ->
            if (i > 0) sb.append(",")
            sb.append(f.readText())
        }
        return sb.append("]").toString()
    }

    private fun exportContent(action: (path: String, open: () -> InputStream) -> Unit) {
        val entries = loadEntriesJsonArray()
        val template = assets.open("index.html").bufferedReader().use { it.readText() }
        val idx = template.indexOf("/*DATA*/")
        val html = if (idx >= 0) {
            template.substring(0, idx) + entries + template.substring(idx + "/*DATA*/".length)
        } else {
            template
        }
        action("index.html", { html.byteInputStream() })

        val data = "{\"app\":\"MyDay\",\"version\":1,\"exported\":\"${stamp()}\",\"entries\":$entries}"
        action("data.json", { data.byteInputStream() })

        entriesDir.listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
            action("entries/${f.name}", { f.inputStream() })
        }
        imagesDir.listFiles()?.filter { it.isFile }?.forEach { f ->
            action("images/${f.name}", { f.inputStream() })
        }
    }

    private fun writeZip(dest: Uri) {
        contentResolver.openOutputStream(dest)?.use { os ->
            ZipOutputStream(BufferedOutputStream(os)).use { zos ->
                exportContent { path, open ->
                    zos.putNextEntry(ZipEntry(path))
                    open().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        } ?: throw IOException("cannot open output stream")
    }

    private fun writeFolder(treeUri: Uri) {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: throw IOException("bad tree uri")
        val site = root.findFile("MyDay_${stamp()}") ?: root.createDirectory("MyDay_${stamp()}")
            ?: throw IOException("cannot create folder")
        site.listFiles().forEach { it.delete() }
        val entriesDoc = site.createDirectory("entries") ?: throw IOException("cannot create entries dir")
        val imagesDoc = site.createDirectory("images") ?: throw IOException("cannot create images dir")
        exportContent { path, open ->
            val parts = path.split('/')
            val doc = when (parts[0]) {
                "entries" -> {
                    val leaf = parts[1]
                    entriesDoc.findFile(leaf) ?: entriesDoc.createFile("application/json", leaf)
                }
                "images" -> {
                    val leaf = parts[1]
                    imagesDoc.findFile(leaf) ?: imagesDoc.createFile("image/jpeg", leaf)
                }
                else -> {
                    val leaf = parts[0]
                    site.findFile(leaf) ?: site.createFile("text/html", leaf)
                }
            } ?: throw IOException("cannot create $path")
            contentResolver.openOutputStream(doc.uri)?.use { os ->
                open().use { it.copyTo(os) }
            } ?: throw IOException("cannot write $path")
        }
    }

    private fun readZip(src: Uri): Int {
        var imported = 0
        contentResolver.openInputStream(src)?.use { ins ->
            ZipInputStream(BufferedInputStream(ins)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    val name = e.name
                    if (!e.isDirectory) {
                        if (name.endsWith(".json") && name.contains("entries/")) {
                            val leaf = name.substringAfterLast('/')
                            val date = leaf.removeSuffix(".json")
                            if (date.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
                                val json = zis.readBytes().toString(Charsets.UTF_8)
                                File(entriesDir, leaf).writeText(json)
                                imported++
                            }
                        } else if (name.contains("images/") && name.substringAfterLast('/').endsWith(".jpg")) {
                            val leaf = name.substringAfterLast('/')
                            File(imagesDir, leaf).outputStream().use { os -> zis.copyTo(os) }
                        }
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
        } ?: throw IOException("cannot open input stream")
        return imported
    }

    private fun buildShare() {
        try {
            val dir = File(cacheDir, "export").apply { mkdirs() }
            val zipFile = File(dir, "MyDay_${stamp()}.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                exportContent { path, open ->
                    zos.putNextEntry(ZipEntry(path))
                    open().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zipFile)
            runOnUiThread {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(Intent.createChooser(intent, "分享 MyDay 备份"))
                } catch (e: Exception) {
                    toast("分享失败")
                }
            }
        } catch (e: Exception) {
            runOnUiThread { toast("导出失败") }
        }
    }

    private fun applyTheme(dark: Boolean) {
        val status = if (dark) R.color.status_bar_dark else R.color.status_bar_light
        val nav = if (dark) R.color.nav_dark else R.color.nav_light
        window.statusBarColor = ContextCompat.getColor(this, status)
        window.navigationBarColor = ContextCompat.getColor(this, nav)
        if (Build.VERSION.SDK_INT >= 26) {
            window.decorView.systemUiVisibility = if (dark) 0 else
                (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.navigationBarDividerColor = ContextCompat.getColor(this, nav)
        }
    }

    private fun notifyJs(fn: String, ok: Boolean) {
        runOnUiThread {
            webView.evaluateJavascript("window.$fn && window.$fn($ok)", null)
        }
    }

    private fun notifyJs(fn: String, ok: Boolean, count: Int) {
        runOnUiThread {
            webView.evaluateJavascript("window.$fn && window.$fn($ok,$count)", null)
        }
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun guessExtension(u: Uri): String = when (contentResolver.getType(u)) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        else -> ".jpg"
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            webView.destroy()
        } catch (e: Exception) {
            // ignore
        }
    }
}
