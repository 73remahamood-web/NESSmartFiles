package ye.nes.smartfiles

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager

/** Dedicated live file-indexing dashboard with totals, file-type breakdown and errors. */
class IndexDashboardActivity:AppCompatActivity(){
    private lateinit var db:IndexDb
    private lateinit var summary:TextView
    private lateinit var phase:TextView
    private lateinit var details:TextView
    private lateinit var errors:TextView
    private lateinit var progress:ProgressBar
    private val blue=Color.rgb(46,99,213)
    private fun dp(x:Int)=(x*resources.displayMetrics.density).toInt()
    private fun text(t:String,size:Float=15f,color:Int=Color.rgb(30,41,59)):TextView=TextView(this).apply {
        text=t;textSize=size;setTextColor(color);setPadding(dp(13),dp(9),dp(13),dp(9));textDirection=View.TEXT_DIRECTION_FIRST_STRONG
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        db=IndexDb(this)
        val sc=ScrollView(this)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.rgb(246,248,253));setPadding(dp(12),dp(12),dp(12),dp(12))}
        sc.addView(root);setContentView(sc)
        root.addView(text("📂 مركز الفهرسة",23f,blue))
        root.addView(text("الفهرس يبقى على الهاتف. تُفحص الملفات المتغيرة فقط، وتُحفظ المحادثات دون مساس.",13f))
        phase=text("جارٍ قراءة حالة الفهرس...");root.addView(phase)
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100}
        root.addView(progress,LinearLayout.LayoutParams(-1,dp(12)))
        summary=text("ملفات: ...",17f);root.addView(summary)
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        root.addView(actions)
        fun action(t:String,cb:()->Unit){actions.addView(Button(this).apply{text=t;isAllCaps=false;setOnClickListener{cb()}},
            LinearLayout.LayoutParams(0,-2,1f))}
        action("بدء / تحديث"){start()}
        action("تحديث الأرقام"){refresh()}
        action("إلغاء المهمة"){WorkManager.getInstance(this).cancelUniqueWork("nes_full_index")}
        root.addView(text("📊 توزيع أنواع الملفات",19f,blue))
        details=text("جارٍ تحميل الأعداد...");root.addView(details)
        root.addView(text("⚠️ ملفات تعذر استخراجها",19f,blue))
        errors=text("");root.addView(errors)
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("nes_full_index").observe(this){list->
            val info=list?.firstOrNull{it.state==WorkInfo.State.RUNNING}?:list?.lastOrNull()?:return@observe
            val p=if(info.state==WorkInfo.State.SUCCEEDED)info.outputData else info.progress
            val st=p.getString("stage")?:""
            val total=p.getInt("total",0);val done=p.getInt("done",0)
            val found=p.getInt("discovered",0);val failed=p.getInt("errors",0)
            phase.text=when(info.state){
                WorkInfo.State.RUNNING -> if(st=="discovery" || total==0) "مرحلة الاكتشاف: $found ملفًا. الإجمالي النهائي سيظهر بعد اكتمال المسح."
                   else "استخراج المحتوى: $done / $total · أخطاء هذه الجولة: $failed"
                WorkInfo.State.SUCCEEDED -> "✅ اكتملت جولة الفهرسة الأخيرة"
                WorkInfo.State.FAILED -> "❌ فشلت الجولة: ${info.outputData.getString("error")?:"خطأ غير معروف"}"
                WorkInfo.State.CANCELLED -> "⏸️ أُوقفت الجولة. لن تضيع المقاطع المفهرسة سابقًا."
                else -> "حالة المهمة: ${info.state}"
            }
            progress.isIndeterminate=info.state==WorkInfo.State.RUNNING && total==0
            if(total>0)progress.progress=((done.toDouble()/total)*100).toInt().coerceIn(0,100)
            if(info.state!=WorkInfo.State.RUNNING || done%105==0)refresh()
        }
        refresh()
    }
    private fun start(){
        if(!Environment.isExternalStorageManager()){
            val uri=Uri.parse("package:$packageName")
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,uri));return
        }
        WorkManager.getInstance(this).enqueueUniqueWork("nes_full_index",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<IndexWorker>().build())
    }
    private fun refresh(){
        try {
            val s=db.getStats()
            summary.text="إجمالي الملفات المسجلة: ${s.files}\nمفهرسة (نص أو بيانات وصفية): ${s.ready}\n"+
                "بانتظار المعالجة: ${s.queued}  •  فشل: ${s.failed}\n"+
                "محتوى نصي: ${s.textReady} ملفًا  •  مقاطع نصية: ${s.parts}\n"+
                "وصف فقط، يحتاج معالجة متخصصة للصوت/الصور/الصيغ الأخرى: ${s.pendingMedia}"
            details.text=db.getTypes().joinToString("\n"){"${FileClassifier.icon(it.category)} ${FileClassifier.arabic(it.category)}: ${it.total}"}
            val failures=db.getFailures(35)
            errors.text=if(failures.isEmpty())"لا توجد حالات خطأ استخراج مسجلة." else failures.joinToString("\n\n") {"${it.path}\n${it.reason}"}
        } catch(ex:Exception){summary.text="تعذر تحديث الإحصاءات: ${ex.message}"}
    }
    override fun onResume(){super.onResume();if(::db.isInitialized)refresh()}
    override fun onDestroy(){db.close();super.onDestroy()}
}
