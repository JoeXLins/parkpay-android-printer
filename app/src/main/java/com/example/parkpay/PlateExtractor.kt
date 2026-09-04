package com.example.parkpay

object PlateExtractor {
    // 尝试从 OCR 识别到的原始文本中抽取中文车牌（包含常见蓝牌与新能源绿牌）
    // 常见简化规则：
    // - 普通蓝牌：1 个汉字 + 1 个大写字母 + 5 个字母/数字  (总长度 7)
    // - 新能源绿牌：1 个汉字 + 1 个大写字母 + 6 个字母/数字  (总长度 8)
    // 这里使用宽松正则并做清理（去空白、去中文·/全角字符等）
    fun extractPlate(raw: String): String? {
        val cleaned = raw
            .replace("·", "")
            .replace(" ", "")
            .replace("\n", "")
            .replace("－", "-")
            .replace("—", "-")
            .replace(Regex("[^\\u4e00-\\u9fa5A-Za-z0-9-]"), "")
            .uppercase()

        // 常见模式：汉字 + 字母 + 5或6个字母/数字
        val regex = Regex("([\\u4e00-\\u9fa5][A-Z][A-Z0-9]{5,6})")
        val m = regex.find(cleaned)
        return m?.value
    }
}
