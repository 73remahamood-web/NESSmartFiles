package ye.nes.smartfiles

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ServiceInfo
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.util.ArrayDeque

/** Two-pass indexer: fast discovery/counts, then resumable extraction for changed files only. */
class IndexWorker(ctx: Context, params: WorkerParameters) : Worker(ctx,params) {
    override fun doWork():Result {
        if (!Environment.isExternalStorageManager()) return Result.failure(data("error", "اسمح للتطبيق بإدارة الملفات أولًا"))
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        val db=IndexDb(applicationContext)
        val run=System.currentTimeMillis()
        var discovered=0;var extracted=0;var failures=0;var dirsFailed=0
        try {
            setForegroundAsync(notification("اكتشاف الملفات",0))
            val roots=linkedSetOf<File>()
            roots.add(Environment.getExternalStorageDirectory())
            for (f in applicationContext.getExternalFilesDirs(null).filterNotNull()) {
                val p=f.absolutePath.substringBefore("/Android/data/")
                val root=File(p)
                if(root.exists() && root.isDirectory)roots.add(root)
            }
            val stack=ArrayDeque<File>()
            roots.forEach{stack.add(it)}
            val visited=HashSet<String>()
            var inTransaction=false
            try {
                db.beginDiscoveryBatch();inTransaction=true
                while(stack.isNotEmpty()) {
                    if(isStopped) { db.abortDiscoveryBatch();inTransaction=false;return Result.retry() }
                    val dir=stack.removeLast()
                    val canonical=try{dir.canonicalPath}catch (_:Exception){dirsFailed++;continue}
                    if(!visited.add(canonical))continue
                    val children=try{dir.listFiles()}catch (_:Exception){null}
                    if(children==null){dirsFailed++;continue}
                    for(file in children) {
                        if(isStopped){db.abortDiscoveryBatch();inTransaction=false;return Result.retry()}
                        if(file.isDirectory){
                            if(file.name in setOf(".",".."))continue
                            if(dir.name=="Android" && file.name in setOf("data","obb"))continue
                            stack.add(file);continue
                        }
                        if(!file.isFile || !file.canRead())continue
                        try { db.discover(file,run,FileClassifier.category(file));discovered++ }
                        catch (_:Exception) { failures++ }
                        if(discovered>0 && discovered%350==0) {
                            db.finishDiscoveryBatch();inTransaction=false
                            report("discovery",discovered,0,0,failures)
                            db.beginDiscoveryBatch();inTransaction=true
                        }
                    }
                }
                db.finishDiscoveryBatch();inTransaction=false
            } finally { if(inTransaction)db.abortDiscoveryBatch() }
            // An inaccessible directory may contain files that we must not treat as deleted.
            if(dirsFailed==0 && !isStopped) db.removeMissing(run)
            val total=discovered
            val queued=db.countQueued(run)
            val alreadyDone=(total-queued).coerceAtLeast(0L).toInt()
            report("extract",total,alreadyDone,total,failures)
            var processed=alreadyDone
            while(true){
                if(isStopped) return Result.retry()
                val batch=db.pendingPaths(run,150)
                if(batch.isEmpty())break
                for(path in batch){
                    if(isStopped)return Result.retry()
                    val file=File(path)
                    try {
                        if(!file.isFile || !file.canRead()) {
                            db.markFailed(path,"الملف غير متاح أثناء المعالجة")
                            failures++
                        } else {
                            val category=FileClassifier.category(file)
                            val value=if(FileClassifier.skipHeavyExtraction(category))
                                Extractors.Extraction("",category,"metadata")
                            else Extractors.extract(file,applicationContext)
                            db.replace(file,run,value)
                            if(value.coverage=="text" || value.coverage=="partial")extracted++
                            if(value.coverage.startsWith("error:"))failures++
                        }
                    } catch (oom:OutOfMemoryError){
                        db.markFailed(path,"نقص ذاكرة أثناء استخراج النص")
                        failures++
                    } catch (ex:Exception){
                        db.markFailed(path,"${ex.javaClass.simpleName}: ${ex.message ?: "خطأ"}")
                        failures++
                    }
                    processed++
                    if(processed%35==0)report("extract",total,processed,total,failures)
                }
            }
            report("finished",total,total,total,failures)
            return Result.success(Data.Builder().putString("stage","finished")
                .putInt("total",total).putInt("done",total).putInt("errors",failures+dirsFailed)
                .putInt("extracted",extracted).build())
        } catch(ex:Exception){
            return Result.failure(data("error",ex.javaClass.simpleName+": "+(ex.message?:"غير معروف")))
        } finally {db.close()}
    }
    private fun data(key:String,value:String)=Data.Builder().putString(key,value).build()
    private fun report(stage:String,discovered:Int,done:Int,total:Int,errors:Int){
        val progress=Data.Builder().putString("stage",stage)
            .putInt("discovered",discovered).putInt("total",total)
            .putInt("done",done).putInt("errors",errors).build()
        setProgressAsync(progress)
        if (discovered%350==0 || stage!="discovery") setForegroundAsync(notification(
            if(stage=="discovery") "اكتشاف: $discovered ملف" else "فهرسة: $done / $total ملف",done))
    }
    private fun notification(title:String,n:Int):ForegroundInfo {
        val manager=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel("nes_indexing","فهرسة الملفات",NotificationManager.IMPORTANCE_LOW))
        val note=NotificationCompat.Builder(applicationContext,"nes_indexing")
            .setContentTitle("NES Smart Files").setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build()
        return if(Build.VERSION.SDK_INT>=29) ForegroundInfo(913,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(913,note)
    }
}
