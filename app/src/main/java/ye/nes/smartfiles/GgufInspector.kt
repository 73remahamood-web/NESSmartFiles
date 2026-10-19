package ye.nes.smartfiles

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read bounded GGUF metadata without loading model weights into RAM. */
object GgufInspector {
    data class Info(val valid:Boolean, val version:Int=0, val architecture:String="غير معروف",
                    val name:String="", val quantization:String="", val error:String="")
    fun inspect(file:File):Info {
        if(!file.isFile || file.length()<32)return Info(false,error="ملف مفقود أو قصير")
        return try { RandomAccessFile(file,"r").use { r ->
            fun bytes(n:Int):ByteArray {require(n in 0..8192 && r.filePointer+n<=file.length()){ "بيانات GGUF غير مكتملة" };return ByteArray(n).also{r.readFully(it)} }
            fun number(n:Int):Long {val b=ByteBuffer.wrap(bytes(n)).order(ByteOrder.LITTLE_ENDIAN)
                return when(n){1->(b.get().toInt() and 255).toLong();2->(b.short.toInt() and 65535).toLong();4->(b.int.toLong() and 0xffffffffL);8->b.long;else->error("invalid size")}}
            fun str():String {val len=number(8);require(len in 0..8192){"طول سلسلة غير صالح"};return String(bytes(len.toInt()),Charsets.UTF_8)}
            fun skip(n:Long){require(n>=0 && n <=file.length()-r.filePointer){"بيانات النموذج ناقصة"};r.seek(r.filePointer+n)}
            fun readValue(type:Int,depth:Int=0):String {
                require(depth<3){"بنية GGUF معقدة"}
                return when(type){
                    0,1,7-> {val v=number(1);v.toString()}
                    2,3->{number(2).toString()}
                    4,5,6->{number(4).toString()}
                    10,11,12->{number(8).toString()}
                    8->str()
                    9->{val itemType=number(4).toInt();val length=number(8)
                        require(length in 0..5_000_000){"مصفوفة GGUF كبيرة جدًا"}
                        val fixed=when(itemType){0,1,7->1L;2,3->2L;4,5,6->4L;10,11,12->8L;else->0L}
                        if(fixed>0)skip(length*fixed) else for(i in 0 until length.toInt())readValue(itemType,depth+1)
                        "array($length)"
                    }
                    else->error("حقل GGUF غير معروف: $type")
                }
            }
            require(String(bytes(4),Charsets.US_ASCII)=="GGUF"){"بصمة GGUF غير موجودة"}
            val version=number(4).toInt();require(version in 2..4){"GGUF version غير مدعوم"}
            number(8) // tensors
            val metadataCount=number(8);require(metadataCount in 0..4096){"حقول GGUF غير معتادة"}
            var architecture="غير معروف";var name="";var quant=""
            for(i in 0 until metadataCount.toInt()) {
                val key=str();val type=number(4).toInt()
                // Tokenizer vocabulary arrays may contain >100,000 strings. The general
                // identity keys are normally earlier; avoid parsing that huge section for UI.
                if (key.startsWith("tokenizer.ggml.") && architecture!="غير معروف") break
                val value=readValue(type)
                when(key){"general.architecture"->architecture=value
                    "general.name"->name=value
                    "general.file_type"->quant=value}
                if(architecture!="غير معروف" && name.isNotBlank() && quant.isNotBlank()) break
            }
            Info(true,version,architecture,name,quant)
        }}catch(e:Exception){Info(false,error="${e.javaClass.simpleName}: ${e.message?:"تعذر تحليل الترويسة"}")}
    }
}
