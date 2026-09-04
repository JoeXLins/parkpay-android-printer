package com.example.parkpay

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.lang.Exception
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.set

class LocalServer(
    port: Int = 8080,
    private val secret: String = "local-demo-secret" // 演示用，若公开请更换
) : NanoHTTPD(port) {

    // 内存订单存储： session -> { amount_cents, status, provider_tx_id }
    private val orders = mutableMapOf<String, MutableMap<String, Any>>()

    override fun serve(session: IHTTPSession?): Response {
        if (session == null) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "bad request")
        try {
            val uri = session.uri
            when {
                uri == "/payments/create" && session.method == Method.POST -> {
                    val bodyMap = HashMap<String, String>()
                    session.parseBody(bodyMap)
                    val postData = bodyMap["postData"] ?: ""
                    val jo = JSONObject(postData)
                    val sid = jo.getString("session")
                    val amountCents = jo.getInt("amount_cents")
                    val expires = (System.currentTimeMillis() / 1000L) + 10 * 60 // 10 分钟
                    val payload = "$sid|$amountCents|$expires"
                    val sig = hmacHex(secret, payload)

                    // 默认用局部回环地址；若想让他人扫码访问，可把 host 换为手机局域网 IP（见下）
                    val host = "127.0.0.1"
                    val payUrl = "http://${host}:${this.listeningPort}/pay?session=${sid}&amount=${amountCents/100}&expires=${expires}&sig=${sig}"

                    // 保存订单
                    orders[sid] = mutableMapOf("amount_cents" to amountCents, "status" to "pending")
                    val resp = JSONObject().put("pay_url", payUrl)
                    return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
                }

                uri.startsWith("/pay") && session.method == Method.GET -> {
                    val q = session.parms
                    val sid = q["session"] ?: "unknown"
                    val amount = q["amount"] ?: "0"
                    val html = """
                        <html><body>
                        <h3>模拟支付页面</h3>
                        <p>订单: ${sid}</p>
                        <p>金额: ${amount} 元</p>
                        <form method="POST" action="/simulate-pay">
                          <input type="hidden" name="session" value="${sid}">
                          <input type="hidden" name="amount" value="${amount}">
                          <button type="submit">模拟支付成功并回调Webhook</button>
                        </form>
                        </body></html>
                    """.trimIndent()
                    return newFixedLengthResponse(Response.Status.OK, "text/html", html)
                }

                uri == "/simulate-pay" && session.method == Method.POST -> {
                    val bodyMap = HashMap<String, String>()
                    session.parseBody(bodyMap)
                    val post = bodyMap["postData"] ?: ""
                    val params = parseForm(post)
                    val sid = params["session"] ?: return newFixedLengthResponse("missing session")
                    val amount = params["amount"] ?: "0"
                    val providerTxId = "tx_" + System.currentTimeMillis().toString(36)
                    orders[sid] = mutableMapOf("amount_cents" to (amount.toInt() * 100), "status" to "paid", "provider_tx_id" to providerTxId)
                    val respHtml = """
                        <html><body>
                        <h3>支付模拟成功</h3>
                        <pre>${JSONObject().put("session", sid).put("amount_cents", amount.toInt()*100).put("provider_tx_id", providerTxId).put("sig", hmacHex(secret, "$sid|${amount.toInt()}"))}</pre>
                        <p>订单状态已标记为 paid（仅本地演示）</p>
                        </body></html>
                    """.trimIndent()
                    return newFixedLengthResponse(Response.Status.OK, "text/html", respHtml)
                }

                uri.startsWith("/orders/") && session.method == Method.GET -> {
                    val parts = uri.split("/")
                    val sid = if (parts.size >= 3) parts[2] else ""
                    val data = orders[sid]
                    val resp = if (data != null) JSONObject(data as Map<*, *>) else JSONObject.NULL
                    return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
                }

                else -> {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}")
        }
    }

    private fun parseForm(body: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        body.split("&").forEach {
            val kv = it.split("=")
            if (kv.size >= 2) {
                map[kv[0]] = java.net.URLDecoder.decode(kv[1], "UTF-8")
            }
        }
        return map
    }

    // HMAC-SHA256 -> hex
    private fun hmacHex(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }
}
