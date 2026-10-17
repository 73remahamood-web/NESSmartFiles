package ye.nes.smartfiles

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Model files are NEVER committed to the repository. Files are imported on-device. */
class ModelInstaller(private val context: Context) {
    companion object {
        const val MODEL_NAME = "local-chat.gguf"
        const val OFFICIAL_URL = "https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF/resolve/9447f74101aeb4e93621884dfa36ee8effb8831b/Qwen3.5-0.8B-Q4_0.gguf"
        // SHA-256 published alongside this exact revision on Hugging Face.
        const val OFFICIAL_SHA256 = "57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf"
        private const val MAX_BYTES = 2_500_000_000L
    }
    fun target(): File = File(context.filesDir, "models/$MODEL_NAME")
    fun modelExists(): Boolean = target().let { it.exists() && it.length() > 100_000L && checkMagic(it) }
    fun temporaryDownload(): File = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "nes_model_qwen35_q4.gguf")

    fun startOfficialDownload(): Long {
        val file = temporaryDownload()
        if (file.exists()) file.delete()
        val req = DownloadManager.Request(Uri.parse(OFFICIAL_URL))
            .setTitle("تنزيل نموذج NES المحلي")
            .setDescription("Qwen3.5-0.8B Q4 — تنزيل لمرة واحدة")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, file.name)
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
    }
    fun downloadState(id: Long): Pair<Int, String> {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) {
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                return status to if (total > 0) "${done * 100 / total}%" else "$done bytes"
            }
        }
        return DownloadManager.STATUS_FAILED to "لم يُعثر على التنزيل"
    }

    fun installDownloaded(): File {
        val file = temporaryDownload()
        require(file.exists()) { "ملف التنزيل غير موجود" }
        return FileInputStream(file).use { stream -> installStream(stream, OFFICIAL_SHA256) }.also { file.delete() }
    }

    fun installUri(uri: Uri): File {
        val name = (DocumentFile.fromSingleUri(context, uri)?.name ?: "unknown").lowercase()
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            if (name.endsWith(".zip")) installZip(stream)
            else if (name.endsWith(".gguf")) installStream(stream, null)
            else error("اختر ملف GGUF أو ZIP يحتوي على GGUF")
        } ?: error("تعذر فتح الملف")
    }

    fun installFolder(treeUri: Uri): File {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: error("المجلد غير متاح")
        val model = locateModel(tree, 2) ?: error("لا يوجد ملف GGUF داخل المجلد المحدد (عمق فحص حتى مستويين)")
        return context.contentResolver.openInputStream(model.uri)?.use { installStream(it, null) }
            ?: error("تعذر قراءة النموذج من المجلد")
    }

    private fun locateModel(root: DocumentFile, depth: Int): DocumentFile? {
        if (depth < 0) return null
        for (f in root.listFiles()) {
            if (f.isFile && f.name?.lowercase()?.endsWith(".gguf") == true) return f
        }
        for (f in root.listFiles()) if (f.isDirectory) locateModel(f, depth - 1)?.let { return it }
        return null
    }

    private fun installZip(source: InputStream): File {
        ZipInputStream(source.buffered()).use { zip ->
            var expectedSha: String? = null
            var entry = zip.nextEntry; var inspected = 0
            while (entry != null && inspected++ < 250) {
                if (!entry.isDirectory && entry.name == "manifest.json" && entry.size <= 16_384) {
                    val manifest = zip.readNBytes(16_384).toString(Charsets.UTF_8)
                    expectedSha = org.json.JSONObject(manifest).optString("sha256").takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
                }
                if (!entry.isDirectory && entry.name.substringAfterLast('/').lowercase().endsWith(".gguf")) {
                    // Copy only model bytes; never expand arbitrary ZIP paths (Zip Slip safe).
                    return installStream(zip, expectedSha)
                }
                zip.closeEntry(); entry = zip.nextEntry
            }
        }
        error("الأرشيف لا يحتوي على ملف GGUF مدعوم")
    }

    private fun installStream(input: InputStream, expectedHash: String?): File {
        val output = target(); output.parentFile?.mkdirs()
        val temp = File(output.parentFile, "${output.name}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(temp).use { out ->
                val buffer = ByteArray(128 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_BYTES) { "حجم النموذج أكبر من الحد المسموح (2.5 GB)" }
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
                out.fd.sync()
            }
            require(temp.length() > 100_000) { "ملف النموذج غير مكتمل" }
            require(checkMagic(temp)) { "هذا ليس ملف GGUF صالحًا" }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedHash != null) require(actual.equals(expectedHash, true)) { "فشل التحقق من SHA-256 للنموذج الذي تم تنزيله" }
            if (output.exists()) output.delete()
            require(temp.renameTo(output)) { "فشل حفظ النموذج داخل مساحة التطبيق" }
            return output
        } finally { if (temp.exists()) temp.delete() }
    }
    private fun checkMagic(file: File): Boolean = file.inputStream().use { input ->
        val bytes = ByteArray(4); input.read(bytes) == 4 && String(bytes, Charsets.US_ASCII) == "GGUF"
    }
}
