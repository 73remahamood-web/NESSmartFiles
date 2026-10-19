package ye.nes.smartfiles

import java.io.File

/** Cheap extension-based classification; no model or media decoder is needed during discovery. */
object FileClassifier {
    private val images = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif", "tif", "tiff", "svg", "ico")
    private val videos = setOf("mp4", "mov", "mkv", "avi", "webm", "3gp", "mpeg", "mpg", "flv", "wmv", "m4v", "ts")
    private val audio = setOf("mp3", "wav", "aac", "m4a", "ogg", "opus", "amr", "flac", "wma", "aiff", "mid", "midi")
    private val archives = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "apk", "jar", "aab", "iso")
    private val code = setOf("js", "jsx", "ts", "tsx", "php", "py", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "rs", "go", "rb", "swift", "dart", "sql", "sh", "bash", "ps1", "bat", "vue", "svelte", "gradle", "json", "yml", "yaml", "toml", "xml", "html", "htm", "css", "scss")
    private val text = setOf("txt", "md", "markdown", "csv", "tsv", "log", "ini", "conf", "cfg", "properties", "env", "rtf")
    fun category(file: File): String {
        val ext = file.extension.lowercase()
        return when {
            file.name.equals("Dockerfile", true) || ext in code -> "code"
            ext in images -> "image"
            ext in videos -> "video"
            ext in audio -> "audio"
            ext == "pdf" -> "pdf"
            ext in setOf("doc", "docx", "odt") -> "document"
            ext in setOf("xls", "xlsx", "ods") -> "spreadsheet"
            ext in setOf("ppt", "pptx", "odp") -> "presentation"
            ext in archives -> "archive"
            ext in text -> "text"
            else -> "other"
        }
    }
    fun icon(category: String): String = when (category) {
        "image" -> "🖼️"; "video" -> "🎬"; "audio" -> "🎧"; "pdf" -> "📕"
        "document" -> "📄"; "spreadsheet" -> "📊"; "presentation" -> "📽️"
        "archive" -> "🗜️"; "code" -> "💻"; "text" -> "📝"; else -> "📁"
    }
    fun arabic(category: String): String = when (category) {
        "image" -> "الصور"; "video" -> "الفيديوهات"; "audio" -> "التسجيلات الصوتية"; "pdf" -> "PDF"
        "document" -> "Word والمستندات"; "spreadsheet" -> "Excel والجداول"; "presentation" -> "العروض التقديمية"
        "archive" -> "الأرشيفات والمضغوطات"; "code" -> "الأكواد"; "text" -> "الملفات النصية"; else -> "ملفات أخرى"
    }
    fun skipHeavyExtraction(category: String): Boolean = category in setOf("image", "video", "audio")
}
