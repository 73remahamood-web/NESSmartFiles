package ye.nes.smartfiles

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.util.ArrayDeque

/** Persistent, resumable-by-file indexing. Android may still pause jobs due to power policies. */
class IndexWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        if (!Environment.isExternalStorageManager()) return Result.failure(Data.Builder().putString("error", "لم تُمنح صلاحية الوصول إلى الملفات").build())
        setForegroundAsync(notification(0))
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        val db = IndexDb(applicationContext)
        val run = System.currentTimeMillis()
        var count = 0; var extracted = 0; var unreadable = 0
        return try {
            val stack = ArrayDeque<File>()
            val roots = mutableSetOf<File>()
            roots.add(Environment.getExternalStorageDirectory())
            // Other mounted shared storage volumes (e.g., SD cards) when accessible.
            for (dir in applicationContext.getExternalFilesDirs(null).filterNotNull()) {
                val location = dir.absolutePath.substringBefore("/Android/data/")
                val root = File(location)
                if (root.exists() && root.isDirectory) roots.add(root)
            }
            roots.forEach { stack.add(it) }
            val visitedDirs = HashSet<String>()
            while (stack.isNotEmpty()) {
                if (isStopped) return Result.retry()
                val dir = stack.removeLast()
                // Prevent traversal loops through symlinks.
                val canonical = try { dir.canonicalPath } catch (_: Exception) { continue }
                if (!visitedDirs.add(canonical)) continue
                val children = try { dir.listFiles() } catch (_: Exception) { null }
                if (children == null) { unreadable++; continue }
                for (item in children) {
                    if (isStopped) return Result.retry()
                    if (item.isDirectory) {
                        if (item.name in setOf(".", "..")) continue
                        if (item.name == "data" && dir.name == "Android") continue
                        if (item.name == "obb" && dir.name == "Android") continue
                        stack.add(item)
                        continue
                    }
                    if (!item.isFile || !item.canRead()) { unreadable++; continue }
                    count++
                    try {
                        if (db.unchanged(item)) db.markSeen(item.absolutePath, run)
                        else {
                            val content = Extractors.extract(item, applicationContext)
                            db.replace(item, run, content)
                            if (content.coverage == "text" || content.coverage == "partial") extracted++
                        }
                    } catch (_: Exception) { unreadable++ }
                    if (count % 35 == 0) {
                        setProgressAsync(Data.Builder().putInt("scanned", count).putInt("text", extracted).putInt("unreadable", unreadable).build())
                        setForegroundAsync(notification(count))
                    }
                }
            }
            // Do not claim files are deleted if any directory failed to list.
            if (unreadable == 0) db.removeMissing(run)
            setProgressAsync(Data.Builder().putInt("scanned", count).putInt("text", extracted).putInt("unreadable", unreadable).build())
            Result.success(Data.Builder().putInt("scanned", count).putInt("text", extracted).putInt("unreadable", unreadable).build())
        } catch (ex: Exception) {
            Result.failure(Data.Builder().putString("error", ex.message ?: "Scan error").build())
        } finally { db.close() }
    }

    private fun notification(n: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("nes_indexing", "فهرسة الملفات", NotificationManager.IMPORTANCE_LOW))
        val note = NotificationCompat.Builder(applicationContext, "nes_indexing")
            .setContentTitle("NES Smart Files")
            .setContentText("تجري فهرسة الهاتف — $n ملف")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(913, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(913, note)
    }
}
