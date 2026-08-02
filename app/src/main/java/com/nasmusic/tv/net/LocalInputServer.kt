package com.nasmusic.tv.net

import com.nasmusic.tv.util.AppLog
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * 本地输入服务器
 *
 * 在 TV 上启动一个轻量 HTTP server。手机扫码后浏览器打开页面，
 * 输入文字提交后通过 [onText] 回调推送到当前打开的输入框。
 *
 * 生命周期：由 TextInputDialog 在打开时 [start]，关闭时 [stop]。
 * 端口固定 [DEFAULT_PORT]，URL 不变，手机可保持页面打开连续输入。
 *
 * @param onText 回调在 NanoHTTPD 内部线程上调用，调用方需自行切线程更新 UI
 */
class LocalInputServer(
    private val port: Int = DEFAULT_PORT
) {

    companion object {
        const val DEFAULT_PORT = 18080
        private const val TAG = "LocalInputServer"
    }

    private var server: Impl? = null

    /**
     * 启动服务器
     * @return true 启动成功；false 端口被占或其他错误
     */
    fun start(onText: (String) -> Unit): Boolean {
        if (server != null) return true
        val impl = Impl(port) { text ->
            // 去掉换行符，输入框是单行
            val cleaned = text.replace("\r", "").replace("\n", "").trim()
            if (cleaned.isNotEmpty()) {
                onText.invoke(cleaned)
            }
        }
        return try {
            impl.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = impl
            AppLog.i(TAG, "Started on port $port")
            true
        } catch (e: IOException) {
            AppLog.e(TAG, "Failed to start on port $port", e)
            false
        }
    }

    /**
     * 停止服务器
     */
    fun stop() {
        server?.let {
            try {
                it.stop()
                AppLog.i(TAG, "Stopped")
            } catch (e: Exception) {
                AppLog.w(TAG, "Error stopping", e)
            }
        }
        server = null
    }

    /**
     * 服务器是否正在运行
     */
    fun isRunning(): Boolean = server != null

    // ---- NanoHTTPD 实现 ----

    private class Impl(
        port: Int,
        private val onText: (String) -> Unit
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            return when {
                session.uri == "/" && session.method == Method.GET -> serveInputPage()
                session.uri == "/submit" && session.method == Method.POST -> handleSubmit(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }

        private fun serveInputPage(): Response {
            return newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=UTF-8",
                INPUT_PAGE_HTML
            )
        }

        private fun handleSubmit(session: IHTTPSession): Response {
            return try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val text = files["postData"] ?: ""
                if (text.isNotEmpty()) {
                    onText.invoke(text)
                }
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json; charset=UTF-8",
                    """{"ok":true}"""
                )
            } catch (e: Exception) {
                AppLog.e(TAG, "Error handling submit", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json; charset=UTF-8",
                    """{"ok":false}"""
                )
            }
        }
    }
}

/** 手机端输入页面 HTML */
private val INPUT_PAGE_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>NAS Music TV</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:#1a1a2e;color:#eee;display:flex;flex-direction:column;align-items:center;min-height:100vh;padding:24px}
.card{background:#16213e;border-radius:16px;padding:28px;width:100%;max-width:480px;box-shadow:0 8px 32px rgba(0,0,0,.3)}
h2{text-align:center;margin-bottom:8px;font-size:22px}
.sub{text-align:center;color:#888;font-size:13px;margin-bottom:24px}
input{width:100%;padding:16px;font-size:18px;border:2px solid #0f3460;border-radius:10px;background:#0a0a23;color:#eee;outline:none}
input:focus{border-color:#e94560}
button{width:100%;padding:16px;font-size:18px;background:#e94560;color:#fff;border:none;border-radius:10px;margin-top:14px;font-weight:600;cursor:pointer;transition:.2s}
button:active{transform:scale(.98);background:#c73e54}
.status{text-align:center;margin-top:14px;font-size:15px;color:#4ecca3;min-height:22px}
.hint{text-align:center;color:#666;font-size:12px;margin-top:20px;line-height:1.6}
</style>
</head>
<body>
<div class="card">
<h2>NAS Music TV</h2>
<p class="sub">扫码输入 - 文字将显示在电视输入框</p>
<input type="text" id="t" placeholder="输入歌曲/歌手名..." autofocus>
<button onclick="send()">发送到电视</button>
<div class="status" id="s"></div>
</div>
<p class="hint">可连续输入多次，每次点发送后文字会立即出现在电视上</p>
<script>
function send(){
  var t=document.getElementById('t').value;
  if(!t.trim())return;
  fetch('/submit',{method:'POST',body:t})
    .then(function(r){return r.json()})
    .then(function(d){
      if(d.ok){
        document.getElementById('s').textContent='已发送: '+t;
        document.getElementById('t').value='';
        document.getElementById('t').focus();
      }
    })
    .catch(function(e){
      document.getElementById('s').textContent='发送失败';
    });
}
document.getElementById('t').addEventListener('keydown',function(e){
  if(e.key==='Enter'){send();}
});
</script>
</body>
</html>
""".trimIndent()
