package com.nasmusic.tv.net

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.UrlSanitizer
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * 服务器配置扫码传输服务（2026-09-22 用户需求）。
 *
 * 场景：TV 端「服务器配置」页展示二维码 → 手机扫码打开表单 → 选择后端类型并
 * 填写 URL / 用户名 / 密码 → 提交到 TV → TV 端自动填充表单，由用户用遥控器
 * 确认后才发起连接（本服务只负责传输，不代替用户做连接决策）。
 *
 * 模式与 [PlaylistUploadServer] / [LocalInputServer] 一致：
 * - 端口段约定 18085（18080~18084 已占用）
 * - [LocalServerAuth] 一次性 token 鉴权（二维码 URL 携带 ?t=，页面种 Cookie）
 * - POST 定长分块读取 + 64KB 上限（防同网段谎报 Content-Length OOM）
 *
 * 安全说明：密码经 LAN 明文传输，与备份传输服务的既有取舍一致（cleartext 支持）；
 * token 把访问者限定为「能看到电视二维码的人」。
 */
class ServerConfigTransferServer(
    private val onConfigReceived: (backendType: String, baseUrl: String, username: String, password: String, displayName: String) -> Unit,
    private val port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "ServerCfgTransfer"

        /** 端口段约定：18080 LocalInput / 18081 Backup / 18082 Remote / 18083 Model / 18084 Playlist */
        const val DEFAULT_PORT = 18085

        /** 与 ServerConfig.TYPE_* 同步（private 常量不可跨用，此处本地白名单） */
        private val ALLOWED_TYPES = setOf("jellyfin", "navidrome", "subsonic", "daoliyu", "feiniu")

        private const val MAX_BODY_BYTES = 64L * 1024
    }

    /** P1-1 同款：一次性鉴权 token（随二维码下发，重启即更换） */
    private val authToken = LocalServerAuth.newToken()

    /** 带 token 的二维码 URL（手机浏览器打开后种 Cookie） */
    fun buildUrl(ip: String): String = "http://$ip:$port/?t=$authToken"

    fun startServer(): Boolean = try {
        start(SOCKET_READ_TIMEOUT, false)
        AppLog.i(TAG, "started on port $port")
        true
    } catch (e: IOException) {
        AppLog.e(TAG, "start failed on port $port", e)
        false
    }

    fun stopServer() {
        try {
            stop()
            AppLog.i(TAG, "stopped")
        } catch (e: Exception) {
            AppLog.w(TAG, "stop error", e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        if (!LocalServerAuth.isAuthorized(session, authToken)) return LocalServerAuth.forbidden()
        return when {
            session.uri == "/" && session.method == Method.GET -> servePage()
            session.uri == "/api/config" && session.method == Method.POST -> handleConfig(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun servePage(): Response {
        val response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", buildPageHtml())
        response.addHeader("Set-Cookie", LocalServerAuth.cookieHeader(authToken))
        return response
    }

    /**
     * 接收手机端提交的服务器配置 JSON：
     * {"backendType":"jellyfin","baseUrl":"http://...","username":"..","password":"..","displayName":".."}
     */
    private fun handleConfig(session: IHTTPSession): Response {
        return try {
            val contentLength = session.headers["content-length"]?.toLongOrNull() ?: -1L
            if (contentLength <= 0L || contentLength > MAX_BODY_BYTES) {
                return json(false, "配置内容为空或过大")
            }
            // 定长分块读取（keep-alive 上不能 read 到 -1；不按 Content-Length 预分配）
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            var remaining = contentLength
            while (remaining > 0) {
                val read = session.inputStream.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
                if (read < 0) break
                buffer.write(chunk, 0, read)
                remaining -= read
            }
            val body = String(buffer.toByteArray(), Charsets.UTF_8)
            if (body.isBlank()) return json(false, "配置内容为空")
            val obj = Gson().fromJson(body, JsonObject::class.java)
                ?: return json(false, "配置格式错误")
            val backendType = obj.get("backendType")?.asString?.trim() ?: ""
            val baseUrl = obj.get("baseUrl")?.asString?.trim() ?: ""
            val username = obj.get("username")?.asString?.trim() ?: ""
            val password = obj.get("password")?.asString ?: ""
            val displayName = obj.get("displayName")?.asString?.trim() ?: ""
            if (backendType !in ALLOWED_TYPES) return json(false, "不支持的后端类型：$backendType")
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                return json(false, "服务器地址必须以 http:// 或 https:// 开头")
            }
            AppLog.i(TAG, "config received: type=$backendType url=${UrlSanitizer.sanitize(baseUrl)}")
            onConfigReceived.invoke(backendType, baseUrl, username, password, displayName)
            json(true, "已发送到电视，请在电视上核对并按确认键连接")
        } catch (e: Exception) {
            AppLog.e(TAG, "handleConfig failed", e)
            json(false, "处理失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun json(ok: Boolean, message: String): Response = newFixedLengthResponse(
        Response.Status.OK,
        "application/json; charset=UTF-8",
        Gson().toJson(mapOf("ok" to ok, "message" to message))
    )

    /** 手机端表单页（单文件内嵌，零外部依赖；后端类型值与 ServerConfig.TYPE_* 一致） */
    private fun buildPageHtml(): String {
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>服务器配置填入</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:#1a1a2e;color:#eee;display:flex;justify-content:center;align-items:flex-start;min-height:100vh;padding:24px}
.card{background:#16213e;border-radius:16px;padding:24px;width:100%;max-width:480px;box-shadow:0 8px 32px rgba(0,0,0,.3)}
h2{text-align:center;margin-bottom:6px;font-size:20px}
.sub{text-align:center;color:#888;font-size:13px;margin-bottom:18px}
label{display:block;font-size:13px;color:#aaa;margin:12px 0 6px}
select,input{width:100%;padding:12px;font-size:15px;border:2px solid #0f3460;border-radius:10px;background:#0a0a23;color:#eee;outline:none}
select:focus,input:focus{border-color:#4ecca3}
button{width:100%;padding:14px;font-size:16px;background:#4ecca3;color:#0a0a23;border:none;border-radius:10px;margin-top:16px;font-weight:600;cursor:pointer}
button:disabled{background:#555;color:#999}
.status{text-align:center;margin-top:12px;font-size:14px;min-height:20px;word-break:break-all}
.status.ok{color:#4ecca3}
.status.err{color:#e94560}
</style>
</head>
<body>
<div class="card">
<h2>NAS Music TV</h2>
<p class="sub">填写服务器信息，提交后自动填入电视端（需在电视上确认连接）</p>
<label>后端类型</label>
<select id="backendType">
  <option value="jellyfin">Jellyfin</option>
  <option value="navidrome">Navidrome</option>
  <option value="subsonic">Subsonic</option>
  <option value="daoliyu">道理鱼音乐</option>
  <option value="feiniu">飞牛音乐</option>
</select>
<label>服务器地址（http:// 或 https:// 开头）</label>
<input type="url" id="baseUrl" placeholder="http://192.168.1.10:8096">
<label>用户名</label>
<input type="text" id="username" autocomplete="off">
<label>密码</label>
<input type="password" id="password" autocomplete="off">
<label>显示名（可选，用于区分多个服务器）</label>
<input type="text" id="displayName" placeholder="例如：客厅 NAS">
<button id="submitBtn" onclick="submitConfig()">发送到电视</button>
<div class="status" id="status"></div>
</div>
<script>
function submitConfig(){
  var btn=document.getElementById('submitBtn');
  var st=document.getElementById('status');
  var payload={
    backendType:document.getElementById('backendType').value,
    baseUrl:document.getElementById('baseUrl').value.trim(),
    username:document.getElementById('username').value.trim(),
    password:document.getElementById('password').value,
    displayName:document.getElementById('displayName').value.trim()
  };
  if(!payload.baseUrl){show('请填写服务器地址','err');return;}
  btn.disabled=true;
  show('发送中...','');
  fetch('/api/config',{method:'POST',body:JSON.stringify(payload)})
    .then(function(r){return r.json()})
    .then(function(d){
      show(d.message||'完成', d.ok?'ok':'err');
      if(d.ok){document.getElementById('password').value='';}
    })
    .catch(function(e){show('发送失败: '+e.message,'err');})
    .finally(function(){btn.disabled=false;});
}
function show(msg,cls){
  var el=document.getElementById('status');
  el.textContent=msg;
  el.className='status'+(cls?' '+cls:'');
}
</script>
</body>
</html>
"""
    }
}