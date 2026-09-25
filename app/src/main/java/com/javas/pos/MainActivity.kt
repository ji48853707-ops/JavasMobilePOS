package com.javas.pos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
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
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URL
import java.security.MessageDigest
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    data class Product(
        var code:String,var name:String,var price:Int,var stock:Int,
        var costPrice:Int=0,var category:String="",var supplier:String="",
        var receivedDate:String="",var storageLocation:String="",
        var expiryDate:String="",var minStock:Int=5
    )
    data class CartLine(val product:Product,var qty:Int)
    data class StockMovement(val timestamp:Long,val code:String,val type:String,val qty:Int,val afterStock:Int,val note:String="")
    data class SaleRecord(val timestamp:Long,val total:Int,val itemCount:Int,val seller:String,val profit:Int)
    data class Supplier(val id:String,var name:String,var phone:String,var manager:String,var memo:String)
    data class PurchaseRecord(val timestamp:Long,val supplierName:String,val code:String,val productName:String,val qty:Int,val unitCost:Int,val total:Int)
    data class Customer(val id:String,var name:String,var phone:String,var memo:String)
    data class Ticket(val id:String,val createdAt:Long,var type:String,var customerName:String,var phone:String,var item:String,var dueDate:String,var status:String,var memo:String)
    data class UserAccount(var id:String,var name:String,var role:Role,var passwordHash:String,var active:Boolean=true)

    enum class Role { HQ, OWNER, MANAGER, STAFF }
    enum class ScanMode { REGISTER, CALCULATE }

    private lateinit var root:LinearLayout
    private lateinit var content:LinearLayout
    private lateinit var cameraExecutor:ExecutorService
    private lateinit var syncExecutor:ExecutorService
    private val mainHandler=Handler(Looper.getMainLooper())
    private lateinit var cloud:CloudSync
    private var cloudToken=""
    private var storeId="JAVAS001"
    private var applyingCloud=false
    private var currentScreen="login"
    private var cameraProvider:ProcessCameraProvider?=null
    private var previewView:PreviewView?=null
    private var currentMode=ScanMode.CALCULATE
    private var scannerRunning=false

    private val products=linkedMapOf<String,Product>()
    private val cart=linkedMapOf<String,CartLine>()
    private val stockHistory=mutableListOf<StockMovement>()
    private val salesHistory=mutableListOf<SaleRecord>()
    private val suppliers=linkedMapOf<String,Supplier>()
    private val purchaseHistory=mutableListOf<PurchaseRecord>()
    private val customers=linkedMapOf<String,Customer>()
    private val tickets=mutableListOf<Ticket>()
    private val users=linkedMapOf<String,UserAccount>()
    private var salesTotal=0
    private var currentUser:UserAccount?=null
    @Volatile private var localDataVersion=0L

    private var lastAcceptedCode=""
    private var lastAcceptedAt=0L
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_MUSIC,100) }

    private val scannerOptions=BarcodeScannerOptions.Builder().setBarcodeFormats(
        Barcode.FORMAT_EAN_13,Barcode.FORMAT_EAN_8,Barcode.FORMAT_UPC_A,
        Barcode.FORMAT_UPC_E,Barcode.FORMAT_CODE_128,Barcode.FORMAT_CODE_39,
        Barcode.FORMAT_ITF,Barcode.FORMAT_CODABAR,Barcode.FORMAT_QR_CODE
    ).build()
    private val barcodeScanner by lazy { BarcodeScanning.getClient(scannerOptions) }

    private val cloudPoll=object:Runnable{
        override fun run(){
            if(cloudToken.isNotBlank() && cart.isEmpty()) pullCloudAsync(false)
            mainHandler.postDelayed(this,5000)
        }
    }

    private val requestCamera=registerForActivityResult(ActivityResultContracts.RequestPermission()){ granted ->
        if(granted) startScanner(currentMode) else toast("카메라 권한이 필요합니다.")
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        cameraExecutor=Executors.newSingleThreadExecutor()
        syncExecutor=Executors.newSingleThreadExecutor()
        cloud=CloudSync(this,"https://javas-pos-cloud-zisfx8.v2.appdeploy.ai")
        storeId=getSharedPreferences("javas_pos",MODE_PRIVATE).getString("store_id","JAVAS001")?:"JAVAS001"
        loadData()
        val prefs=getSharedPreferences("javas_pos",MODE_PRIVATE)
        val hasExistingBusinessData=products.isNotEmpty()||salesHistory.isNotEmpty()||suppliers.isNotEmpty()||customers.isNotEmpty()||tickets.isNotEmpty()
        if(hasExistingBusinessData && prefs.getLong("data_updated_at",0L)==0L){
            prefs.edit().putLong("data_updated_at",System.currentTimeMillis()).apply()
        }
        seedUsers()
        showLogin()
    }

    override fun onDestroy(){
        super.onDestroy()
        stopScanner()
        barcodeScanner.close()
        cameraExecutor.shutdown()
        syncExecutor.shutdown()
        mainHandler.removeCallbacks(cloudPoll)
        if(::cloud.isInitialized) cloud.close()
        tone.release()
    }

    private fun showLogin(){
        currentScreen="login"
        stopScanner()
        mainHandler.removeCallbacks(cloudPoll)
        val scroll=ScrollView(this).apply{ setBackgroundColor(Color.WHITE); isFillViewport=true }
        val outer=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12),dp(10),dp(12),dp(10))
        }
        val c=card().apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(12),dp(18),dp(12))
            minimumHeight=(resources.displayMetrics.heightPixels-dp(70)).coerceAtLeast(0)
        }
        val logo=ImageView(this).apply{
            setImageResource(R.drawable.javas_logo)
            scaleType=ImageView.ScaleType.CENTER_INSIDE
        }
        c.addView(logo,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(72)))
        c.addView(centerText("JAVAS FISHING POS",21,true),matchWrap(top=2))
        c.addView(centerText("낚시매장 종합관리 · 매장 공유",12,false,Color.GRAY),matchWrap(top=1,bottom=8))

        val store=field("매장코드").apply{ setText(storeId) }
        val id=field("아이디 입력")
        val pw=field("비밀번호 입력").apply{ inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }

        c.addView(fieldLabel("매장코드")); c.addView(store,matchWrap(top=2))
        c.addView(fieldLabel("아이디"),matchWrap(top=6)); c.addView(id,matchWrap(top=2))
        c.addView(fieldLabel("비밀번호"),matchWrap(top=6)); c.addView(pw,matchWrap(top=2))
        c.addView(primaryButton("로그인 · 매장 동기화"){
            loginWithCloud(store.text.toString().trim().uppercase(Locale.KOREA),id.text.toString().trim(),pw.text.toString())
        },matchWrap(top=6))
        outer.addView(c,matchWrap())
        scroll.addView(outer)
        setContentView(scroll)
    }

    private fun loginWithCloud(requestStore:String,id:String,pw:String){
        if(requestStore.isBlank()||id.isBlank()||pw.isBlank()){toast("매장코드, 아이디, 비밀번호를 입력해 주세요.");return}

        // 본사 총관리자는 반드시 서버에 인증한 뒤 가맹점 관리로 들어간다.
        if(requestStore=="JAVAS001" && id=="hq"){
            toast("본사 서버 연결 중")
            syncExecutor.execute{
                try{
                    val result=cloud.login(requestStore,id,pw)
                    runOnUiThread{
                        storeId=result.storeId
                        cloudToken=result.token
                        currentUser=UserAccount(result.userId,result.name,Role.HQ,hashPassword(pw),true)
                        getSharedPreferences("javas_pos",MODE_PRIVATE).edit().putString("store_id",storeId).apply()
                        buildShell();showHqPortal()
                        toast("본사 총관리자 연결 완료")
                    }
                }catch(e:CloudSync.HttpError){runOnUiThread{toast(if(e.status==401)"아이디 또는 비밀번호를 확인해 주세요." else "본사 서버 연결을 확인해 주세요.")}}
                catch(_:Exception){runOnUiThread{toast("본사 서버 연결을 확인해 주세요.")}}
            }
            return
        }

        // 자바쓰 본점 가맹점 계정은 서버 상태와 상관없이 즉시 로그인되어 POS를 사용할 수 있다.
        val local=users[id]
        if(requestStore=="JAVAS001" && local!=null && local.passwordHash==hashPassword(pw) && local.active){
            storeId=requestStore
            currentUser=local
            getSharedPreferences("javas_pos",MODE_PRIVATE).edit().putString("store_id",storeId).apply()
            buildShell()
            if(local.role==Role.HQ) showHqPortal() else showDashboard()
            toast("로그인 완료")

            // 클라우드는 뒤에서 연결한다. 실패해도 판매/재고/상품관리는 계속 정상 사용한다.
            syncExecutor.execute{
                try{
                    val result=cloud.login(requestStore,id,pw)
                    val remote=cloud.getSnapshot(result.storeId,result.token)
                    val hasRemote=hasBusinessData(remote)
                    val hasLocal=products.isNotEmpty()||salesHistory.isNotEmpty()||suppliers.isNotEmpty()||customers.isNotEmpty()||tickets.isNotEmpty()
                    val localUpdated=getSharedPreferences("javas_pos",MODE_PRIVATE).getLong("data_updated_at",0L)
                    val remoteUpdated=remote.optLong("data_updated_at",0L)
                    val keepLocal=hasLocal && (!hasRemote || localUpdated>remoteUpdated)
                    if(keepLocal) cloud.putSnapshot(result.storeId,result.token,buildCloudSnapshot())
                    runOnUiThread{
                        cloudToken=result.token
                        if(hasRemote && !keepLocal) applyCloudSnapshot(remote)
                        mainHandler.removeCallbacks(cloudPoll)
                        mainHandler.postDelayed(cloudPoll,5000)
                    }
                }catch(_:Exception){
                    // 서버 접근이 막혀도 로컬 POS 사용을 방해하지 않는다.
                }
            }
            return
        }

        if(requestStore=="JAVAS001"){
            toast("아이디 또는 비밀번호를 확인해 주세요.")
            return
        }

        toast("매장 서버 연결 중")
        syncExecutor.execute{
            try{
                val result=cloud.login(requestStore,id,pw)
                val remote=cloud.getSnapshot(result.storeId,result.token)
                runOnUiThread{
                    storeId=result.storeId
                    cloudToken=result.token
                    getSharedPreferences("javas_pos",MODE_PRIVATE).edit().putString("store_id",storeId).apply()
                    currentUser=UserAccount(result.userId,result.name,try{Role.valueOf(result.role)}catch(_:Exception){Role.STAFF},hashPassword(pw),true)
                    // 새 가맹점 로그인 시 이전 매장 데이터가 섞이지 않도록 서버 자료로 완전히 교체한다.
                    applyCloudSnapshot(remote)
                    buildShell();showDashboard()
                    mainHandler.removeCallbacks(cloudPoll); mainHandler.postDelayed(cloudPoll,5000)
                    toast("매장 공유 연결 완료")
                }
            }catch(e:CloudSync.HttpError){
                runOnUiThread{toast(if(e.status==401)"아이디 또는 비밀번호를 확인해 주세요." else if(e.status==404)"등록되지 않은 매장코드입니다." else "매장 서버 연결을 확인해 주세요.")}
            }catch(_:Exception){
                runOnUiThread{toast("매장 서버 연결을 확인해 주세요.")}
            }
        }
    }

    private fun loginOffline(id:String,pw:String){
        val u=users[id]
        if(u==null || u.passwordHash!=hashPassword(pw)){ toast("아이디 또는 비밀번호를 확인해 주세요."); return }
        if(!u.active){ toast("사용이 중지된 계정입니다."); return }
        currentUser=u
        buildShell()
        showDashboard()
    }

    private fun logout(){
        cloudToken=""
        mainHandler.removeCallbacks(cloudPoll)
        currentUser=null
        cart.clear()
        showLogin()
    }

    private fun canManageProducts()=currentUser?.role in setOf(Role.HQ,Role.OWNER,Role.MANAGER)
    private fun canAdjustStock()=currentUser?.role in setOf(Role.HQ,Role.OWNER,Role.MANAGER)
    private fun canViewSales()=currentUser?.role in setOf(Role.HQ,Role.OWNER,Role.MANAGER)
    private fun canManageSuppliers()=currentUser?.role in setOf(Role.HQ,Role.OWNER,Role.MANAGER)
    private fun canManageCustomers()=currentUser?.role in setOf(Role.HQ,Role.OWNER,Role.MANAGER)
    private fun canManageStaff()=currentUser?.role in setOf(Role.HQ,Role.OWNER)
    private fun roleLabel(r:Role)=when(r){Role.HQ->"본사 총관리자";Role.OWNER->"가맹점 대표";Role.MANAGER->"점장";Role.STAFF->"일반 직원"}

    private fun buildShell(){
        val scroll=ScrollView(this).apply{ setBackgroundColor(Color.rgb(239,246,248)); isFillViewport=true }
        root=LinearLayout(this).apply{ orientation=LinearLayout.VERTICAL; setPadding(dp(8),dp(8),dp(8),dp(14)) }
        scroll.addView(root,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)

        val head=card().apply{ orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(9),dp(10),dp(9)) }
        val top=LinearLayout(this).apply{ orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        val logo=ImageView(this).apply{ setImageResource(R.drawable.javas_logo); scaleType=ImageView.ScaleType.CENTER_INSIDE }
        top.addView(logo,LinearLayout.LayoutParams(dp(64),dp(64)))
        top.addView(LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL; setPadding(dp(8),0,0,0)
            addView(text("JAVAS FISHING POS",18,true).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END})
            addView(text("낚시매장 종합관리",11,false,Color.GRAY))
        },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        head.addView(top)
        head.addView(text("$storeId · ${currentUser?.name} · ${roleLabel(currentUser?.role?:Role.STAFF)}",12,true,Color.DKGRAY).apply{
            maxLines=1;ellipsize=TextUtils.TruncateAt.END
        },matchWrap(top=5))
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        actions.addView(secondaryButton("서버 저장"){pushCloudAsync(true)},LinearLayout.LayoutParams(0,dp(38),1f).apply{marginEnd=dp(4)})
        actions.addView(secondaryButton("서버 불러오기"){pullCloudAsync(true)},LinearLayout.LayoutParams(0,dp(38),1f).apply{marginEnd=dp(4)})
        actions.addView(secondaryButton("로그아웃"){logout()},LinearLayout.LayoutParams(0,dp(38),1f))
        head.addView(actions,matchWrap(top=5))
        root.addView(head,matchWrap(bottom=7))

        val navItems=mutableListOf<Pair<String,()->Unit>>()
        if(currentUser?.role==Role.HQ) navItems.add("본사관리" to {showHqPortal()})
        navItems.add("대시보드" to {showDashboard()})
        navItems.add("판매 POS" to {showCalculate()})
        if(canManageProducts()) navItems.add("상품관리" to {showRegister()})
        navItems.add("재고" to {showStock()})
        if(canViewSales()) navItems.add("매출" to {showSales()})
        if(canManageStaff()) navItems.add("관리자" to {showAdmin()})
        val navCard=card().apply{orientation=LinearLayout.VERTICAL;setPadding(dp(5),dp(5),dp(5),dp(5))}
        navItems.chunked(3).forEachIndexed{ri,chunk->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            chunk.forEachIndexed{i,it->
                row.addView(navButton(it.first,it.second),LinearLayout.LayoutParams(0,dp(42),1f).apply{if(i>0)marginStart=dp(4)})
            }
            repeat(3-chunk.size){row.addView(Space(this),LinearLayout.LayoutParams(0,dp(42),1f).apply{marginStart=dp(4)})}
            navCard.addView(row,matchWrap(top=if(ri==0)0 else 4))
        }
        root.addView(navCard,matchWrap(bottom=7))
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(content,matchWrap())
    }

    private fun showDashboard(){
        currentScreen="dashboard"
        stopScanner(); content.removeAllViews()
        val today=todayString()
        val todays=salesHistory.filter{dateKey(it.timestamp)==today}
        val low=products.values.count{it.stock<=it.minStock}
        val long=products.values.count{isLongStock(it)}
        val expiry=products.values.count{isExpirySoon(it)}
        val asWait=tickets.count{it.type=="AS" && it.status!="완료"}

        val title=card().apply{
            orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(9),dp(10),dp(9))
            addView(text("JAVAS FISHING 낚시매장 종합관리",18,true))
            addView(text("판매 · 상품 · 재고 · 보관 · 매출 · 거래처 · 고객/AS · 직원",11,false,Color.GRAY),matchWrap(top=2))
        }
        content.addView(title,matchWrap(bottom=7))
        content.addView(dashRow("오늘 매출","${money(todays.sumOf{it.total})}원","판매건수","${todays.size}건"),matchWrap(bottom=4))
        content.addView(dashRow("재고부족","${low}개","장기재고","${long}개"),matchWrap(bottom=4))
        content.addView(dashRow("유통기한 임박","${expiry}개","AS 대기","${asWait}건"),matchWrap(bottom=7))

        val menu=card().apply{orientation=LinearLayout.VERTICAL;setPadding(dp(9),dp(9),dp(9),dp(9));addView(text("종합관리 메뉴",16,true))}
        val items=mutableListOf<Pair<String,()->Unit>>()
        if(currentUser?.role==Role.HQ) items.add("본사 가맹점 관리" to {showHqPortal()})
        items.add("판매 POS" to {showCalculate()})
        if(canManageProducts()) items.add("상품관리" to {showRegister()})
        items.add("재고/입출고" to {showStock()})
        items.add("보관/유통기한" to {showStorageExpiry()})
        if(canViewSales()) items.add("매출/정산" to {showSales()})
        if(canManageSuppliers()) items.add("매입/거래처" to {showSuppliers()})
        if(canManageCustomers()) items.add("고객/예약/AS" to {showCustomerService()})
        if(canManageStaff()) items.add("직원/권한" to {showAdmin()})
        items.add("설정" to {showSettings()})
        items.chunked(2).forEach{ chunk ->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            chunk.forEachIndexed{i,it-> row.addView(secondaryButton(it.first,it.second),LinearLayout.LayoutParams(0,dp(44),1f).apply{if(i==0)marginEnd=dp(4)})}
            if(chunk.size==1) row.addView(Space(this),LinearLayout.LayoutParams(0,dp(44),1f))
            menu.addView(row,matchWrap(top=4))
        }
        content.addView(menu,matchWrap())
    }

    private fun dashRow(a:String,av:String,b:String,bv:String)=LinearLayout(this).apply{
        orientation=LinearLayout.HORIZONTAL
        addView(statBox(a,av),LinearLayout.LayoutParams(0,dp(76),1f).apply{marginEnd=dp(4)})
        addView(statBox(b,bv),LinearLayout.LayoutParams(0,dp(76),1f))
    }


    private fun showCalculate(){
        currentScreen="calculate"
        stopScanner(); content.removeAllViews(); currentMode=ScanMode.CALCULATE

        val stats=card().apply{
            orientation=LinearLayout.HORIZONTAL
            addView(statBox("등록상품",products.size.toString()),LinearLayout.LayoutParams(0,dp(82),1f))
            addView(statBox("장바구니",cart.values.sumOf{it.qty}.toString()),LinearLayout.LayoutParams(0,dp(82),1f))
        }
        content.addView(stats,matchWrap(bottom=7))

        val scan=card().apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(16),dp(14),dp(16))}
        scan.addView(centerText("빠른 바코드 · QR 스캔",18,true))
        scan.addView(primaryButton("📷 카메라 스캔 시작"){ensureCamera(ScanMode.CALCULATE)},matchWrap(top=7))
        val pv=PreviewView(this).apply{
            visibility=View.GONE
            implementationMode=PreviewView.ImplementationMode.PERFORMANCE
            scaleType=PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(Color.BLACK)
        }
        previewView=pv
        scan.addView(pv,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(340)).apply{topMargin=dp(10)})
        val manual=field("코드 직접 입력")
        val mr=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            addView(manual,LinearLayout.LayoutParams(0,dp(40),1f))
            addView(secondaryButton("찾기"){
                val c=normalizeCode(manual.text.toString())
                if(c.isNotBlank()) processCalculateCode(c)
            },LinearLayout.LayoutParams(dp(82),dp(40)).apply{marginStart=dp(8)})
        }
        scan.addView(mr,matchWrap(top=6))
        content.addView(scan,matchWrap(bottom=7))

        val cc=card().apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(10),dp(10),dp(10));addView(text("장바구니",18,true))}
        if(cart.isEmpty()){
            cc.addView(centerText("스캔한 상품이 없습니다.",17,false,Color.GRAY).apply{setPadding(0,dp(28),0,dp(28))})
        }else{
            cart.values.toList().forEach{line->
                val item=LinearLayout(this).apply{
                    orientation=LinearLayout.VERTICAL;setPadding(0,dp(10),0,dp(10))
                    addView(text("${line.product.name}   ${money(line.product.price*line.qty)}원",18,true))
                    val a=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.HORIZONTAL}
                    a.addView(smallButton("−"){
                        if(line.qty>1) line.qty-- else cart.remove(line.product.code)
                        showCalculate()
                    },LinearLayout.LayoutParams(0,dp(40),1f))
                    a.addView(centerText("${line.qty}개",17,true),LinearLayout.LayoutParams(0,dp(40),1f))
                    a.addView(smallButton("+"){
                        if(line.qty<line.product.stock) line.qty++ else toast("현재 재고 수량까지 담겼습니다.")
                        showCalculate()
                    },LinearLayout.LayoutParams(0,dp(40),1f))
                    a.addView(dangerButton("취소"){cart.remove(line.product.code);showCalculate()},LinearLayout.LayoutParams(0,dp(40),1.2f))
                    addView(a)
                }
                cc.addView(item)
            }
        }
        val total=cart.values.sumOf{it.product.price*it.qty}
        cc.addView(text("합계  ${money(total)}원",28,true).apply{gravity=Gravity.END;setPadding(0,dp(15),0,dp(10))})
        cc.addView(secondaryButton("마지막 상품 취소"){cart.entries.lastOrNull()?.let{cart.remove(it.key);showCalculate()}},matchWrap(top=6))
        cc.addView(dangerButton("전체 취소"){cart.clear();showCalculate()},matchWrap(top=6))
        cc.addView(primaryButton("판매 완료"){checkout()},matchWrap(top=8))
        content.addView(cc,matchWrap())
    }

    private fun showRegister(prefillCode:String="",editCode:String?=null,searchQuery:String=""){
        currentScreen="products"
        if(!canManageProducts()){toast("상품관리 권한이 없습니다.");showDashboard();return}
        stopScanner();content.removeAllViews();currentMode=ScanMode.REGISTER
        val editing=editCode?.let{products[it]}
        val oldStock=editing?.stock

        val topSearch=card().apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(10),dp(10),dp(10))}
        topSearch.addView(text("상품 검색",20,true))
        val topSearchField=field("상품명 · 바코드 · 분류 · 거래처").apply{
            setText(searchQuery)
            setSingleLine(true)
        }
        val topSearchRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        topSearchRow.addView(topSearchField,LinearLayout.LayoutParams(0,dp(42),1f))
        topSearchRow.addView(
            primaryButton("검색"){showRegister(searchQuery=topSearchField.text.toString().trim())},
            LinearLayout.LayoutParams(dp(78),dp(42)).apply{marginStart=dp(6)}
        )
        topSearch.addView(topSearchRow,matchWrap(top=8))
        if(searchQuery.isNotBlank()) topSearch.addView(secondaryButton("검색 초기화"){showRegister()},matchWrap(top=6))
        content.addView(topSearch,matchWrap(bottom=7))

        if(searchQuery.isNotBlank()){
            val q=searchQuery.trim().lowercase(Locale.KOREA)
            val visibleProducts=products.values.filter{p->
                q.isBlank() ||
                p.name.lowercase(Locale.KOREA).contains(q) ||
                p.code.lowercase(Locale.KOREA).contains(q) ||
                p.category.lowercase(Locale.KOREA).contains(q) ||
                p.supplier.lowercase(Locale.KOREA).contains(q)
            }
            val results=card().apply{
                orientation=LinearLayout.VERTICAL
                setPadding(dp(10),dp(10),dp(10),dp(10))
                addView(text("검색 결과 ${visibleProducts.size}개",22,true))
            }
            visibleProducts.forEach{p->
                val item=LinearLayout(this).apply{
                    orientation=LinearLayout.VERTICAL
                    setPadding(dp(10),dp(12),dp(10),dp(12))
                    background=rounded(Color.rgb(247,250,251),Color.rgb(215,225,230))
                    isClickable=true
                    isFocusable=true
                    addView(text(p.name,18,true))
                    addView(text("${p.code} · ${money(p.price)}원 · 재고 ${p.stock}개",14,false,Color.GRAY),matchWrap(top=3))
                    val meta=listOf(p.category,p.supplier,p.storageLocation).filter{it.isNotBlank()}.joinToString(" · ")
                    if(meta.isNotBlank()) addView(text(meta,13,false,Color.GRAY),matchWrap(top=2))
                    addView(text("이 상품을 눌러 수정",12,true,Color.rgb(11,75,107)),matchWrap(top=6))
                    setOnClickListener{showRegister(editCode=p.code)}
                }
                results.addView(item,matchWrap(top=7))
            }
            if(visibleProducts.isEmpty()) results.addView(centerText("검색 결과가 없습니다.",17,false,Color.GRAY),matchWrap(top=10,bottom=10))
            content.addView(results,matchWrap())
            return
        }

        val c=card().apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(10),dp(10),dp(10))}
        c.addView(text("상품등록 · 수정 · 취소",20,true))
        c.addView(text(if(editing==null)"새 상품 정보를 입력해 주세요." else "등록된 상품 정보를 수정하는 중입니다.",14,false,Color.GRAY),matchWrap(top=4))
        c.addView(primaryButton("📷 바코드 · QR 스캔"){ensureCamera(ScanMode.REGISTER)},matchWrap(top=9))
        val pv=PreviewView(this).apply{
            visibility=View.GONE;implementationMode=PreviewView.ImplementationMode.PERFORMANCE
            scaleType=PreviewView.ScaleType.FILL_CENTER;setBackgroundColor(Color.BLACK)
        }
        previewView=pv
        c.addView(pv,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(310)).apply{topMargin=dp(10)})

        val name=field("예: 몽크로스 DEEP IMPACT X8 200m").apply{if(editing!=null)setText(editing.name)}
        val code=field("스캔 또는 직접 입력").apply{setText(editing?.code?:prefillCode)}
        val cost=field("예: 30000").apply{inputType=InputType.TYPE_CLASS_NUMBER;if(editing!=null && editing.costPrice>0)setText(editing.costPrice.toString())}
        val price=field("예: 43000").apply{inputType=InputType.TYPE_CLASS_NUMBER;if(editing!=null)setText(editing.price.toString())}
        val stock=field("예: 10").apply{inputType=InputType.TYPE_CLASS_NUMBER;if(editing!=null)setText(editing.stock.toString())}
        val category=field("예: 릴 / 낚싯대 / 떡밥 / 라인").apply{if(editing!=null)setText(editing.category)}
        val supplier=field("예: 몽크로스 / 경원F&B").apply{if(editing!=null)setText(editing.supplier)}
        val received=field("YYYY-MM-DD").apply{setText(editing?.receivedDate?.ifBlank{todayString()}?:todayString())}
        val location=field("예: A선반-2 / 창고1").apply{if(editing!=null)setText(editing.storageLocation)}
        val expiry=field("YYYY-MM-DD 또는 비워두기").apply{if(editing!=null)setText(editing.expiryDate)}
        val minStock=field("예: 5").apply{inputType=InputType.TYPE_CLASS_NUMBER;setText((editing?.minStock?:5).toString())}

        fun addForm(label:String,v:EditText){c.addView(fieldLabel(label),matchWrap(top=7));c.addView(v,matchWrap(top=5))}
        addForm("상품명",name);addForm("바코드 / QR 코드값",code);addForm("매입가",cost);addForm("판매가",price)
        addForm(if(editing==null)"초기 재고" else "현재 재고",stock);addForm("상품분류",category);addForm("거래처 / 공급업체",supplier)
        addForm("입고일",received);addForm("보관위치",location);addForm("유통기한 / 사용기한",expiry);addForm("최소재고 알림수량",minStock)

        c.addView(primaryButton(if(editing==null)"등록 저장" else "수정 저장"){
            val nc=normalizeCode(code.text.toString())
            val n=name.text.toString().trim()
            val cp=cost.text.toString().replace(",","").toIntOrNull()?:0
            val sp=price.text.toString().replace(",","").toIntOrNull()
            val q=stock.text.toString().toIntOrNull()
            val min=minStock.text.toString().toIntOrNull()
            val rd=received.text.toString().trim()
            val ex=expiry.text.toString().trim()
            if(nc.isBlank()||n.isBlank()||sp==null||q==null||min==null||cp<0||sp<0||q<0||min<0){toast("상품명, 코드, 가격, 재고를 정확히 입력해 주세요.");return@primaryButton}
            if(rd.isNotBlank()&&!isValidDate(rd)){toast("입고일은 YYYY-MM-DD 형식입니다.");return@primaryButton}
            if(ex.isNotBlank()&&!isValidDate(ex)){toast("유통기한은 YYYY-MM-DD 형식입니다.");return@primaryButton}
            val duplicate=findProductByCode(nc)
            if(duplicate!=null && duplicate.code!=editCode){toast("이미 등록된 바코드/QR 코드입니다.");return@primaryButton}
            if(editing!=null && editCode!=nc){products.remove(editCode);cart.remove(editCode)}
            products[nc]=Product(nc,n,sp,q,cp,category.text.toString().trim(),supplier.text.toString().trim(),rd,location.text.toString().trim(),ex,min)
            if(editing==null && q>0) addStockMovement(nc,"입고",q,q,"상품 최초등록")
            else if(editing!=null && oldStock!=null && oldStock!=q) addStockMovement(nc,"재고조정",q-oldStock,q,"상품수정")
            saveData();toast(if(editing==null)"등록 완료: $n" else "수정 완료: $n");showRegister()
        },matchWrap(top=9))
        c.addView(secondaryButton(if(editing==null)"등록 취소" else "수정 취소"){showRegister()},matchWrap(top=7))
        content.addView(c,matchWrap(bottom=7))

        val q=searchQuery.trim().lowercase(Locale.KOREA)
        val visibleProducts=products.values.filter{p->
            q.isBlank() ||
            p.name.lowercase(Locale.KOREA).contains(q) ||
            p.code.lowercase(Locale.KOREA).contains(q) ||
            p.category.lowercase(Locale.KOREA).contains(q) ||
            p.supplier.lowercase(Locale.KOREA).contains(q)
        }

        val list=card().apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(10),dp(10),dp(10),dp(10))
            addView(text(if(q.isBlank())"등록 상품 ${products.size}개" else "검색 결과 ${visibleProducts.size}개",24,true))
        }
        visibleProducts.forEach{p->
            val item=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL;setPadding(0,dp(12),0,dp(12))
                addView(text(p.name,18,true))
                addView(text("${p.code} · ${money(p.price)}원 · 재고 ${p.stock}개",14,false,Color.GRAY),matchWrap(top=3))
                val meta=listOf(p.category,p.supplier,p.storageLocation).filter{it.isNotBlank()}.joinToString(" · ")
                if(meta.isNotBlank())addView(text(meta,13,false,Color.GRAY),matchWrap(top=2))
                val a=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.HORIZONTAL}
                a.addView(secondaryButton("수정"){showRegister(editCode=p.code)},LinearLayout.LayoutParams(0,dp(42),1f).apply{marginEnd=dp(6)})
                a.addView(dangerButton("삭제"){confirmDeleteProduct(p.code)},LinearLayout.LayoutParams(0,dp(42),1f))
                addView(a,matchWrap(top=8))
            }
            list.addView(item)
        }
        if(products.isEmpty()) list.addView(centerText("등록 상품 없음",17,false,Color.GRAY),matchWrap(top=7))
        else if(visibleProducts.isEmpty()) list.addView(centerText("검색 결과가 없습니다.",17,false,Color.GRAY),matchWrap(top=7))
        content.addView(list,matchWrap())
    }

    private fun confirmDeleteProduct(code:String){
        val p=products[code]?:return
        android.app.AlertDialog.Builder(this).setTitle("등록상품 삭제").setMessage("${p.name}\n\n이 상품을 삭제할까요?")
            .setPositiveButton("삭제"){_,_->products.remove(code);cart.remove(code);saveData();showRegister()}
            .setNegativeButton("취소",null).show()
    }

    private fun showStock(){
        currentScreen="stock"
        stopScanner();content.removeAllViews()
        val c=card().apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(10),dp(10),dp(10))
            addView(text("재고 · 입출고 관리",20,true))
            addView(text(if(canAdjustStock())"입고 · 조정 · 파손 · 분실 · 반품 이력을 남깁니다." else "현재 재고를 확인합니다.",14,false,Color.GRAY),matchWrap(top=4,bottom=8))
        }
        products.values.toList().forEach{p->
            val row=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL;setPadding(0,dp(10),0,dp(10))
                addView(text(p.name,18,true))
                val low=p.stock<=p.minStock
                addView(text("현재 재고 ${p.stock}개${if(low)" · 재고 부족" else ""}",15,low,if(low)Color.rgb(180,35,24) else Color.GRAY),matchWrap(top=3))
                if(p.storageLocation.isNotBlank())addView(text("보관위치 ${p.storageLocation}",13,false,Color.GRAY),matchWrap(top=2))
                if(canAdjustStock()){
                    val a=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.HORIZONTAL}
                    a.addView(secondaryButton("입고"){showStockAdjustDialog(p.code,"입고",true)},LinearLayout.LayoutParams(0,dp(40),1f).apply{marginEnd=dp(5)})
                    a.addView(secondaryButton("조정"){showStockAdjustDialog(p.code,"재고조정",null)},LinearLayout.LayoutParams(0,dp(40),1f).apply{marginEnd=dp(5)})
                    a.addView(dangerButton("파손/분실"){showLossDialog(p.code)},LinearLayout.LayoutParams(0,dp(40),1f))
                    addView(a,matchWrap(top=7))
                }
            }
            c.addView(row)
        }
        if(stockHistory.isNotEmpty()){
            c.addView(text("최근 입출고 이력",21,true),matchWrap(top=9))
            stockHistory.asReversed().take(30).forEach{m->
                val sign=if(m.qty>0)"+" else ""
                c.addView(text("${formatDateTime(m.timestamp)} · ${products[m.code]?.name?:m.code}",15,true),matchWrap(top=8))
                c.addView(text("${m.type} $sign${m.qty}개 → 재고 ${m.afterStock}개${if(m.note.isNotBlank())" · ${m.note}" else ""}",13,false,Color.GRAY),matchWrap(top=2))
            }
        }
        content.addView(c,matchWrap())
    }

    private fun showStockAdjustDialog(code:String,type:String,positive:Boolean?){
        val p=products[code]?:return
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),0)}
        val qty=field("수량").apply{inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED}
        val note=field("메모(선택)")
        box.addView(fieldLabel("${p.name} · 현재 ${p.stock}개"));box.addView(qty,matchWrap(top=8));box.addView(note,matchWrap(top=8))
        android.app.AlertDialog.Builder(this).setTitle(type).setView(box).setNegativeButton("취소",null).setPositiveButton("저장"){_,_->
            var d=qty.text.toString().toIntOrNull()?:0
            if(positive==true)d=abs(d)
            if(d==0){toast("수량을 입력해 주세요.");return@setPositiveButton}
            if(positive==null){val ns=(p.stock+d).coerceAtLeast(0);d=ns-p.stock;p.stock=ns}else p.stock+=d
            addStockMovement(code,type,d,p.stock,note.text.toString().trim());saveData();showStock()
        }.show()
    }

    private fun showLossDialog(code:String){
        val types=arrayOf("파손","분실","반품")
        android.app.AlertDialog.Builder(this).setTitle("재고 감소 사유").setItems(types){_,i->showDecreaseDialog(code,types[i])}.show()
    }

    private fun showDecreaseDialog(code:String,type:String){
        val p=products[code]?:return
        val qty=field("수량").apply{inputType=InputType.TYPE_CLASS_NUMBER}
        android.app.AlertDialog.Builder(this).setTitle(type).setView(qty).setNegativeButton("취소",null).setPositiveButton("저장"){_,_->
            val q=qty.text.toString().toIntOrNull()?:0
            if(q<=0){toast("수량을 입력해 주세요.");return@setPositiveButton}
            val d=-minOf(q,p.stock);p.stock+=d;addStockMovement(code,type,d,p.stock);saveData();showStock()
        }.show()
    }

    private fun showStorageExpiry(){
        currentScreen="storage"
        stopScanner();content.removeAllViews()
        val c=card().apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(10),dp(10),dp(10))
            addView(text("보관 · 유통기한 관리",20,true))
            addView(text("입고 후 180일 이상은 장기재고, 유통기한 30일 이내는 임박으로 표시합니다.",14,false,Color.GRAY),matchWrap(top=4,bottom=10))
        }
        products.values.sortedBy{it.name}.forEach{p->
            val tags=mutableListOf<String>()
            if(isLongStock(p))tags.add("장기재고")
            if(isExpirySoon(p))tags.add("유통기한 임박")
            if(p.stock<=p.minStock)tags.add("재고부족")
            c.addView(text(p.name,18,true),matchWrap(top=9))
            c.addView(text("입고 ${p.receivedDate.ifBlank{"-"}} · 유통 ${p.expiryDate.ifBlank{"-"}} · 위치 ${p.storageLocation.ifBlank{"-"}}",13,false,Color.GRAY),matchWrap(top=2))
            if(tags.isNotEmpty())c.addView(text(tags.joinToString(" · "),13,true,Color.rgb(180,35,24)),matchWrap(top=2))
        }
        content.addView(c,matchWrap())
    }


    private fun showSales(){
        currentScreen="sales"
        if(!canViewSales()){toast("매출조회 권한이 없습니다.");showDashboard();return}
        stopScanner();content.removeAllViews()
        val today=todayString()
        val yesterday=dateKey(System.currentTimeMillis()-86400000L)
        val month=today.substring(0,7)
        val t=salesHistory.filter{dateKey(it.timestamp)==today}.sumOf{it.total}
        val y=salesHistory.filter{dateKey(it.timestamp)==yesterday}.sumOf{it.total}
        val m=salesHistory.filter{dateKey(it.timestamp).startsWith(month)}
        val c=card().apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(10),dp(10),dp(10));addView(text("매출 · 정산",20,true))}
        c.addView(dashRow("오늘","${money(t)}원","어제","${money(y)}원"),matchWrap(top=6))
        c.addView(dashRow("이번 달","${money(m.sumOf{it.total})}원","예상 이익","${money(m.sumOf{it.profit})}원"),matchWrap(top=6,bottom=12))
        c.addView(text("누적 매출 ${money(salesTotal)}원",20,true),matchWrap(top=4,bottom=8))
        val grouped=salesHistory.groupBy{dateKey(it.timestamp)}.toSortedMap(compareByDescending{it})
        grouped.entries.take(30).forEach{e->
            c.addView(text("${e.key}   ${money(e.value.sumOf{it.total})}원",17,true),matchWrap(top=9))
            c.addView(text("판매 ${e.value.size}건 · 상품 ${e.value.sumOf{it.itemCount}}개 · 예상이익 ${money(e.value.sumOf{it.profit})}원",13,false,Color.GRAY),matchWrap(top=2))
        }
        if(grouped.isEmpty())c.addView(text("아직 저장된 판매 기록이 없습니다.",15,false,Color.GRAY),matchWrap(top=8))
        content.addView(c,matchWrap())
    }

    private fun showSuppliers(){
        currentScreen="suppliers"
        if(!canManageSuppliers()){toast("거래처 관리 권한이 없습니다.");showDashboard();return}
        stopScanner();content.removeAllViews()
        val c=card().apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(10),dp(10),dp(10))
            addView(text("매입 · 거래처 관리",20,true))
            addView(text("매입/입고를 등록하면 상품 재고가 자동으로 증가합니다.",14,false,Color.GRAY),matchWrap(top=4))
            addView(primaryButton("+ 거래처 등록"){showAddSupplierDialog()},matchWrap(top=7))
            addView(secondaryButton("+ 매입 · 입고 등록"){showPurchaseDialog()},matchWrap(top=7))
        }
        c.addView(text("등록 거래처",21,true),matchWrap(top=9))
        suppliers.values.forEach{s->
            c.addView(text(s.name,18,true),matchWrap(top=9))
            val meta=listOf(s.manager.takeIf{it.isNotBlank()}?.let{"담당 $it"},s.phone.takeIf{it.isNotBlank()}?.let{"연락처 $it"}).filterNotNull().joinToString(" · ")
            if(meta.isNotBlank())c.addView(text(meta,13,false,Color.GRAY),matchWrap(top=2))
            c.addView(dangerButton("거래처 삭제"){confirmDeleteSupplier(s.id)},matchWrap(top=6))
        }
        if(suppliers.isEmpty())c.addView(text("등록된 거래처가 없습니다.",15,false,Color.GRAY),matchWrap(top=7))
        if(purchaseHistory.isNotEmpty()){
            c.addView(text("최근 매입 내역",21,true),matchWrap(top=9))
            purchaseHistory.asReversed().take(30).forEach{r->
                c.addView(text("${formatDateTime(r.timestamp)} · ${r.productName}",15,true),matchWrap(top=8))
                c.addView(text("${r.supplierName} · ${r.qty}개 × ${money(r.unitCost)}원 = ${money(r.total)}원",13,false,Color.GRAY),matchWrap(top=2))
            }
        }
        content.addView(c,matchWrap())
    }

    private fun showAddSupplierDialog(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),0)}
        val name=field("거래처명");val manager=field("담당자명");val phone=field("연락처");val memo=field("메모")
        box.addView(fieldLabel("거래처명"));box.addView(name,matchWrap(top=4,bottom=8))
        box.addView(fieldLabel("담당자"));box.addView(manager,matchWrap(top=4,bottom=8))
        box.addView(fieldLabel("연락처"));box.addView(phone,matchWrap(top=4,bottom=8))
        box.addView(fieldLabel("메모"));box.addView(memo,matchWrap(top=4))
        val d=android.app.AlertDialog.Builder(this).setTitle("거래처 등록").setView(box).setNegativeButton("취소",null).setPositiveButton("저장",null).create()
        d.setOnShowListener{
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val n=name.text.toString().trim()
                if(n.isBlank()){toast("거래처명을 입력해 주세요.");return@setOnClickListener}
                val id=System.currentTimeMillis().toString()
                suppliers[id]=Supplier(id,n,phone.text.toString().trim(),manager.text.toString().trim(),memo.text.toString().trim())
                saveData();d.dismiss();showSuppliers()
            }
        }
        d.show()
    }

    private fun confirmDeleteSupplier(id:String){
        val s=suppliers[id]?:return
        android.app.AlertDialog.Builder(this).setTitle("거래처 삭제").setMessage("${s.name} 거래처를 삭제할까요?")
            .setPositiveButton("삭제"){_,_->suppliers.remove(id);saveData();showSuppliers()}
            .setNegativeButton("취소",null).show()
    }

    private fun showPurchaseDialog(){
        if(products.isEmpty()){toast("먼저 상품을 등록해 주세요.");return}
        if(suppliers.isEmpty()){toast("먼저 거래처를 등록해 주세요.");return}
        val ps=products.values.toList();val ss=suppliers.values.toList()
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),0)}
        val sSpin=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,ss.map{it.name})}
        val pSpin=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,ps.map{it.name})}
        val qty=field("입고 수량").apply{inputType=InputType.TYPE_CLASS_NUMBER}
        val unit=field("개당 매입가").apply{inputType=InputType.TYPE_CLASS_NUMBER}
        box.addView(fieldLabel("거래처"));box.addView(sSpin,matchWrap(top=4,bottom=8))
        box.addView(fieldLabel("상품"));box.addView(pSpin,matchWrap(top=4,bottom=8))
        box.addView(fieldLabel("입고 수량"));box.addView(qty,matchWrap(top=4,bottom=8))
        box.addView(fieldLabel("개당 매입가"));box.addView(unit,matchWrap(top=4))
        val d=android.app.AlertDialog.Builder(this).setTitle("매입 · 입고 등록").setView(box).setNegativeButton("취소",null).setPositiveButton("입고 저장",null).create()
        d.setOnShowListener{
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val q=qty.text.toString().toIntOrNull();val u=unit.text.toString().toIntOrNull()
                if(q==null||u==null||q<=0||u<0){toast("입고 수량과 매입가를 확인해 주세요.");return@setOnClickListener}
                val s=ss[sSpin.selectedItemPosition];val p=ps[pSpin.selectedItemPosition]
                p.stock+=q;p.costPrice=u;p.supplier=s.name;p.receivedDate=todayString()
                addStockMovement(p.code,"매입입고",q,p.stock,s.name)
                purchaseHistory.add(PurchaseRecord(System.currentTimeMillis(),s.name,p.code,p.name,q,u,q*u))
                saveData();d.dismiss();showSuppliers()
            }
        }
        d.show()
    }

    private fun showCustomerService(){
        currentScreen="customers"
        if(!canManageCustomers()){toast("고객/AS 관리 권한이 없습니다.");showDashboard();return}
        stopScanner();content.removeAllViews()
        val c=card().apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(10),dp(10),dp(10))
            addView(text("고객 · 예약 · AS",20,true))
            addView(primaryButton("+ 고객 등록"){showAddCustomerDialog()},matchWrap(top=7))
            addView(secondaryButton("+ 예약 / AS 접수"){showAddTicketDialog()},matchWrap(top=7))
        }
        c.addView(text("처리 대기",21,true),matchWrap(top=9))
        val open=tickets.filter{it.status!="완료"}.sortedByDescending{it.createdAt}
        open.forEach{t->
            c.addView(text("${t.type} · ${t.customerName}",18,true),matchWrap(top=9))
            c.addView(text("${t.item} · ${t.status} · 예정 ${t.dueDate.ifBlank{"-"}}",13,false,Color.GRAY),matchWrap(top=2))
            val a=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            a.addView(secondaryButton(if(t.status=="접수")"진행중" else "완료"){
                t.status=if(t.status=="접수")"진행중" else "완료";saveData();showCustomerService()
            },LinearLayout.LayoutParams(0,dp(40),1f).apply{marginEnd=dp(5)})
            a.addView(dangerButton("삭제"){confirmDeleteTicket(t.id)},LinearLayout.LayoutParams(0,dp(40),1f))
            c.addView(a,matchWrap(top=6))
        }
        if(open.isEmpty())c.addView(text("대기 중인 예약/AS가 없습니다.",15,false,Color.GRAY),matchWrap(top=7))
        c.addView(text("등록 고객 ${customers.size}명",21,true),matchWrap(top=18))
        customers.values.take(30).forEach{cu->
            c.addView(text("${cu.name} · ${cu.phone}",15,true),matchWrap(top=7))
            if(cu.memo.isNotBlank())c.addView(text(cu.memo,13,false,Color.GRAY),matchWrap(top=2))
        }
        content.addView(c,matchWrap())
    }

    private fun showAddCustomerDialog(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),0)}
        val name=field("고객 이름");val phone=field("전화번호");val memo=field("메모")
        box.addView(fieldLabel("고객 이름"));box.addView(name,matchWrap(top=4,bottom=8))
        box.addView(fieldLabel("전화번호"));box.addView(phone,matchWrap(top=4,bottom=8))
        box.addView(fieldLabel("메모"));box.addView(memo,matchWrap(top=4))
        val d=android.app.AlertDialog.Builder(this).setTitle("고객 등록").setView(box).setNegativeButton("취소",null).setPositiveButton("저장",null).create()
        d.setOnShowListener{
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val n=name.text.toString().trim()
                if(n.isBlank()){toast("고객 이름을 입력해 주세요.");return@setOnClickListener}
                val id=System.currentTimeMillis().toString()
                customers[id]=Customer(id,n,phone.text.toString().trim(),memo.text.toString().trim())
                saveData();d.dismiss();showCustomerService()
            }
        }
        d.show()
    }

    private fun showAddTicketDialog(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),0)}
        val type=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("예약","AS"))}
        val name=field("고객 이름");val phone=field("전화번호");val item=field("상품 / 접수 내용");val due=field("YYYY-MM-DD");val memo=field("메모")
        box.addView(fieldLabel("구분"));box.addView(type,matchWrap(top=4,bottom=8))
        box.addView(fieldLabel("고객 이름"));box.addView(name,matchWrap(top=4,bottom=8))
        box.addView(fieldLabel("전화번호"));box.addView(phone,matchWrap(top=4,bottom=8))
        box.addView(fieldLabel("상품 / 접수 내용"));box.addView(item,matchWrap(top=4,bottom=8))
        box.addView(fieldLabel("예정일"));box.addView(due,matchWrap(top=4,bottom=8))
        box.addView(fieldLabel("메모"));box.addView(memo,matchWrap(top=4))
        val d=android.app.AlertDialog.Builder(this).setTitle("예약 / AS 접수").setView(box).setNegativeButton("취소",null).setPositiveButton("접수 저장",null).create()
        d.setOnShowListener{
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val n=name.text.toString().trim();val it=item.text.toString().trim();val dd=due.text.toString().trim()
                if(n.isBlank()||it.isBlank()){toast("고객 이름과 접수 내용을 입력해 주세요.");return@setOnClickListener}
                if(dd.isNotBlank()&&!isValidDate(dd)){toast("예정일은 YYYY-MM-DD 형식입니다.");return@setOnClickListener}
                tickets.add(Ticket(System.currentTimeMillis().toString(),System.currentTimeMillis(),type.selectedItem.toString(),n,phone.text.toString().trim(),it,dd,"접수",memo.text.toString().trim()))
                saveData();d.dismiss();showCustomerService()
            }
        }
        d.show()
    }

    private fun confirmDeleteTicket(id:String){
        android.app.AlertDialog.Builder(this).setTitle("접수 삭제").setMessage("이 예약/AS 접수를 삭제할까요?")
            .setPositiveButton("삭제"){_,_->tickets.removeAll{it.id==id};saveData();showCustomerService()}
            .setNegativeButton("취소",null).show()
    }

    private fun showHqPortal(){
        if(currentUser?.role!=Role.HQ){toast("본사 총관리자 전용입니다.");return}
        currentScreen="hq_portal"
        stopScanner()
        mainHandler.removeCallbacks(cloudPoll)
        val page=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.WHITE)}
        val bar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8));setBackgroundColor(Color.rgb(239,246,248))}
        bar.addView(secondaryButton("← POS"){buildShell();showDashboard()},LinearLayout.LayoutParams(dp(76),dp(40)))
        bar.addView(text("본사 총관리자 · 가맹점 관리",16,true),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply{marginStart=dp(8)})
        bar.addView(secondaryButton("로그아웃"){logout()},LinearLayout.LayoutParams(dp(76),dp(40)))
        page.addView(bar,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
        val web=WebView(this).apply{
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.useWideViewPort=true
            settings.loadWithOverviewMode=false
            webViewClient=WebViewClient()
            loadUrl("https://javas-pos-cloud-zisfx8.v2.appdeploy.ai/#nativeToken="+Uri.encode(cloudToken))
        }
        page.addView(web,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        setContentView(page)
    }

    private fun showAdmin(){
        currentScreen="admin"
        if(!canManageStaff()){toast("직원관리 권한이 없습니다.");showDashboard();return}
        stopScanner();content.removeAllViews()
        val c=card().apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(11),dp(10),dp(11),dp(10))
            addView(text("직원 · 권한 관리",20,true))
            addView(text("대표: 전체관리 + 직원관리 / 점장: 판매·상품·재고·매출·거래처·고객/AS / 일반: 판매·재고조회",11,false,Color.DKGRAY),matchWrap(top=4))
            addView(primaryButton("+ 직원 추가"){showAddEmployeeDialog()},matchWrap(top=7))
        }
        users.values.filter{it.role!=Role.HQ&&it.role!=Role.OWNER}.forEach{u->
            c.addView(text("${u.name} · ${roleLabel(u.role)}",15,true),matchWrap(top=8))
            c.addView(text("ID ${u.id} · ${if(u.active)"사용중" else "사용중지"}",11,false,if(u.active)Color.rgb(21,115,71) else Color.rgb(180,35,24)),matchWrap(top=1))
            val a=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            a.addView(secondaryButton("권한수정"){showRoleDialog(u.id)},LinearLayout.LayoutParams(0,dp(38),1f).apply{marginEnd=dp(4)})
            a.addView(secondaryButton(if(u.active)"중지" else "재개"){u.active=!u.active;saveData();showAdmin()},LinearLayout.LayoutParams(0,dp(38),1f).apply{marginEnd=dp(4)})
            a.addView(dangerButton("삭제"){confirmDeleteUser(u.id)},LinearLayout.LayoutParams(0,dp(38),1f))
            c.addView(a,matchWrap(top=4))
        }
        content.addView(c,matchWrap())
    }

    private fun showAddEmployeeDialog(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),0)}
        val name=field("직원 이름");val id=field("로그인 ID");val pw=field("임시 비밀번호").apply{inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD}
        val role=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("점장","일반 직원"))}
        box.addView(fieldLabel("직원 이름"));box.addView(name,matchWrap(top=4,bottom=8))
        box.addView(fieldLabel("로그인 ID"));box.addView(id,matchWrap(top=4,bottom=8))
        box.addView(fieldLabel("임시 비밀번호"));box.addView(pw,matchWrap(top=4,bottom=8))
        box.addView(fieldLabel("직급"));box.addView(role,matchWrap(top=4))
        val d=android.app.AlertDialog.Builder(this).setTitle("직원 ID 추가").setView(box).setNegativeButton("취소",null).setPositiveButton("직원 등록",null).create()
        d.setOnShowListener{
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val n=name.text.toString().trim();val i=id.text.toString().trim();val p=pw.text.toString()
                if(n.isBlank()||i.isBlank()||p.length<4){toast("이름, ID, 비밀번호 4자리 이상을 입력해 주세요.");return@setOnClickListener}
                if(users.containsKey(i)){toast("이미 사용 중인 ID입니다.");return@setOnClickListener}
                users[i]=UserAccount(i,n,if(role.selectedItemPosition==0)Role.MANAGER else Role.STAFF,hashPassword(p),true)
                saveData();d.dismiss();showAdmin()
            }
        }
        d.show()
    }

    private fun showRoleDialog(id:String){
        val u=users[id]?:return
        val labels=arrayOf("점장","일반 직원")
        android.app.AlertDialog.Builder(this).setTitle("${u.name} 권한 수정")
            .setSingleChoiceItems(labels,if(u.role==Role.MANAGER)0 else 1){d,w->u.role=if(w==0)Role.MANAGER else Role.STAFF;saveData();d.dismiss();showAdmin()}
            .setNegativeButton("취소",null).show()
    }

    private fun confirmDeleteUser(id:String){
        val u=users[id]?:return
        android.app.AlertDialog.Builder(this).setTitle("직원 계정 삭제").setMessage("${u.name} 계정을 삭제할까요?")
            .setPositiveButton("삭제"){_,_->users.remove(id);saveData();showAdmin()}
            .setNegativeButton("취소",null).show()
    }

    private fun showSettings(){
        currentScreen="settings"
        stopScanner();content.removeAllViews()
        val c=card().apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(10),dp(10),dp(10))
            addView(text("설정",20,true))
            addView(text("매장코드 $storeId\n매장 자바쓰피싱 본점\n사용자 ${currentUser?.name}\n권한 ${roleLabel(currentUser?.role?:Role.STAFF)}\n클라우드 ${if(cloudToken.isNotBlank())"연결됨" else "오프라인"}\n버전 종합관리 · 본사관리 연결",15,false,Color.DKGRAY),matchWrap(top=6))
            addView(secondaryButton("로그아웃"){logout()},matchWrap(top=9))
        }
        content.addView(c,matchWrap())
    }


    private fun ensureCamera(mode:ScanMode){
        currentMode=mode
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startScanner(mode)
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startScanner(mode:ScanMode){
        if(scannerRunning)return
        val pv=previewView?:return
        pv.visibility=View.VISIBLE;scannerRunning=true;currentMode=mode
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            try{
                val provider=future.get();cameraProvider=provider;provider.unbindAll()
                val preview=Preview.Builder().build().also{it.setSurfaceProvider(pv.surfaceProvider)}
                val analysis=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(cameraExecutor){proxy->
                    val media=proxy.image
                    if(media==null){proxy.close();return@setAnalyzer}
                    val image=InputImage.fromMediaImage(media,proxy.imageInfo.rotationDegrees)
                    barcodeScanner.process(image)
                        .addOnSuccessListener{codes->
                            val raw=codes.firstOrNull{!it.rawValue.isNullOrBlank()}?.rawValue
                            if(raw!=null)onDetected(normalizeCode(raw))
                        }
                        .addOnCompleteListener{proxy.close()}
                }
                provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis)
            }catch(e:Exception){
                scannerRunning=false
                runOnUiThread{toast("카메라 시작 실패: ${e.message?:"오류"}")}
            }
        },ContextCompat.getMainExecutor(this))
    }

    private fun stopScanner(){
        scannerRunning=false
        cameraProvider?.unbindAll()
        previewView?.visibility=View.GONE
    }

    private fun onDetected(code:String){
        if(code.isBlank())return
        val now=System.currentTimeMillis()
        if(code==lastAcceptedCode&&now-lastAcceptedAt<900)return
        lastAcceptedCode=code;lastAcceptedAt=now
        runOnUiThread{
            beepAndVibrate()
            when(currentMode){
                ScanMode.REGISTER->{
                    stopScanner()
                    val existing=findProductByCode(code)
                    if(existing!=null){
                        showRegister(editCode=existing.code)
                        toast("등록된 상품을 불러왔습니다.")
                    }else{
                        showRegister(code)
                        toast("인식 성공: $code")
                    }
                }
                ScanMode.CALCULATE->processCalculateCode(code)
            }
        }
    }

    private fun processCalculateCode(code:String){
        val p=findProductByCode(code)
        if(p==null){
            stopScanner()
            val b=android.app.AlertDialog.Builder(this).setTitle("미등록 상품").setMessage("코드: $code")
            if(canManageProducts())b.setMessage("코드: $code\n이 코드로 바로 등록할까요?").setPositiveButton("지금 등록"){_,_->showRegister(code)}
            b.setNegativeButton("계속 스캔"){_,_->showCalculate();ensureCamera(ScanMode.CALCULATE)}.show()
            return
        }
        val productCode=p.code
        val e=cart[productCode]
        if(e!=null){
            if(e.qty>=p.stock){toast("현재 재고 수량까지 담겼습니다.");return}
            e.qty++
        }else{
            if(p.stock<=0){toast("재고가 없는 상품입니다.");return}
            cart[productCode]=CartLine(p,1)
        }
        showCalculate();ensureCamera(ScanMode.CALCULATE)
    }

    private fun checkout(){
        if(cart.isEmpty()){toast("장바구니가 비어 있습니다.");return}
        if(cart.values.any{it.qty>it.product.stock}){toast("재고보다 많은 상품이 있습니다.");return}
        val total=cart.values.sumOf{it.product.price*it.qty}
        val profit=cart.values.sumOf{((it.product.price-it.product.costPrice).coerceAtLeast(0))*it.qty}
        val itemCount=cart.values.sumOf{it.qty}
        cart.values.forEach{line->
            line.product.stock=(line.product.stock-line.qty).coerceAtLeast(0)
            addStockMovement(line.product.code,"판매",-line.qty,line.product.stock,"POS 판매")
        }
        salesTotal+=total
        salesHistory.add(SaleRecord(System.currentTimeMillis(),total,itemCount,currentUser?.name?:"직원",profit))
        cart.clear();saveData();toast("판매 완료 ${money(total)}원");showCalculate()
    }

    private fun beepAndVibrate(){
        tone.startTone(ToneGenerator.TONE_PROP_BEEP,110)
        val vib=getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vib.vibrate(VibrationEffect.createOneShot(70,VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun seedUsers(){
        if(users.isNotEmpty())return
        users["hq"]=UserAccount("hq","본사 관리자",Role.HQ,hashPassword("1234"),true)
        users["javass01"]=UserAccount("javass01","안동일 대표",Role.OWNER,hashPassword("1234"),true)
        users["cs001"]=UserAccount("cs001","김철수",Role.MANAGER,hashPassword("1234"),true)
        users["yh002"]=UserAccount("yh002","이영희",Role.STAFF,hashPassword("1234"),true)
        saveData()
    }

    private fun loadData(){
        val pref=getSharedPreferences("javas_pos",MODE_PRIVATE)
        salesTotal=pref.getInt("sales",0)

        try{
            val a=JSONArray(pref.getString("products","[]"))
            for(i in 0 until a.length()){
                val o=a.getJSONObject(i)
                val p=Product(
                    o.getString("code"),o.getString("name"),o.getInt("price"),o.getInt("stock"),
                    o.optInt("costPrice",0),o.optString("category",""),o.optString("supplier",""),
                    o.optString("receivedDate",""),o.optString("storageLocation",""),o.optString("expiryDate",""),o.optInt("minStock",5)
                )
                products[p.code]=p
            }
        }catch(_:Exception){}

        try{
            val a=JSONArray(pref.getString("stock_history","[]"))
            for(i in 0 until a.length()){val o=a.getJSONObject(i);stockHistory.add(StockMovement(o.getLong("timestamp"),o.getString("code"),o.getString("type"),o.getInt("qty"),o.getInt("afterStock"),o.optString("note","")))}
        }catch(_:Exception){}

        try{
            val a=JSONArray(pref.getString("sales_history","[]"))
            for(i in 0 until a.length()){val o=a.getJSONObject(i);salesHistory.add(SaleRecord(o.getLong("timestamp"),o.getInt("total"),o.optInt("itemCount",0),o.optString("seller","직원"),o.optInt("profit",0)))}
        }catch(_:Exception){}

        try{
            val a=JSONArray(pref.getString("suppliers","[]"))
            for(i in 0 until a.length()){val o=a.getJSONObject(i);val s=Supplier(o.getString("id"),o.getString("name"),o.optString("phone",""),o.optString("manager",""),o.optString("memo",""));suppliers[s.id]=s}
        }catch(_:Exception){}

        try{
            val a=JSONArray(pref.getString("purchase_history","[]"))
            for(i in 0 until a.length()){val o=a.getJSONObject(i);purchaseHistory.add(PurchaseRecord(o.getLong("timestamp"),o.getString("supplierName"),o.getString("code"),o.getString("productName"),o.getInt("qty"),o.getInt("unitCost"),o.getInt("total")))}
        }catch(_:Exception){}

        try{
            val a=JSONArray(pref.getString("customers","[]"))
            for(i in 0 until a.length()){val o=a.getJSONObject(i);val c=Customer(o.getString("id"),o.getString("name"),o.optString("phone",""),o.optString("memo",""));customers[c.id]=c}
        }catch(_:Exception){}

        try{
            val a=JSONArray(pref.getString("tickets","[]"))
            for(i in 0 until a.length()){val o=a.getJSONObject(i);tickets.add(Ticket(o.getString("id"),o.getLong("createdAt"),o.getString("type"),o.getString("customerName"),o.optString("phone",""),o.getString("item"),o.optString("dueDate",""),o.optString("status","접수"),o.optString("memo","")))}
        }catch(_:Exception){}

        try{
            val a=JSONArray(pref.getString("users","[]"))
            for(i in 0 until a.length()){
                val o=a.getJSONObject(i)
                val r=try{Role.valueOf(o.getString("role"))}catch(_:Exception){Role.STAFF}
                val u=UserAccount(o.getString("id"),o.getString("name"),r,o.getString("passwordHash"),o.optBoolean("active",true))
                users[u.id]=u
            }
        }catch(_:Exception){}
    }

    private fun saveData(){
        localDataVersion++
        val pref=getSharedPreferences("javas_pos",MODE_PRIVATE)
        val dataUpdatedAt=if(applyingCloud) pref.getLong("data_updated_at",0L) else System.currentTimeMillis()
        val pa=JSONArray()
        products.values.forEach{p->pa.put(JSONObject().put("code",p.code).put("name",p.name).put("price",p.price).put("stock",p.stock).put("costPrice",p.costPrice).put("category",p.category).put("supplier",p.supplier).put("receivedDate",p.receivedDate).put("storageLocation",p.storageLocation).put("expiryDate",p.expiryDate).put("minStock",p.minStock))}
        val sha=JSONArray()
        stockHistory.takeLast(500).forEach{m->sha.put(JSONObject().put("timestamp",m.timestamp).put("code",m.code).put("type",m.type).put("qty",m.qty).put("afterStock",m.afterStock).put("note",m.note))}
        val sa=JSONArray()
        salesHistory.takeLast(1000).forEach{s->sa.put(JSONObject().put("timestamp",s.timestamp).put("total",s.total).put("itemCount",s.itemCount).put("seller",s.seller).put("profit",s.profit))}
        val spa=JSONArray()
        suppliers.values.forEach{s->spa.put(JSONObject().put("id",s.id).put("name",s.name).put("phone",s.phone).put("manager",s.manager).put("memo",s.memo))}
        val pra=JSONArray()
        purchaseHistory.takeLast(1000).forEach{r->pra.put(JSONObject().put("timestamp",r.timestamp).put("supplierName",r.supplierName).put("code",r.code).put("productName",r.productName).put("qty",r.qty).put("unitCost",r.unitCost).put("total",r.total))}
        val ca=JSONArray()
        customers.values.forEach{c->ca.put(JSONObject().put("id",c.id).put("name",c.name).put("phone",c.phone).put("memo",c.memo))}
        val ta=JSONArray()
        tickets.forEach{t->ta.put(JSONObject().put("id",t.id).put("createdAt",t.createdAt).put("type",t.type).put("customerName",t.customerName).put("phone",t.phone).put("item",t.item).put("dueDate",t.dueDate).put("status",t.status).put("memo",t.memo))}
        val ua=JSONArray()
        users.values.forEach{u->ua.put(JSONObject().put("id",u.id).put("name",u.name).put("role",u.role.name).put("passwordHash",u.passwordHash).put("active",u.active))}
        pref.edit()
            .putString("products",pa.toString()).putString("stock_history",sha.toString()).putString("sales_history",sa.toString())
            .putString("suppliers",spa.toString()).putString("purchase_history",pra.toString()).putString("customers",ca.toString())
            .putString("tickets",ta.toString()).putString("users",ua.toString()).putInt("sales",salesTotal)
            .putLong("data_updated_at",dataUpdatedAt).apply()
        if(!applyingCloud && cloudToken.isNotBlank()) pushCloudAsync()
    }

    private fun hasBusinessData(snapshot:JSONObject):Boolean{
        return listOf("products","sales_history","suppliers","customers","tickets").any{
            (snapshot.optJSONArray(it)?.length()?:0) > 0
        }
    }

    private fun buildCloudSnapshot():JSONObject{
        val pref=getSharedPreferences("javas_pos",MODE_PRIVATE)
        return JSONObject()
            .put("products",JSONArray(pref.getString("products","[]")))
            .put("stock_history",JSONArray(pref.getString("stock_history","[]")))
            .put("sales_history",JSONArray(pref.getString("sales_history","[]")))
            .put("suppliers",JSONArray(pref.getString("suppliers","[]")))
            .put("purchase_history",JSONArray(pref.getString("purchase_history","[]")))
            .put("customers",JSONArray(pref.getString("customers","[]")))
            .put("tickets",JSONArray(pref.getString("tickets","[]")))
            .put("users",JSONArray(pref.getString("users","[]")))
            .put("data_updated_at",pref.getLong("data_updated_at",0L))
    }

    private fun applyCloudSnapshot(snapshot:JSONObject){
        if(cart.isNotEmpty()) return
        applyingCloud=true
        try{
            val pref=getSharedPreferences("javas_pos",MODE_PRIVATE)
            val edit=pref.edit()
            val keys=listOf("products","stock_history","sales_history","suppliers","purchase_history","customers","tickets")
            keys.forEach{k-> edit.putString(k,(snapshot.optJSONArray(k)?:JSONArray()).toString())}
            val remoteUsers=snapshot.optJSONArray("users")
            if(remoteUsers!=null && remoteUsers.length()>0) edit.putString("users",remoteUsers.toString())
            val remoteSales=snapshot.optJSONArray("sales_history")?:JSONArray()
            var total=0
            for(i in 0 until remoteSales.length()) total+=remoteSales.optJSONObject(i)?.optInt("total",0)?:0
            edit.putInt("sales",total)
                .putString("store_id",storeId)
                .putLong("data_updated_at",snapshot.optLong("data_updated_at",0L))
                .apply()

            products.clear()
            stockHistory.clear()
            salesHistory.clear()
            suppliers.clear()
            purchaseHistory.clear()
            customers.clear()
            tickets.clear()
            users.clear()
            salesTotal=0
            loadData()
            seedUsers()
        }finally{
            applyingCloud=false
        }
    }

    private fun pullCloudAsync(showMessage:Boolean){
        if(cloudToken.isBlank()||cart.isNotEmpty()) return
        val versionAtRequest=localDataVersion
        syncExecutor.execute{
            try{
                val snapshot=cloud.getSnapshot(storeId,cloudToken)
                runOnUiThread{
                    if(versionAtRequest!=localDataVersion){
                        if(showMessage) toast("방금 저장한 상품을 보호했습니다.")
                        return@runOnUiThread
                    }
                    val pref=getSharedPreferences("javas_pos",MODE_PRIVATE)
                    val localUpdated=pref.getLong("data_updated_at",0L)
                    val remoteUpdated=snapshot.optLong("data_updated_at",0L)
                    val hasLocal=products.isNotEmpty()||salesHistory.isNotEmpty()||suppliers.isNotEmpty()||customers.isNotEmpty()||tickets.isNotEmpty()
                    if(hasLocal && localUpdated>remoteUpdated){
                        if(showMessage) toast("휴대폰 데이터가 더 최신입니다. 서버 저장을 눌러 주세요.")
                        return@runOnUiThread
                    }
                    applyCloudSnapshot(snapshot)
                    if(currentScreen=="dashboard") showDashboard()
                    if(showMessage) toast("최신 매장 데이터 불러오기 완료")
                }
            }catch(_:Exception){
                if(showMessage) runOnUiThread{toast("동기화 서버 연결을 확인해 주세요.")}
            }
        }
    }

    private fun pushCloudAsync(showMessage:Boolean=false){
        if(cloudToken.isBlank()){
            if(showMessage) toast("서버 연결 후 다시 시도해 주세요.")
            return
        }
        val snapshot=try{buildCloudSnapshot()}catch(_:Exception){
            if(showMessage) toast("서버 저장 자료를 만들 수 없습니다.")
            return
        }
        syncExecutor.execute{
            try{
                cloud.putSnapshot(storeId,cloudToken,snapshot)
                if(showMessage) runOnUiThread{toast("서버 백업 완료")}
            }catch(_:Exception){
                if(showMessage) runOnUiThread{toast("서버 저장 연결을 확인해 주세요.")}
            }
        }
    }

    private fun addStockMovement(code:String,type:String,qty:Int,after:Int,note:String=""){
        stockHistory.add(StockMovement(System.currentTimeMillis(),code,type,qty,after,note))
        while(stockHistory.size>500)stockHistory.removeAt(0)
    }

    private fun hashPassword(pw:String):String{
        val d=MessageDigest.getInstance("SHA-256").digest(("JAVAS_POS_2026:"+pw).toByteArray())
        return d.joinToString(""){"%02x".format(it)}
    }

    private fun normalizeCode(s:String)=s.trim().replace(Regex("\\s+"),"")

    private fun codeCandidates(raw:String):List<String>{
        val c=normalizeCode(raw)
        if(c.isBlank()) return emptyList()

        val result=linkedSetOf(c)
        val numericLike=c.all{it.isDigit() || it=='-'}
        if(numericLike){
            val digits=c.filter{it.isDigit()}
            if(digits.isNotBlank()) result.add(digits)

            when(digits.length){
                12->{
                    result.add("0$digits")
                    result.add("00$digits")
                }
                13->{
                    if(digits.startsWith("0")) result.add(digits.drop(1))
                    result.add("0$digits")
                }
                14->{
                    if(digits.startsWith("0")) result.add(digits.drop(1))
                    if(digits.startsWith("00")) result.add(digits.drop(2))
                }
            }
        }
        return result.toList()
    }

    private fun findProductByCode(raw:String):Product?{
        val wanted=codeCandidates(raw).toSet()
        if(wanted.isEmpty()) return null

        wanted.forEach{k->products[k]?.let{return it}}

        return products.values.firstOrNull{p->
            codeCandidates(p.code).any{it in wanted}
        }
    }

    private fun money(v:Int)=NumberFormat.getNumberInstance(Locale.KOREA).format(v)
    private fun todayString()=SimpleDateFormat("yyyy-MM-dd",Locale.KOREA).format(Date())
    private fun dateKey(t:Long)=SimpleDateFormat("yyyy-MM-dd",Locale.KOREA).format(Date(t))
    private fun formatDateTime(t:Long)=SimpleDateFormat("MM-dd HH:mm",Locale.KOREA).format(Date(t))

    private fun isValidDate(v:String)=try{SimpleDateFormat("yyyy-MM-dd",Locale.KOREA).apply{isLenient=false}.parse(v);true}catch(_:Exception){false}

    private fun daysFromToday(v:String):Long?{
        if(v.isBlank()||!isValidDate(v))return null
        return try{
            val f=SimpleDateFormat("yyyy-MM-dd",Locale.KOREA).apply{isLenient=false}
            val target=f.parse(v)?:return null
            val today=f.parse(todayString())?:return null
            (target.time-today.time)/86400000L
        }catch(_:Exception){null}
    }

    private fun isLongStock(p:Product)=daysFromToday(p.receivedDate)?.let{it<=-180&&p.stock>0}?:false
    private fun isExpirySoon(p:Product)=daysFromToday(p.expiryDate)?.let{it in 0..30&&p.stock>0}?:false

    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun matchWrap(top:Int=0,bottom:Int=0)=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(top);bottomMargin=dp(bottom)}
    private fun rounded(fill:Int,stroke:Int=Color.TRANSPARENT)=android.graphics.drawable.GradientDrawable().apply{
        color=android.content.res.ColorStateList.valueOf(fill);cornerRadius=dp(14).toFloat();setStroke(dp(1),stroke)
    }
    private fun card()=LinearLayout(this).apply{background=rounded(Color.WHITE);elevation=dp(2).toFloat()}
    private fun text(s:String,size:Int,bold:Boolean,color:Int=Color.rgb(7,53,76))=TextView(this).apply{text=s;textSize=size.toFloat();includeFontPadding=false;setTextColor(color);if(bold)setTypeface(typeface,Typeface.BOLD)}
    private fun centerText(s:String,size:Int,bold:Boolean,color:Int=Color.rgb(7,53,76))=text(s,size,bold,color).apply{gravity=Gravity.CENTER}
    private fun fieldLabel(s:String)=text(s,12,true,Color.rgb(52,64,84))
    private fun field(h:String)=EditText(this).apply{hint=h;textSize=15f;includeFontPadding=false;minHeight=dp(42);minimumHeight=dp(42);setPadding(dp(11),dp(6),dp(11),dp(6));background=rounded(Color.WHITE,Color.rgb(205,216,221))}
    private fun primaryButton(s:String,a:()->Unit)=Button(this).apply{text=s;textSize=15f;includeFontPadding=false;minHeight=0;minimumHeight=0;setPadding(dp(8),dp(6),dp(8),dp(6));setTextColor(Color.WHITE);setTypeface(typeface,Typeface.BOLD);background=rounded(Color.rgb(11,75,107));setOnClickListener{a()}}
    private fun secondaryButton(s:String,a:()->Unit)=Button(this).apply{text=s;textSize=13f;includeFontPadding=false;minHeight=0;minimumHeight=0;setPadding(dp(6),dp(5),dp(6),dp(5));setTextColor(Color.rgb(7,83,105));setTypeface(typeface,Typeface.BOLD);background=rounded(Color.rgb(226,244,248));setOnClickListener{a()}}
    private fun dangerButton(s:String,a:()->Unit)=Button(this).apply{text=s;textSize=13f;includeFontPadding=false;minHeight=0;minimumHeight=0;setPadding(dp(6),dp(5),dp(6),dp(5));setTextColor(Color.rgb(180,35,24));setTypeface(typeface,Typeface.BOLD);background=rounded(Color.rgb(255,237,235));setOnClickListener{a()}}
    private fun smallButton(s:String,a:()->Unit)=secondaryButton(s,a)
    private fun navButton(s:String,a:()->Unit)=Button(this).apply{text=s;textSize=13f;includeFontPadding=false;minHeight=0;minimumHeight=0;setPadding(dp(4),dp(4),dp(4),dp(4));setTextColor(Color.rgb(7,53,76));setTypeface(typeface,Typeface.BOLD);background=rounded(Color.WHITE);setOnClickListener{a()}}
    private fun statBox(label:String,value:String)=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(9),dp(7),dp(9),dp(7));background=rounded(Color.rgb(247,250,251))
        addView(text(label,11,false,Color.GRAY));addView(text(value,if(value.length>10)15 else 18,true))
    }
}

class CloudSync(private val activity: Activity, private val baseUrl: String) {
    data class LoginResult(val token:String,val storeId:String,val userId:String,val name:String,val role:String)
    class HttpError(val status:Int,message:String):IOException(message)
    private class Pending{val latch=CountDownLatch(1);@Volatile var ok=false;@Volatile var payload=""}
    private val pending=ConcurrentHashMap<String,Pending>()
    private val ready=CountDownLatch(1)
    private val webView:WebView

    init{
        webView=WebView(activity).apply{
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            addJavascriptInterface(object{
                @JavascriptInterface fun onResult(id:String,ok:Boolean,payload:String){pending[id]?.let{it.ok=ok;it.payload=payload;it.latch.countDown()}}
            },"AndroidCloud")
            webViewClient=object:WebViewClient(){override fun onPageFinished(view:WebView?,url:String?){ready.countDown()}}
            loadUrl(baseUrl.trimEnd('/')+"/?nativeBridge=1")
        }
    }

    fun close(){activity.runOnUiThread{try{webView.removeJavascriptInterface("AndroidCloud");webView.destroy()}catch(_:Exception){}}}

    fun login(storeId:String,userId:String,password:String):LoginResult{
        val r=request("POST","/api/login",JSONObject().put("storeId",storeId).put("userId",userId).put("password",password))
        val u=r.getJSONObject("user")
        return LoginResult(r.getString("token"),r.getString("storeId"),u.getString("id"),u.getString("name"),u.getString("role"))
    }

    fun getSnapshot(storeId:String,token:String):JSONObject=
        request("GET","/api/snapshot?storeId=${encode(storeId)}&token=${encode(token)}",null).getJSONObject("snapshot")

    fun putSnapshot(storeId:String,token:String,snapshot:JSONObject){
        request("PUT","/api/snapshot",JSONObject().put("storeId",storeId).put("token",token).put("snapshot",snapshot))
    }

    private fun request(method:String,path:String,body:JSONObject?):JSONObject{
        if(!ready.await(15,TimeUnit.SECONDS))throw IOException("cloud bridge not ready")
        val id=UUID.randomUUID().toString();val p=Pending();pending[id]=p
        val script="""(function(){var id=${JSONObject.quote(id)};window.JavasNativeApi.request(${JSONObject.quote(method)},${JSONObject.quote(path)},${body?.toString()?:"null"}).then(function(v){AndroidCloud.onResult(id,true,JSON.stringify(v));}).catch(function(e){AndroidCloud.onResult(id,false,JSON.stringify({status:(e&&e.status)||0,message:(e&&e.message)||'request_failed'}));});})();"""
        activity.runOnUiThread{webView.evaluateJavascript(script,null)}
        if(!p.latch.await(20,TimeUnit.SECONDS)){pending.remove(id);throw IOException("cloud request timeout")}
        pending.remove(id)
        if(!p.ok){val e=try{JSONObject(p.payload)}catch(_:Exception){JSONObject()};throw HttpError(e.optInt("status",0),e.optString("message",p.payload.ifBlank{"cloud request failed"}))}
        return if(p.payload.isBlank()||p.payload=="null")JSONObject() else JSONObject(p.payload)
    }

    private fun encode(value:String)=java.net.URLEncoder.encode(value,"UTF-8")
}
