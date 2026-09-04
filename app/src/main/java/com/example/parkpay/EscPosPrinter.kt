package com.example.parkpay

import android.graphics.Bitmap
import java.io.OutputStream
import java.nio.charset.Charset
import kotlin.math.ceil

object EscPosPrinter {
    // 58mm 常见打印像素宽度：384 px（取决于打印机，若不对可调整）
    const val TARGET_WIDTH = 384

    // 将位图按 monochrome 转换为 ESC/POS raster 格式数据（GS v 0）
    fun bitmapToEscPosRaster(bitmap: Bitmap): ByteArray {
        // 如果宽度大于目标宽，缩放
        val bmp = if (bitmap.width != TARGET_WIDTH) {
            val h = (bitmap.height.toFloat() * TARGET_WIDTH / bitmap.width).toInt()
            Bitmap.createScaledBitmap(bitmap, TARGET_WIDTH, h, true)
        } else bitmap

        val width = bmp.width
        val height = bmp.height
        val bytesPerRow = (width + 7) / 8
        val imageBytes = ByteArray(bytesPerRow * height)
        var idx = 0
        for (y in 0 until height) {
            var bitIndex = 0
            var currentByte = 0
            for (x in 0 until width) {
                val pixel = bmp.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b)
                val black = luminance < 128
                currentByte = (currentByte shl 1) or if (black) 1 else 0
                bitIndex++
                if (bitIndex == 8) {
                    imageBytes[idx++] = currentByte.toByte()
                    bitIndex = 0
                    currentByte = 0
                }
            }
            if (bitIndex != 0) {
                currentByte = currentByte shl (8 - bitIndex)
                imageBytes[idx++] = currentByte.toByte()
            }
        }

        // 构造命令：GS v 0 m xL xH yL yH + data
        val output = ArrayList<Byte>()
        // 初始化打印机
        output.addAll(listOf(0x1B, 0x40).map { it.toByte() }) // ESC @
        val m: Byte = 0x00
        val xL = (bytesPerRow and 0xFF).toByte()
        val xH = ((bytesPerRow shr 8) and 0xFF).toByte()
        val yL = (height and 0xFF).toByte()
        val yH = ((height shr 8) and 0xFF).toByte()
        output.addAll(listOf(0x1D, 0x76, 0x30, m).map { it.toByte() })
        output.add(xL); output.add(xH); output.add(yL); output.add(yH)
        // data
        for (b in imageBytes) output.add(b)
        // 换行若干，利于切纸
        output.addAll(listOf(0x0A, 0x0A, 0x0A).map { it.toByte() })
        return output.toByteArray()
    }

    fun textToBytes(text: String): ByteArray {
        // 设置居中/字体等可按需求添加 ESC/POS 命令
        val sb = StringBuilder()
        sb.append(text)
        if (!text.endsWith("\n")) sb.append("\n")
        return sb.toString().toByteArray(Charset.forName("GBK")) // 多数打印机用 GBK 支持中文
    }

    fun printReceipt(out: OutputStream, text: String, qrBitmap: Bitmap) {
        // 打印文字
        out.write(textToBytes(text))
        out.write(byteArrayOf(0x0A))
        // 打印二维码图片
        val data = bitmapToEscPosRaster(qrBitmap)
        out.write(data)
        // 进纸并尝试切纸（部分打印机支持）
        try {
            out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00)) // GS V B n
        } catch (e: Exception) {
            // 忽略
        }
        out.flush()
    }
}
