package com.example.parkpay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.graphics.Bitmap
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
    )

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        // no-op; assume user accepted for dev convenience
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
                Toast.makeText(this@MainActivity, "拍照失败: ${'$'}{exception.message}", Toast.LENGTH_SHORT).show()
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
                    tvPlate.text = "识别到车牌：${'$'}{plate ?: "未识别"}"
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
        val names = paired.map { "${'$'}{it.name ?: "未知"}\n${'$'}{it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择打印机")
            .setItems(names) { _, which ->
                selectedDevice = paired[which]
                Toast.makeText(this, "已选择：${'$'}{paired[which].name}", Toast.LENGTH_SHORT).show()
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
        val amountYuanText = String.format("%d.%02d", amountCents / 100, amountCents % 100)

        // 生成支付链接（生产环境换成后端返回）
        val payUrl = "http://10.0.2.2:3000/pay?session=demo-${'$'}{UUID.randomUUID()}&amount=${'$'}{amountCents/100}"

        val qrBitmap = QrGenerator.generate(payUrl, 300)

        val receipt = buildReceiptText(plate, startAt, endAt, minutes, amountCents)

        // 打印（在协程中）
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = createRfcommSocket(selectedDevice!!)
                socket.connect()
                val out: OutputStream = socket.outputStream
                // 打印
                EscPosPrinter.printReceipt(out, receipt, qrBitmap)
                out.close()
                socket.close()
                runOnUiThread { Toast.makeText(this@MainActivity, "打印成功", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this@MainActivity, "打印失败: ${'$'}{e.message}", Toast.LENGTH_LONG).show() }
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
            车牌: ${'$'}plate
            开始: ${'$'}start
            结束: ${'$'}end
            时长: ${'$'}{minutes} 分钟
            金额: ${'$'}{amountCents/100}.${'$'}{(amountCents%100).toString().padStart(2,'0')} 元

            请扫码支付：
        """.trimIndent()
    }

    // 创建 RFCOMM socket（使用常见 SPP UUID）
    private fun createRfcommSocket(device: BluetoothDevice): BluetoothSocket {
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        // 需要 BLUETOOTH_CONNECT 权限（在 Android 12+）
        return device.createRfcommSocketToServiceRecord(uuid)
    }
}
