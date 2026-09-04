package com.example.parkpay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.OutputStream
import java.util.*
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var btnCapture: Button
    private lateinit var btnSelectPrinter: Button
    private lateinit var btnSettlePrint: Button
    private lateinit var tvPlate: TextView
    private lateinit var etPlateManual: EditText

    private var imageCapture: ImageCapture? = null
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var selectedDevice: BluetoothDevice? = null

    // Local embedded server
    private var localServer: LocalServer? = null
    private val LOCAL_SERVER_PORT = 8080
    private val LOCAL_HOST = "http://127.0.0.1:$LOCAL_SERVER_PORT"

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
    )

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        // no-op
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        btnCapture = findViewById(R.id.btnCapture)
        btnSelectPrinter = findViewById(R.id.btnSelectPrinter)
        btnSettlePrint = findViewById(R.id.btnSettlePrint)
        tvPlate = findViewById(R.id.tvPlate)
        etPlateManual = findViewById(R.id.etPlateManual)

        requestPermissionsLauncher.launch(requiredPermissions)

        startCamera()

        btnCapture.setOnClickListener {
            takePhotoAndRecognize()
        }

        btnSelectPrinter.setOnClickListener {
            selectPairedPrinter()
        }

        btnSettlePrint.setOnClickListener {
            settleAndPrint()
        }

        // Start local embedded server for demo
        localServer = LocalServer(LOCAL_SERVER_PORT, "local-demo-secret")
        try {
            localServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            Toast.makeText(this, "本地 server 启动: $LOCAL_HOST", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "启动本地 server 失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { localServer?.stop() } catch (e: Exception) { }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhotoAndRecognize() {
        val ic = imageCapture ?: return
        ic.takePicture(ContextCompat.getMainExecutor(this), object: ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                processImageProxy(imageProxy)
                imageProxy.close()
            }
            override fun onError(exception: ImageCaptureException) {
                Toast.makeText(this@MainActivity, "拍照失败: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return
        val rotation = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotation)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.text
                val plate = PlateExtractor.extractPlate(rawText)
                runOnUiThread {
                    tvPlate.text = "识别到车牌：${plate ?: "未识别"}"
                    if (plate != null) etPlateManual.setText(plate)
                }
            }
            .addOnFailureListener { e ->
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this, "识别出错", Toast.LENGTH_SHORT).show() }
            }
    }

    private fun selectPairedPrinter() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_SHORT).show()
            return
        }
        val paired = adapter.bondedDevices.toList()
        if (paired.isEmpty()) {
            Toast.makeText(this, "没有已配对的蓝牙设备，请先在系统设置配对打印机", Toast.LENGTH_SHORT).show()
            return
        }
        val names = paired.map { "${it.name ?: "未知"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择打印机")
            .setItems(names) { _, which ->
                selectedDevice = paired[which]
                Toast.makeText(this, "已选择：${paired[which].name}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun settleAndPrint() {
        val plate = etPlateManual.text.toString().trim()
        if (plate.isEmpty()) {
            Toast.makeText(this, "请输入或识别车牌", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedDevice == null) {
            Toast.makeText(this, "请先选择打印机", Toast.LENGTH_SHORT).show()
            return
        }

        // 示范: 使用当前时间为开始/结束测试（真实应从 session 获取）
        val startAt = Date(System.currentTimeMillis() - 45 * 60 * 1000L) // 假设停车 45 分钟
        val endAt = Date()
        val minutes = ceil((endAt.time - startAt.time) / 60000.0).toInt()
        val amountCents = calculateFee(minutes)

        // Create payment on local embedded server
        val publicHost = LOCAL_HOST
        val sessionId = "demo-${UUID.randomUUID()}"

        CoroutineScope(Dispatchers.IO).launch {
            var payUrl: String? = null
            try {
                payUrl = createPaymentOnServer(publicHost, sessionId, amountCents)
            } catch (e: Exception) { e.printStackTrace() }
            if (payUrl == null) {
                runOnUiThread { Toast.makeText(this@MainActivity, "无法从本地 server 获取支付链接", Toast.LENGTH_LONG).show() }
                return@launch
            }
            val qrBitmap = QrGenerator.generate(payUrl, 300)
            val receipt = buildReceiptText(plate, startAt, endAt, minutes, amountCents)

            try {
                val socket = createRfcommSocket(selectedDevice!!)
                socket.connect()
                val out: OutputStream = socket.outputStream
                EscPosPrinter.printReceipt(out, receipt + "\n", qrBitmap)
                out.close()
                socket.close()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "打印成功", Toast.LENGTH_SHORT).show()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("支付链接已生成")
                        .setMessage(payUrl)
                        .setPositiveButton("在浏览器打开") { _, _ ->
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(payUrl)))
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this@MainActivity, "打印失败: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun calculateFee(durationMinutes: Int): Int {
        val perHourCents = 500 // 5 元/小时
        val roundingMinutes = 15
        val rounded = ceil(durationMinutes.toDouble() / roundingMinutes).toInt() * roundingMinutes
        val amount = perHourCents * (rounded / 60.0)
        val minCents = 200
        return maxOf(minCents, amount.toInt())
    }

    private fun buildReceiptText(plate: String, start: Date, end: Date, minutes: Int, amountCents: Int): String {
        return """
            停车小票
            车牌: $plate
            开始: $start
            结束: $end
            时长: ${minutes} 分钟
            金额: ${amountCents/100}.${(amountCents%100).toString().padStart(2,'0')} 元

            请扫码支付：
        """.trimIndent()
    }

    // 创建 RFCOMM socket（使用常见 SPP UUID）
    private fun createRfcommSocket(device: BluetoothDevice): BluetoothSocket {
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        return device.createRfcommSocketToServiceRecord(uuid)
    }

    // Network helper: call local server to create payment
    suspend fun createPaymentOnServer(publicHost: String, session: String, amountCents: Int): String? {
        val client = OkHttpClient()
        val json = JSONObject().put("session", session).put("amount_cents", amountCents)
        val body = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), json.toString())
        val req = Request.Builder()
            .url("$publicHost/payments/create")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val respBody = resp.body?.string() ?: return null
            val obj = JSONObject(respBody)
            return obj.optString("pay_url", null)
        }
    }
}
