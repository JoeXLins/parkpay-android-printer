package com.example.parkpay

import android.graphics.Bitmap
import java.io.OutputStream
import java.nio.charset.Charset

object EscPosPrinter {
    const val TARGET_WIDTH = 384

    fun bitmapToEscPosRaster(bitmap: Bitmap): ByteArray {
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

        val output = ArrayList<Byte>()
        output.addAll(listOf(0x1B, 0x40).map { it.toByte() }) // ESC @
        val m: Byte = 0x00
        val xL = (bytesPerRow and 0xFF).toByte()
        val xH = ((bytesPerRow shr 8) and 0xFF).toByte()
        val yL = (height and 0xFF).toByte()
        val yH = ((height shr 8) and 0xFF).toByte()
        output.addAll(listOf(0x1D, 0x76, 0x30, m).map { it.toByte() })
        output.add(xL); output.add(xH); output.add(yL); output.add(yH)
        for (b in imageBytes) output.add(b)
        output.addAll(listOf(0x0A, 0x0A, 0x0A).map { it.toByte() })
        return output.toByteArray()
    }

    fun textToBytes(text: String): ByteArray {
        val sb = StringBuilder()
        sb.append(text)
        if (!text.endsWith("\n")) sb.append("\n")
        return sb.toString().toByteArray(Charset.forName("GBK"))
    }

    fun printReceipt(out: OutputStream, text: String, qrBitmap: Bitmap) {
        out.write(textToBytes(text))
        out.write(byteArrayOf(0x0A))
        val data = bitmapToEscPosRaster(qrBitmap)
        out.write(data)
        try {
            out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00))
        } catch (e: Exception) { }
        out.flush()
    }
}
