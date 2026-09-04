package com.example.parkpay

object PlateExtractor {
    // 提取中文车牌（蓝牌/新能源绿牌）简化正则
    fun extractPlate(raw: String): String? {
        val cleaned = raw
            .replace("·", "")
            .replace(" ", "")
            .replace("\n", "")
            .replace("－", "-")
            .replace("—", "-")
            .replace(Regex("[^\\u4e00-\\u9fa5A-Za-z0-9-]"), "")
            .uppercase()

        val regex = Regex("([\\u4e00-\\u9fa5][A-Z][A-Z0-9]{5,6})")
        val m = regex.find(cleaned)
        return m?.value
    }
}
