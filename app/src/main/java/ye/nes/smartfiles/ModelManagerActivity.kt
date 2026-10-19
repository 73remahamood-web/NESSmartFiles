package ye.nes.smartfiles

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Model control panel, local import, verified downloads, GGUF inspection, diagnostics. */
class ModelManagerActivity:AppCompatActivity(){
    private lateinit var installer:ModelInstaller
    private lateinit var status:TextView
    private lateinit var catalogList:LinearLayout
    private lateinit var details:TextView
    private lateinit var diagnosis:TextView
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val handler=Handler(Looper.getMainLooper())
    private var downloadPoll:Runnable?=null
    private val blue=Color.rgb(44,95,208)
    private fun dp(x:Int)=(x*resources.displayMetrics.density).toInt()
    private fun txt(s:String,size:Float=14f):TextView=TextView(this).apply {
        text=s;textSize=size;setTextColor(Color.rgb(34,45,69));setPadding(dp(12),dp(9),dp(12),dp(9))
        textDirection=View.TEXT_DIRECTION_FIRST_STRONG
    }
    private fun addButton(root:LinearLayout,title:String,fn:()->Unit){root.addView(Button(this).apply{
        text=title;isAllCaps=false;setOnClickListener{fn()}
    },LinearLayout.LayoutParams(-1,-2))}
    private val pickFile=registerForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null)install {installer.installUri(uri)}
    }
    private val pickDir=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        if(uri!=null)install{installer.installFolder(uri)}
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        installer=ModelInstaller(this)
        val scroll=ScrollView(this)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.rgb(246,248,253));setPadding(dp(12),dp(12),dp(12),dp(12))}
        scroll.addView(root);setContentView(scroll)
        root.addView(txt("🧠 مركز إدارة النماذج",23f))
        root.addView(txt("النماذج في مساحة التطبيق الخاصة؛ لا تُرفع ملفات الهاتف إلى الإنترنت. اختر ملف GGUF محليًا أو نزّل نموذجًا مع التحقق من SHA-256."))
        status=txt("جارٍ فحص النماذج...");root.addView(status)
        root.addView(txt("📦 النماذج المثبتة",19f));catalogList=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(catalogList)
        root.addView(txt("⬇️ تنزيل نموذج موثّق",19f))
        for(m in ModelInstaller.catalog)addButton(root,"تنزيل ${m.title} (نحو ${m.approxMB} MB)"){
            AlertDialog.Builder(this).setTitle("تأكيد التنزيل")
                .setMessage("سيتم تنزيل ${m.title}. إذا كان لديك نموذج سابق، فلن يُحذف تلقائيًا. استخدم Wi-Fi ومساحة كافية.")
                .setPositiveButton("تنزيل"){_,_->
                    try{installer.startCatalogDownload(m);status.text="بدأ تنزيل ${m.title}";watchDownload()}
                    catch(e:Exception){status.text="تعذر التنزيل: ${e.message}"}
                }.setNegativeButton("إلغاء",null).show()
        }
        addButton(root,"📁 استيراد GGUF أو ZIP من التخزين"){pickFile.launch(arrayOf("*/*"))}
        addButton(root,"📂 استيراد نموذج من مجلد"){pickDir.launch(null)}
        root.addView(txt("🔎 بيانات النموذج والمسار",19f));details=txt("");root.addView(details)
        root.addView(txt("🩺 تشخيص التشغيل",19f));diagnosis=txt("");root.addView(diagnosis)
        addButton(root,"تحديث الحالة والتحليل"){refresh()}
        addButton(root,"نسخ تقرير التشخيص"){val c=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            c.setPrimaryClip(ClipData.newPlainText("NES model diagnostics",details.text.toString()+"\n"+diagnosis.text.toString()))
            Toast.makeText(this,"تم نسخ التقرير",Toast.LENGTH_SHORT).show()}
        addButton(root,"العودة إلى المحادثة وتجربة تشغيل النموذج"){setResult(RESULT_OK);finish()}
        refresh();watchDownload()
    }
    private fun refresh(){
        catalogList.removeAllViews()
        val models=installer.allModels()
        status.text=if(models.isEmpty())"لا توجد نماذج مثبتة" else "${models.size} نموذج GGUF محلي"
        for(file in models){
            addButton(catalogList,"${if(file.canonicalPath==installer.target().canonicalPath)"✅" else "◻️"} ${file.name} — ${file.length()/1_000_000} MB"){
                AlertDialog.Builder(this).setTitle(file.name).setMessage(installer.describe(file))
                    .setPositiveButton("اختياره كنموذج نشط"){_,_->
                        installer.activate(file);getSharedPreferences("nes_settings",MODE_PRIVATE).edit().remove("model_last_error").apply()
                        setResult(RESULT_OK);refresh()
                    }.setNegativeButton("إغلاق",null).show()
            }
        }
        val target=installer.target()
        details.text=if(installer.modelExists())installer.describe(target) else
            "لم يُحدد نموذج صالح. المسار الافتراضي: ${target.absolutePath}\nالأرشيف المفهرس والمحادثات لا يعتمد وجودهما على النموذج."
        val mem=ActivityManager.MemoryInfo().also{(getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)}
        val p=getSharedPreferences("nes_settings",MODE_PRIVATE)
        val last=p.getString("model_error_history",null)
            ?:p.getString("model_last_error",null)?:"لا يوجد سجل فشل محفوظ"
        val info=if(installer.modelExists())GgufInspector.inspect(target)else null
        diagnosis.text="نظام أندرويد: ${android.os.Build.VERSION.RELEASE}\n"+
            "ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}\n"+
            "RAM متاحة للنظام: ${mem.availMem/1_000_000} MB؛ منخفضة: ${mem.lowMemory}\n"+
            "ذاكرة Native الحالية: ${Debug.getNativeHeapAllocatedSize()/1_000_000} MB\n"+
            "سعة متاحة في مجلد النماذج: ${target.parentFile?.usableSpace?.div(1_000_000)} MB\n"+
            "بنية GGUF: ${info?.architecture?:"غير مثبت"}\n"+
            "آخر فشل تشغيل:\n$last\n\n"+
            "تنبيه: UnsupportedArchitectureException قد يكون فشل تحميل GGUF وليس بالضرورة معالجًا غير مدعوم. " +
            "للتشخيص القطعي يلزم سجل llama.cpp الأصلي عبر logcat."
    }
    private fun install(fn:()->File){
        status.text="جارٍ النسخ والتحقق من GGUF محليًا..."
        scope.launch{
            try{val file=withContext(Dispatchers.IO){fn()}
                status.text="✅ تم استيراد: ${file.name}";setResult(RESULT_OK);refresh()
            }catch(e:Exception){status.text="❌ الاستيراد: ${e.message}"}
        }
    }
    private fun watchDownload(){
        downloadPoll?.let{handler.removeCallbacks(it)}
        val (id,m)=installer.savedDownload();if(id<0 || m==null)return
        val callback=object:Runnable {override fun run(){
            if(isFinishing)return
            val (s,percent)=installer.downloadState(id)
            when(s){
                DownloadManager.STATUS_SUCCESSFUL->{installer.clearDownload();install{installer.installDownloaded(m)}}
                DownloadManager.STATUS_FAILED->{installer.clearDownload();status.text="❌ فشل تنزيل ${m.title}"}
                else->{status.text="⬇️ ${m.title}: $percent";handler.postDelayed(this,1600)}
            }
        }}
        downloadPoll=callback;handler.post(callback)
    }
    override fun onResume(){super.onResume();if(::installer.isInitialized)refresh()}
    override fun onDestroy(){downloadPoll?.let{handler.removeCallbacks(it)};scope.cancel();super.onDestroy()}
}
