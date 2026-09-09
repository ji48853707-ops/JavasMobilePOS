package com.javas.pos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    data class Product(var code: String, var name: String, var price: Int, var stock: Int)
    data class CartLine(val product: Product, var qty: Int)
    enum class ScanMode { REGISTER, CALCULATE }

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var currentMode: ScanMode = ScanMode.CALCULATE
    private var scannerRunning = false

    private val products = linkedMapOf<String, Product>()
    private val cart = linkedMapOf<String, CartLine>()
    private var salesTotal = 0

    private var lastAcceptedCode = ""
    private var lastAcceptedAt = 0L
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }

    private val scannerOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_ITF,
            Barcode.FORMAT_CODABAR,
            Barcode.FORMAT_QR_CODE
        ).build()
    private val barcodeScanner by lazy { BarcodeScanning.getClient(scannerOptions) }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startScanner(currentMode) else toast("카메라 권한이 필요합니다.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        loadData()
        buildShell()
        showCalculate()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScanner()
        barcodeScanner.close()
        cameraExecutor.shutdown()
        tone.release()
    }

    private fun buildShell() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(239,246,248)) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(30))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)

        val header = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val logo = ImageView(this).apply {
            setImageResource(com.javas.pos.R.drawable.javas_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(105), dp(105)))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12),0,0,0)
            addView(text("자바쓰피싱 모바일 POS", 24, true))
            addView(text("바코드 · QR · 계산 · 재고 · 매출", 15, false, Color.rgb(107,124,133)))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header, matchWrap(bottom = 12))

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bCalc = navButton("계산") { showCalculate() }
        val bReg = navButton("상품등록") { showRegister() }
        val bStock = navButton("재고") { showStock() }
        val bSales = navButton("매출") { showSales() }
        listOf(bCalc,bReg,bStock,bSales).forEach { nav.addView(it, LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginEnd=dp(6) }) }
        root.addView(nav, matchWrap(bottom = 12))

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, matchWrap())
    }

    private fun showCalculate() {
        stopScanner()
        content.removeAllViews()
        currentMode = ScanMode.CALCULATE

        val stats = card().apply {
            orientation = LinearLayout.HORIZONTAL
            addView(statBox("등록상품", products.size.toString()), LinearLayout.LayoutParams(0, dp(110),1f))
            addView(statBox("장바구니", cart.values.sumOf { it.qty }.toString()), LinearLayout.LayoutParams(0, dp(110),1f))
        }
        content.addView(stats, matchWrap(bottom=12))

        val scanCard = card().apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(16),dp(14),dp(16)) }
        scanCard.addView(centerText("빠른 바코드 · QR 스캔", 23, true))
        scanCard.addView(primaryButton("📷 카메라 스캔 시작") { ensureCamera(ScanMode.CALCULATE) }, matchWrap(top=12))
        val pv = PreviewView(this).apply {
            visibility = View.GONE
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(Color.BLACK)
        }
        previewView = pv
        scanCard.addView(pv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)).apply { topMargin=dp(10) })
        val manual = EditText(this).apply { hint="코드 직접 입력"; textSize=18f; setPadding(dp(14),dp(12),dp(14),dp(12)); background=rounded(Color.WHITE, Color.rgb(210,220,225)) }
        val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; addView(manual, LinearLayout.LayoutParams(0,dp(58),1f)); addView(secondaryButton("찾기") { val c=normalizeCode(manual.text.toString()); if(c.isNotBlank()) processCalculateCode(c) }, LinearLayout.LayoutParams(dp(110),dp(58)).apply{marginStart=dp(8)}) }
        scanCard.addView(row, matchWrap(top=10))
        content.addView(scanCard, matchWrap(bottom=12))

        val cartCard = card().apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)) }
        cartCard.addView(text("장바구니", 25, true))
        if (cart.isEmpty()) cartCard.addView(centerText("스캔한 상품이 없습니다.",17,false,Color.GRAY).apply{setPadding(0,dp(28),0,dp(28))})
        else cart.values.forEach { line ->
            val rowLine = LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; setPadding(0,dp(10),0,dp(10))
                addView(text("${line.product.name}   ${money(line.product.price * line.qty)}원",18,true))
                val actions=LinearLayout(this@MainActivity).apply { orientation=LinearLayout.HORIZONTAL }
                actions.addView(smallButton("−") { if(line.qty>1) line.qty-- else cart.remove(line.product.code); showCalculate() }, LinearLayout.LayoutParams(0,dp(48),1f))
                actions.addView(centerText("${line.qty}개",17,true), LinearLayout.LayoutParams(0,dp(48),1f))
                actions.addView(smallButton("+") { line.qty++; showCalculate() }, LinearLayout.LayoutParams(0,dp(48),1f))
                actions.addView(dangerButton("취소") { cart.remove(line.product.code); showCalculate() }, LinearLayout.LayoutParams(0,dp(48),1.2f))
                addView(actions)
            }
            cartCard.addView(rowLine)
        }
        val total = cart.values.sumOf { it.product.price * it.qty }
        cartCard.addView(text("합계  ${money(total)}원",28,true).apply{gravity=Gravity.END;setPadding(0,dp(15),0,dp(10))})
        cartCard.addView(secondaryButton("마지막 상품 취소") { cart.entries.lastOrNull()?.let { cart.remove(it.key); showCalculate() } }, matchWrap(top=6))
        cartCard.addView(dangerButton("전체 취소") { cart.clear(); showCalculate() }, matchWrap(top=6))
        cartCard.addView(primaryButton("판매 완료") { checkout() }, matchWrap(top=8))
        content.addView(cartCard, matchWrap())
    }

    private fun showRegister(prefillCode: String = "") {
        stopScanner()
        content.removeAllViews()
        currentMode = ScanMode.REGISTER

        val c = card().apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)) }
        c.addView(text("상품등록",27,true))
        c.addView(primaryButton("📷 바코드·QR 스캔해서 코드 입력") { ensureCamera(ScanMode.REGISTER) }, matchWrap(top=12))
        val pv = PreviewView(this).apply {
            visibility=View.GONE
            implementationMode=PreviewView.ImplementationMode.PERFORMANCE
            scaleType=PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(Color.BLACK)
        }
        previewView=pv
        c.addView(pv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(310)).apply{topMargin=dp(10)})

        val code = field("바코드/QR 번호").apply { setText(prefillCode) }
        val name = field("상품명")
        val price = field("판매가")
        val stock = field("재고")
        c.addView(code, matchWrap(top=10)); c.addView(name, matchWrap(top=8)); c.addView(price, matchWrap(top=8)); c.addView(stock, matchWrap(top=8))
        c.addView(primaryButton("상품 저장") {
            val normalized = normalizeCode(code.text.toString())
            val n=name.text.toString().trim(); val p=price.text.toString().replace(",","").toIntOrNull(); val s=stock.text.toString().toIntOrNull()
            if(normalized.isBlank() || n.isBlank() || p==null || s==null) { toast("코드, 상품명, 판매가, 재고를 입력해 주세요."); return@primaryButton }
            products[normalized]=Product(normalized,n,p,s); saveData(); toast("저장 완료: $n"); showRegister()
        }, matchWrap(top=12))
        content.addView(c, matchWrap(bottom=12))

        val list=card().apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)); addView(text("등록 상품",24,true)) }
        if(products.isEmpty()) list.addView(centerText("등록 상품 없음",17,false,Color.GRAY).apply{setPadding(0,dp(22),0,dp(22))})
        products.values.forEach { p -> list.addView(text("${p.name}   ${money(p.price)}원\n${p.code} · 재고 ${p.stock}",17,true).apply{setPadding(0,dp(12),0,dp(12))}) }
        content.addView(list,matchWrap())
    }

    private fun showStock() {
        stopScanner(); content.removeAllViews()
        val c=card().apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)); addView(text("재고",27,true)) }
        if(products.isEmpty()) c.addView(centerText("등록 상품 없음",17,false,Color.GRAY))
        products.values.forEach { c.addView(text("${it.name}   재고 ${it.stock}개",19,true).apply{setPadding(0,dp(12),0,dp(12))}) }
        content.addView(c,matchWrap())
    }

    private fun showSales() {
        stopScanner(); content.removeAllViews()
        val c=card().apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)); addView(text("매출",27,true)); addView(text("누적 매출  ${money(salesTotal)}원",28,true).apply{setPadding(0,dp(20),0,dp(20))}) }
        content.addView(c,matchWrap())
    }

    private fun ensureCamera(mode: ScanMode) {
        currentMode=mode
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startScanner(mode)
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startScanner(mode: ScanMode) {
        if(scannerRunning) return
        val pv=previewView ?: return
        pv.visibility=View.VISIBLE
        scannerRunning=true
        currentMode=mode
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider=future.get(); cameraProvider=provider; provider.unbindAll()
                val preview=Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                val analysis=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    val media=proxy.image
                    if(media==null){ proxy.close(); return@setAnalyzer }
                    val image=InputImage.fromMediaImage(media,proxy.imageInfo.rotationDegrees)
                    barcodeScanner.process(image)
                        .addOnSuccessListener { codes ->
                            val raw=codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                            if(raw!=null) onDetected(normalizeCode(raw))
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis)
            } catch(e:Exception) { scannerRunning=false; runOnUiThread { toast("카메라 시작 실패: ${e.message ?: "오류"}") } }
        },ContextCompat.getMainExecutor(this))
    }

    private fun stopScanner() {
        scannerRunning=false
        cameraProvider?.unbindAll()
        previewView?.visibility=View.GONE
    }

    private fun onDetected(code: String) {
        if(code.isBlank()) return
        val now=System.currentTimeMillis()
        if(code==lastAcceptedCode && now-lastAcceptedAt<900) return
        lastAcceptedCode=code; lastAcceptedAt=now
        runOnUiThread {
            beepAndVibrate()
            when(currentMode) {
                ScanMode.REGISTER -> {
                    stopScanner()
                    showRegister(code)
                    toast("인식 성공: $code")
                }
                ScanMode.CALCULATE -> processCalculateCode(code)
            }
        }
    }

    private fun processCalculateCode(code: String) {
        val p=products[code]
        if(p==null) {
            stopScanner()
            android.app.AlertDialog.Builder(this)
                .setTitle("미등록 상품")
                .setMessage("코드: $code\n이 코드로 바로 등록할까요?")
                .setPositiveButton("지금 등록") { _,_-> showRegister(code) }
                .setNegativeButton("계속 스캔") { _,_-> showCalculate(); ensureCamera(ScanMode.CALCULATE) }
                .show()
            return
        }
        cart[code]?.qty = (cart[code]?.qty ?: 0)+1
        if(cart[code]==null) cart[code]=CartLine(p,1)
        showCalculate()
        ensureCamera(ScanMode.CALCULATE)
    }

    private fun checkout() {
        if(cart.isEmpty()) { toast("장바구니가 비어 있습니다."); return }
        val total=cart.values.sumOf { it.product.price*it.qty }
        cart.values.forEach { line -> line.product.stock=(line.product.stock-line.qty).coerceAtLeast(0) }
        salesTotal+=total; cart.clear(); saveData(); toast("판매 완료 ${money(total)}원"); showCalculate()
    }

    private fun beepAndVibrate() {
        tone.startTone(ToneGenerator.TONE_PROP_BEEP,110)
        val vib=getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vib.vibrate(VibrationEffect.createOneShot(70,VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun normalizeCode(s:String)=s.trim().replace(" ","").replace("\n","").replace("\r","")
    private fun money(v:Int)=NumberFormat.getNumberInstance(Locale.KOREA).format(v)

    private fun loadData() {
        val pref=getSharedPreferences("javas_pos",MODE_PRIVATE)
        salesTotal=pref.getInt("sales",0)
        try {
            val arr=JSONArray(pref.getString("products","[]"))
            for(i in 0 until arr.length()) { val o=arr.getJSONObject(i); val p=Product(o.getString("code"),o.getString("name"),o.getInt("price"),o.getInt("stock")); products[p.code]=p }
        } catch(_:Exception){}
    }
    private fun saveData() {
        val arr=JSONArray(); products.values.forEach { arr.put(JSONObject().put("code",it.code).put("name",it.name).put("price",it.price).put("stock",it.stock)) }
        getSharedPreferences("javas_pos",MODE_PRIVATE).edit().putString("products",arr.toString()).putInt("sales",salesTotal).apply()
    }

    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun matchWrap(top:Int=0,bottom:Int=0)=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(top);bottomMargin=dp(bottom)}
    private fun rounded(fill:Int, stroke:Int=Color.TRANSPARENT)=android.graphics.drawable.GradientDrawable().apply { color=android.content.res.ColorStateList.valueOf(fill); cornerRadius=dp(18).toFloat(); setStroke(dp(1),stroke) }
    private fun card()=LinearLayout(this).apply { background=rounded(Color.WHITE); elevation=dp(2).toFloat() }
    private fun text(s:String,size:Int,bold:Boolean,color:Int=Color.rgb(7,53,76))=TextView(this).apply { text=s;textSize=size.toFloat();setTextColor(color);if(bold)setTypeface(typeface,android.graphics.Typeface.BOLD) }
    private fun centerText(s:String,size:Int,bold:Boolean,color:Int=Color.rgb(7,53,76))=text(s,size,bold,color).apply{gravity=Gravity.CENTER}
    private fun field(h:String)=EditText(this).apply{hint=h;textSize=18f;setPadding(dp(14),dp(12),dp(14),dp(12));background=rounded(Color.WHITE,Color.rgb(205,216,221))}
    private fun primaryButton(s:String,action:()->Unit)=Button(this).apply{text=s;textSize=18f;setTextColor(Color.WHITE);setTypeface(typeface,android.graphics.Typeface.BOLD);background=rounded(Color.rgb(11,75,107));setOnClickListener{action()}}
    private fun secondaryButton(s:String,action:()->Unit)=Button(this).apply{text=s;textSize=16f;setTextColor(Color.rgb(7,83,105));setTypeface(typeface,android.graphics.Typeface.BOLD);background=rounded(Color.rgb(226,244,248));setOnClickListener{action()}}
    private fun dangerButton(s:String,action:()->Unit)=Button(this).apply{text=s;textSize=16f;setTextColor(Color.rgb(180,35,24));setTypeface(typeface,android.graphics.Typeface.BOLD);background=rounded(Color.rgb(255,237,235));setOnClickListener{action()}}
    private fun smallButton(s:String,action:()->Unit)=secondaryButton(s,action)
    private fun navButton(s:String,action:()->Unit)=Button(this).apply{text=s;textSize=15f;setTextColor(Color.rgb(7,53,76));setTypeface(typeface,android.graphics.Typeface.BOLD);background=rounded(Color.WHITE);setOnClickListener{action()}}
    private fun statBox(label:String,value:String)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(15),dp(12),dp(15),dp(12));background=rounded(Color.rgb(247,250,251));addView(text(label,16,false,Color.rgb(107,124,133)));addView(text(value,30,true))}
}
