package ye.nes.smartfiles

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** Bounded extraction: never assume that a discovered file has been fully understood. */
object Extractors {
    data class Extraction(val text: String, val category: String, val coverage: String)
    private const val MAX_CHARS = 240_000
    private const val MAX_FILE_BYTES = 48L * 1024 * 1024
    private val textExt = setOf(
        "txt","md","markdown","csv","tsv","log","ini","conf","cfg","properties","env","xml","html","htm",
        "css","scss","js","jsx","ts","tsx","php","py","kt","kts","java","c","h","cpp","hpp","rs","go","rb","swift","dart","sql","sh","bash","json","jsonl","yaml","yml","toml","gradle","bat","ps1","svg","vue","svelte","dockerfile"
    )
    private val officeExt = setOf("docx","xlsx","pptx","odt","ods","odp","epub")
    private val imageExt = setOf("jpg","jpeg","png","webp","gif","bmp","heic","avif","tiff")
    private val audioExt = setOf("mp3","wav","m4a","ogg","opus","amr","flac","aac")
    private val videoExt = setOf("mp4","mov","mkv","webm","avi","3gp","mpeg")

    fun extract(file: File, context: Context): Extraction {
        val ext = file.extension.lowercase()
        if (file.length() > MAX_FILE_BYTES && ext !in (imageExt + audioExt + videoExt)) {
            return Extraction("", "large", "metadata")
        }
        return try {
            when {
                ext in textExt || file.name.equals("Dockerfile", true) -> Extraction(readText(file), "text/code", "text")
                ext == "pdf" -> pdfText(file)
                ext in officeExt || ext == "zip" || ext == "jar" || ext == "apk" -> zipText(file, ext)
                ext in imageExt -> imageMeta(file)
                ext in audioExt || ext in videoExt -> mediaMeta(file, ext in videoExt)
                else -> Extraction("", "other", "metadata")
            }
        } catch (ex: Exception) {
            Extraction("", ext.ifEmpty { "unknown" }, "error:${ex.javaClass.simpleName}")
        } catch (_: OutOfMemoryError) {
            Extraction("", ext.ifEmpty { "unknown" }, "error:memory")
        }
    }

    private fun readText(file: File): String = file.inputStream().use { readBounded(it, MAX_CHARS) }

    private fun readBounded(input: InputStream, charLimit: Int): String {
        val data = input.readNBytes((charLimit * 3).coerceAtMost(700_000))
        if (data.isEmpty()) return ""
        // Distinguish UTF16 text from UTF8 for common exported files.
        val text = when {
            data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte() -> String(data, Charset.forName("UTF-16LE"))
            data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte() -> String(data, Charset.forName("UTF-16BE"))
            else -> String(data, Charsets.UTF_8)
        }
        return text.take(charLimit)
    }

    private fun pdfText(file: File): Extraction = PDDocument.load(file).use { pdf ->
        val stripper = PDFTextStripper()
        val result = StringBuilder()
        val pages = pdf.numberOfPages.coerceAtMost(75)
        for (p in 1..pages) {
            if (result.length >= MAX_CHARS) break
            stripper.startPage = p; stripper.endPage = p
            result.append("\n[الصفحة $p]\n")
            result.append(stripper.getText(pdf).take((MAX_CHARS - result.length).coerceAtLeast(0)))
        }
        Extraction(result.toString(), "pdf", if (pdf.numberOfPages > pages || result.length >= MAX_CHARS) "partial" else if (result.length < pages * 5) "needs_ocr" else "text")
    }

    private fun zipText(file: File, ext: String): Extraction {
        val result = StringBuilder()
        var scanned = 0
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements() && scanned < 600 && result.length < MAX_CHARS) {
                val entry = entries.nextElement(); scanned++
                if (entry.isDirectory) continue
                result.append("\n[داخل الأرشيف: ${entry.name.take(240)}]\n")
                val lower = entry.name.lowercase()
                val isOfficeXml = ext in officeExt && (
                    (ext == "docx" && (lower == "word/document.xml" || lower.startsWith("word/header") || lower.startsWith("word/footer"))) ||
                    (ext == "xlsx" && (lower == "xl/sharedstrings.xml" || lower.startsWith("xl/worksheets/sheet"))) ||
                    (ext == "pptx" && lower.startsWith("ppt/slides/slide") && lower.endsWith(".xml")) ||
                    ((ext == "odt" || ext == "ods" || ext == "odp") && lower == "content.xml") ||
                    (ext == "epub" && (lower.endsWith(".xhtml") || lower.endsWith(".html")))
                )
                val isTextInsideZip = ext !in officeExt && entry.name.substringAfterLast('.', "").lowercase() in textExt
                if (entry.size in 1..180_000 && (isOfficeXml || isTextInsideZip)) {
                    val raw = zip.getInputStream(entry).use { readBounded(it, 14_000) }
                    val cleaned = if (isOfficeXml) raw.replace(Regex("<[^>]+>"), " ")
                        .replace(Regex("\\s+"), " ") else raw
                    result.append(cleaned.take(14_000))
                }
                result.append('\n')
            }
        }
        return Extraction(result.toString().take(MAX_CHARS), if (ext in officeExt) "office" else "archive", "partial")
    }

    private fun imageMeta(file: File): Extraction {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val dimensions = if (options.outWidth > 0) "${options.outWidth} × ${options.outHeight}" else "غير محدد"
        return Extraction("صورة: ${file.name}; الأبعاد: $dimensions. لم يتم التعرف على النص داخل الصورة بعد.", "image", "metadata")
    }

    private fun mediaMeta(file: File, video: Boolean): Extraction {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val millis = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: file.name
            val description = "${if (video) "فيديو" else "صوت"}: $title، المدة ${millis / 1000} ثانية. لم يُفرغ الكلام بعد."
            Extraction(description, if (video) "video" else "audio", "metadata")
        } finally { try { r.release() } catch (_: Exception) {} }
    }
}
