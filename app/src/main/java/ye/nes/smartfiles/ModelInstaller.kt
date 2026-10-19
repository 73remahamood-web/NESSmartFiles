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

/** GGUF remains private on-device; supports downloaded catalog models or local file/folder. */
class ModelInstaller(private val context:Context) {
    data class CatalogModel(val id:String,val title:String,val fileName:String,val url:String,val sha256:String,val approxMB:Int)
    companion object {
        private const val LIMIT=2_500_000_000L
        val QWEN35=CatalogModel("qwen35","Qwen3.5 0.8B Q4_0 (تجريبي)","qwen3.5-0.8b-q4_0.gguf",
            "https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF/resolve/9447f74101aeb4e93621884dfa36ee8effb8831b/Qwen3.5-0.8B-Q4_0.gguf",
            "57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf",563)
        val QWEN3=CatalogModel("qwen3","Qwen3 0.6B Q4_0 (بديل نصي)","qwen3-0.6b-q4_0.gguf",
            "https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_0.gguf",
            "da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4",429)
        val catalog=listOf(QWEN3,QWEN35)
    }
    private fun prefs()=context.getSharedPreferences("nes_settings",Context.MODE_PRIVATE)
    private fun folder():File=File(context.filesDir,"models").apply{mkdirs()}
    private fun safe(name:String):String=name.replace(Regex("[^A-Za-z0-9._-]"),"_").take(96).let{
        if(it.lowercase().endsWith(".gguf"))it else "imported.gguf"
    }
    fun allModels():List<File> = (folder().listFiles()?: emptyArray()).filter{it.isFile && it.name.endsWith(".gguf",true)}.sortedBy{it.name}
    fun target():File {
        val selected=prefs().getString("active_model",null)
        val candidate=if(selected.isNullOrBlank())File(folder(),"local-chat.gguf")else File(folder(),safe(selected))
        return candidate
    }
    fun modelExists():Boolean=target().let{it.isFile && it.length()>100_000 && magic(it)}
    fun activate(file:File){
        require(file.parentFile?.canonicalPath==folder().canonicalPath && file.isFile && magic(file)){"ملف GGUF خارج مجلد النماذج أو غير صالح"}
        prefs().edit().putString("active_model",file.name).apply()
    }
    fun describe(file:File):String {
        val i=GgufInspector.inspect(file)
        return "الاسم: ${file.name}\nالمسار: ${file.absolutePath}\nالحجم: %.1f MB\n".format(file.length()/1_000_000.0)+
            "GGUF: ${if(i.valid)"${i.version} / ${i.architecture}" else "غير صالح: ${i.error}"}\n"+
            "نوع التكميم: ${i.quantization}\nالنموذج: ${i.name}\n"+
            "الحالة: ${if(file.canonicalPath==target().canonicalPath)"نشط" else "مثبت غير نشط"}"
    }
    private fun destination(name:String)=File(folder(),safe(name))
    private fun pendingFile(model:CatalogModel):File=File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"nes_${model.fileName}")
    fun startCatalogDownload(model:CatalogModel):Long {
        val temp=pendingFile(model);if(temp.exists())temp.delete()
        val request=DownloadManager.Request(Uri.parse(model.url))
            .setTitle("NES: ${model.title}")
            .setDescription("ملف GGUF إلى الهاتف، تنزيل لمرة واحدة")
            .setAllowedOverMetered(true).setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,temp.name)
        val id=(context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        prefs().edit().putLong("model_download_id",id).putString("model_download_kind",model.id).apply()
        return id
    }
    fun savedDownload():Pair<Long,CatalogModel?> {
        val p=prefs();val id=p.getLong("model_download_id",-1)
        return id to catalog.firstOrNull{it.id==p.getString("model_download_kind","")}
    }
    fun clearDownload(){prefs().edit().remove("model_download_id").remove("model_download_kind").apply()}
    fun downloadState(id:Long):Pair<Int,String> {
        val m=context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        m.query(DownloadManager.Query().setFilterById(id))?.use{c->if(c.moveToFirst()){
            val status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val total=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val done=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            return status to if(total>0)"${done*100/total}%" else "${done/1_000_000} MB"
        }}
        return DownloadManager.STATUS_FAILED to "لا يوجد تنزيل"
    }
    fun installDownloaded(model:CatalogModel):File {
        val file=pendingFile(model)
        require(file.isFile){"ملف التنزيل غير موجود"}
        val installed=FileInputStream(file).use{installStream(it,model.fileName,model.sha256)}
        file.delete();activate(installed);clearDownload();return installed
    }
    fun installUri(uri:Uri):File {
        val name=DocumentFile.fromSingleUri(context,uri)?.name?:"model.gguf"
        return context.contentResolver.openInputStream(uri)?.use{stream->
            val out=if(name.endsWith(".zip",true))installZip(stream)
            else {require(name.endsWith(".gguf",true)){"اختر GGUF أو ZIP"};installStream(stream,name,null)}
            activate(out);out
        }?:error("تعذر قراءة الملف")
    }
    fun installFolder(uri:Uri):File {
        val tree=DocumentFile.fromTreeUri(context,uri)?:error("المجلد غير متاح")
        fun locate(dir:DocumentFile,depth:Int):DocumentFile?{
            for(file in dir.listFiles())if(file.isFile && file.name?.endsWith(".gguf",true)==true)return file
            if(depth>0)for(file in dir.listFiles())if(file.isDirectory)locate(file,depth-1)?.let{return it}
            return null
        }
        val model=locate(tree,2)?:error("لم أجد GGUF داخل المجلد")
        return context.contentResolver.openInputStream(model.uri)?.use {activate(installStream(it,model.name?:"model.gguf",null));target()}
            ?:error("تعذر فتح GGUF")
    }
    private fun installZip(input:InputStream):File {
        ZipInputStream(input.buffered()).use{zip->
            var hash:String?=null;var next=zip.nextEntry;var found=0
            while(next!=null && found++<300){
                if(!next.isDirectory && next.name.substringAfterLast('/')=="manifest.json" && next.size >= 0L && next.size <= 16_384L){
                    hash=try{org.json.JSONObject(zip.readNBytes(16_384).toString(Charsets.UTF_8))
                        .optString("sha256").takeIf{it.matches(Regex("[0-9a-fA-F]{64}"))}}catch(_:Exception){null}
                } else if(!next.isDirectory && next.name.endsWith(".gguf",true))
                    return installStream(zip,next.name.substringAfterLast('/'),hash)
                zip.closeEntry();next=zip.nextEntry
            }
        }
        error("الأرشيف لا يحتوي على GGUF")
    }
    private fun installStream(input:InputStream,name:String,hash:String?):File {
        val out=destination(name);val temp=File(folder(),"${out.name}.part")
        val digest=MessageDigest.getInstance("SHA-256")
        try{
            FileOutputStream(temp).use{dst->
                val buf=ByteArray(128*1024);var size=0L
                while(true){val n=input.read(buf);if(n<0)break;size+=n
                    require(size<=LIMIT){"الحد الأقصى 2.5 GB"};digest.update(buf,0,n);dst.write(buf,0,n)}
                dst.fd.sync()
            }
            require(temp.length()>100_000 && magic(temp)){"ملف غير مكتمل أو ليس GGUF"}
            val actual=digest.digest().joinToString(""){"%02x".format(it)}
            require(hash==null || actual.equals(hash,true)){"فشل تحقق SHA-256، لا تستخدم الملف"}
            // The metadata inspector is deliberately lightweight; do not reject a
            // legitimate future GGUF variant solely because it has new metadata tags.
            if(out.exists())require(out.delete()){ "تعذر استبدال ملف النموذج" }
            require(temp.renameTo(out)){"تعذر حفظ النموذج"}
            return out
        }finally{if(temp.exists())temp.delete()}
    }
    private fun magic(file:File):Boolean=try{file.inputStream().use{it.readNBytes(4).contentEquals("GGUF".toByteArray())}}catch(_:Exception){false}
    // Legacy API from V0.1 for MainActivity if still invoked.
    fun startOfficialDownload()=startCatalogDownload(QWEN35)
    fun installDownloaded()=installDownloaded(QWEN35)
}
