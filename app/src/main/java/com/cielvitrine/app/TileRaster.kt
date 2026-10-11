package com.cielvitrine.app

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/** Lossless native raster transport. No JPEG recompression or HDR-to-SDR conversion. */
internal object TileRaster {
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAGIC = 0x43565431
    fun encode(bitmap: Bitmap): ByteArray {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).use { out ->
            out.writeInt(MAGIC); writeBitmap(out, bitmap)
            val hdr = Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()
            out.writeBoolean(hdr)
            if (hdr) writeGainmap(out, bitmap.gainmap!!)
        }
        require(raw.size() <= MAX_BYTES)
        return ByteArrayOutputStream().also { packed ->
            DeflaterOutputStream(packed).use { it.write(raw.toByteArray()) }
        }.toByteArray().also { require(it.size <= MAX_BYTES) }
    }
    fun decode(packed: ByteArray): Bitmap {
        require(packed.size in 1..MAX_BYTES)
        val raw = ByteArrayOutputStream()
        val inflater=Inflater()
        try { inflater.setInput(packed);val buffer=ByteArray(16384)
            while(!inflater.finished()){val n=inflater.inflate(buffer);if(n==0){require(inflater.finished());break}
                require(n>0 && raw.size()+n<=MAX_BYTES);raw.write(buffer,0,n)}
            require(inflater.remaining==0)
        } finally {inflater.end()}
        return DataInputStream(ByteArrayInputStream(raw.toByteArray())).use { input ->
            require(input.readInt() == MAGIC)
            val bitmap = readBitmap(input)
            if (input.readBoolean()) {
                if(Build.VERSION.SDK_INT >= 34) bitmap.gainmap = readGainmap(input)
                else error("HDR raster requires Android 14")
            }
            require(input.read() == -1); bitmap
        }
    }
    private fun color(space: ColorSpace?): JSONObject {
        if (space == null) return JSONObject().put("none",true)
        ColorSpace.Named.entries.firstOrNull { ColorSpace.get(it) == space }?.let {
            return JSONObject().put("named",it.name)
        }
        require(space is ColorSpace.Rgb)
        val p = space.transferParameters ?: error("Unsupported custom color transfer")
        return JSONObject().put("primaries",JSONArray(space.primaries.toList()))
            .put("white",JSONArray(space.whitePoint.toList()))
            .put("transfer",JSONArray(listOf(p.a,p.b,p.c,p.d,p.e,p.f,p.g)))
    }
    private fun color(json: JSONObject): ColorSpace? {
        if(json.optBoolean("none"))return null
        if(json.has("named"))return ColorSpace.get(ColorSpace.Named.valueOf(json.getString("named")))
        val p=json.getJSONArray("transfer");require(p.length()==7)
        fun values(name:String,n:Int):FloatArray { val a=json.getJSONArray(name);require(a.length()==n)
            return FloatArray(n){a.getDouble(it).toFloat().also{v->require(v.isFinite())}} }
        val numbers=DoubleArray(7){p.getDouble(it).also{v->require(v.isFinite())}}
        return ColorSpace.Rgb("Tile RGB",values("primaries",6),values("white",2),
            ColorSpace.Rgb.TransferParameters(numbers[0],numbers[1],numbers[2],numbers[3],numbers[4],numbers[5],numbers[6]))
    }
    private fun bytesPerPixel(config: Bitmap.Config): Int = when(config) {
        Bitmap.Config.ARGB_8888 -> 4
        Bitmap.Config.RGBA_F16 -> 8
        Bitmap.Config.RGB_565 -> 2
        Bitmap.Config.ALPHA_8 -> 1
        else -> if(Build.VERSION.SDK_INT>=33 && config==Bitmap.Config.RGBA_1010102)4 else error("Unsupported tile pixel format")
    }
    private fun writeJson(out:DataOutputStream,json:JSONObject) {
        val bytes=json.toString().toByteArray();require(bytes.size <= 16384);out.writeInt(bytes.size);out.write(bytes)
    }
    private fun readJson(input:DataInputStream):JSONObject {
        val size=input.readInt();require(size in 2..16384)
        return JSONObject(ByteArray(size).also(input::readFully).toString(Charsets.UTF_8))
    }
    private fun writeBitmap(out:DataOutputStream,bitmap:Bitmap) {
        val config=bitmap.config ?: error("Unknown raster config");val bpp=bytesPerPixel(config)
        require(bitmap.width in 1..514 && bitmap.height in 1..514 && bitmap.byteCount <= MAX_BYTES)
        writeJson(out,JSONObject().put("width",bitmap.width).put("height",bitmap.height).put("config",config.name)
            .put("color",color(bitmap.colorSpace)).put("alpha",bitmap.hasAlpha()).put("premultiplied",bitmap.isPremultiplied).put("density",bitmap.density))
        val pixels=ByteBuffer.allocate(bitmap.byteCount);bitmap.copyPixelsToBuffer(pixels)
        val row=bitmap.width*bpp
        for(y in 0 until bitmap.height)out.write(pixels.array(),y*bitmap.rowBytes,row)
    }
    private fun readBitmap(input:DataInputStream):Bitmap {
        val json=readJson(input);val w=json.getInt("width");val h=json.getInt("height")
        require(w in 1..514 && h in 1..514)
        val config=Bitmap.Config.valueOf(json.getString("config"));val bpp=bytesPerPixel(config)
        val space=color(json.getJSONObject("color"))
        val bitmap=if(config==Bitmap.Config.ALPHA_8) Bitmap.createBitmap(w,h,config)
            else Bitmap.createBitmap(w,h,config,json.getBoolean("alpha"),space ?: ColorSpace.get(ColorSpace.Named.SRGB))
        bitmap.setHasAlpha(json.getBoolean("alpha"));bitmap.isPremultiplied=json.getBoolean("premultiplied");bitmap.density=json.getInt("density")
        require(bitmap.byteCount <= MAX_BYTES)
        val pixels=ByteArray(bitmap.byteCount);val row=w*bpp
        for(y in 0 until h)input.readFully(pixels,y*bitmap.rowBytes,row)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));return bitmap
    }
    @androidx.annotation.RequiresApi(34)
    private fun writeGainmap(out:DataOutputStream,g:Gainmap) {
        val j=JSONObject().put("min",JSONArray(g.ratioMin.toList())).put("max",JSONArray(g.ratioMax.toList()))
            .put("gamma",JSONArray(g.gamma.toList())).put("sdr",JSONArray(g.epsilonSdr.toList())).put("hdr",JSONArray(g.epsilonHdr.toList()))
            .put("full",g.displayRatioForFullHdr).put("transition",g.minDisplayRatioForHdrTransition)
        if(Build.VERSION.SDK_INT>=36)j.put("direction",g.gainmapDirection).put("alternative",color(g.alternativeImagePrimaries))
        writeJson(out,j);writeBitmap(out,g.gainmapContents)
    }
    @androidx.annotation.RequiresApi(34)
    private fun readGainmap(input:DataInputStream):Gainmap {
        val j=readJson(input);val g=Gainmap(readBitmap(input))
        fun values(name:String):FloatArray {val a=j.getJSONArray(name);require(a.length()==3)
            return FloatArray(3){a.getDouble(it).toFloat().also{v->require(v.isFinite())}}}
        values("min").let{g.setRatioMin(it[0],it[1],it[2])};values("max").let{g.setRatioMax(it[0],it[1],it[2])}
        values("gamma").let{g.setGamma(it[0],it[1],it[2])};values("sdr").let{g.setEpsilonSdr(it[0],it[1],it[2])}
        values("hdr").let{g.setEpsilonHdr(it[0],it[1],it[2])}
        g.displayRatioForFullHdr=j.getDouble("full").toFloat();g.minDisplayRatioForHdrTransition=j.getDouble("transition").toFloat()
        if(j.has("direction")){
            if(Build.VERSION.SDK_INT>=36){g.gainmapDirection=j.getInt("direction");g.alternativeImagePrimaries=color(j.getJSONObject("alternative"))}
            else error("Gainmap extensions require Android 16")
        }
        return g
    }
}
