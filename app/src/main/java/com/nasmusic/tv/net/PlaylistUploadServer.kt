package com.nasmusic.tv.net

import android.content.Context
import com.google.gson.Gson
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.playlist.PlaylistParsers
import com.nasmusic.tv.util.AppLog
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * 歌单扫码上传服务器
 *
 * TV 上启动 HTTP server，手机扫码后浏览器打开歌单上传页：
 * - 选择歌单文件（.m3u/.m3u8/.txt/.json/网易云导出）
 * - 上传到 TV（raw body + 文件名 query，同 BackupTransferServer 上传协议）
 * - 回调 [onFileReceived] 交给导入编排（PlaylistImporter.importBytes）处理
 *
 * 生命周期：由 PlaylistImportUploadDialog 打开时 [start]，关闭时 [stop]。
 *
 * @param onFileReceived 收到文件的回调（NanoHTTPD 工作线程同步调用）。
 *   返回非空字符串 = 导入成功消息（透传给手机端页面显示）；
 *   返回 null = 导入失败（手机端显示通用失败文案）。
 */
class PlaylistUploadServer(
    private val context: Context,
    private val onFileReceived: (fileName: String, bytes: ByteArray) -> String?,
    private val port: Int = DEFAULT_PORT
) {

    companion object {
        /**
         * 端口段约定：18080 LocalInputServer / 18081 BackupTransferServer /
         * 18082 RemoteControlServer / 18083 ModelTransferServer / 18084 PlaylistUploadServer
         */
        const val DEFAULT_PORT = 18084
        private const val TAG = "PlaylistUploadServer"

        /**
         * 上传体量上限，与 [PlaylistParsers.MAX_FILE_BYTES]（5MB）对齐。
         * 分块累积读取 + 硬上限截断，防同 LAN 谎报 Content-Length 导致 OOM（同 BackupTransferServer）。
         */
        const val MAX_UPLOAD_BYTES = PlaylistParsers.MAX_FILE_BYTES.toLong()
    }

    private var server: Impl? = null

    /** P1-1：一次性鉴权 token，随二维码 URL 下发（server 重启即更换） */
    private val authToken = LocalServerAuth.newToken()

    /** P1-1：带 token 的二维码 URL（手机浏览器打开后种 Cookie） */
    fun buildUrl(ip: String): String = "http://$ip:$port/?t=$authToken"

    fun start(): Boolean {
        if (server != null) return true
        val impl = Impl(context, onFileReceived, port, authToken)
        return try {
            impl.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = impl
            AppLog.i(TAG, "started on port $port")
            true
        } catch (e: IOException) {
            AppLog.e(TAG, "start failed on port $port", e)
            false
        }
    }

    fun stop() {
        server?.let {
            try {
                it.stop()
                AppLog.i(TAG, "stopped")
            } catch (e: Exception) {
                AppLog.w(TAG, "stop error", e)
            }
        }
        server = null
    }

    // ---- NanoHTTPD 实现 ----

    private class Impl(
        private val context: Context,
        private val onFileReceived: (fileName: String, bytes: ByteArray) -> String?,
        port: Int,
        private val authToken: String
    ) : NanoHTTPD(port) {

        private val gson = Gson()

        override fun serve(session: IHTTPSession): Response {
            // P1-1：一次性 token 鉴权（query t= 或 Cookie auth=），拒绝同网段未授权访问
            if (!LocalServerAuth.isAuthorized(session, authToken)) return LocalServerAuth.forbidden()
            return when {
                session.uri == "/" && session.method == Method.GET -> servePage()
                session.uri == "/api/upload" && session.method == Method.POST -> handleUpload(session)
                else -> {
                    AppLog.w(TAG, "404 for ${session.method} ${session.uri}")
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
                }
            }
        }

        private fun servePage(): Response {
            val response = newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=UTF-8",
                buildUploadPageHtml(context)
            )
            response.addHeader("Set-Cookie", LocalServerAuth.cookieHeader(authToken))
            return response
        }

        private fun buildUploadPageHtml(context: Context): String {
            // JS 文案用 Gson 生成 STR JSON（自动转义引号/换行，避免破坏单引号字符串）
            val strMap = mapOf(
                "uploading" to context.getString(R.string.html_playlist_status_uploading),
                "uploadFailed" to context.getString(R.string.html_playlist_status_upload_failed),
                "importing" to context.getString(R.string.html_playlist_status_importing)
            )
            val strJson = gson.toJson(strMap)
            return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>${context.getString(R.string.html_playlist_title)}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:#1a1a2e;color:#eee;min-height:100vh;padding:16px}
.card{background:#16213e;border-radius:16px;padding:24px;max-width:640px;margin:0 auto;box-shadow:0 8px 32px rgba(0,0,0,.3)}
h2{text-align:center;margin-bottom:4px;font-size:20px}
.sub{text-align:center;color:#888;font-size:13px;margin-bottom:20px}
.section{margin-bottom:20px}
.section h3{font-size:15px;color:#4ecca3;margin-bottom:10px}
.hint{color:#888;font-size:12px;margin-bottom:10px}
input[type=file]{width:100%;padding:10px;font-size:14px;background:#0a0a23;border:2px solid #0f3460;border-radius:8px;color:#eee;margin-bottom:8px}
.btn-upload{width:100%;padding:14px;font-size:16px;background:#4ecca3;color:#0a0a23;border:none;border-radius:10px;font-weight:600;cursor:pointer;margin-top:10px}
.btn-upload:active{background:#3db890}
.btn-upload:disabled{background:#555;color:#999}
.status{text-align:center;margin-top:12px;font-size:14px;min-height:20px;word-break:break-all}
.status.ok{color:#4ecca3}
.status.err{color:#e94560}
</style>
</head>
<body>
<div class="card">
<h2>${context.getString(R.string.html_playlist_title)}</h2>
<p class="sub">${context.getString(R.string.html_playlist_subtitle)}</p>

<div class="section">
<h3>${context.getString(R.string.html_playlist_section_upload)}</h3>
<div class="hint">${context.getString(R.string.html_playlist_accept_hint)}</div>
<input type="file" id="fileInput" accept=".m3u,.m3u8,.txt,.json,audio/x-mpegurl,text/plain,application/json">
<button class="btn-upload" id="uploadBtn" onclick="uploadPlaylist()">${context.getString(R.string.html_playlist_upload_btn)}</button>
</div>

<div id="status" class="status"></div>
</div>

<script>
var STR = $strJson;
function uploadPlaylist(){
  var input=document.getElementById('fileInput');
  var btn=document.getElementById('uploadBtn');
  if(!input.files||input.files.length===0){
    showStatus(STR.uploadFailed,'err');
    return;
  }
  var file=input.files[0];
  btn.disabled=true;
  btn.textContent=STR.uploading;
  showStatus(STR.uploading.replace('%s',file.name),'');
  var reader=new FileReader();
  reader.onload=function(){
    var blob=new Blob([reader.result]);
    fetch('/api/upload?name='+encodeURIComponent(file.name),{method:'POST',body:blob})
      .then(function(r){return r.json()})
      .then(function(d){
        if(d.ok){
          showStatus(d.message,'ok');
          if(d.importing) setTimeout(function(){showStatus(STR.importing,'');},400);
        } else {
          showStatus(d.message||STR.uploadFailed,'err');
        }
      })
      .catch(function(e){
        showStatus(STR.uploadFailed+': '+e.message,'err');
      })
      .finally(function(){
        btn.disabled=false;
        btn.textContent=STR.uploadBtn;
      });
  };
  reader.onerror=function(){
    showStatus(STR.uploadFailed+': reader error','err');
    btn.disabled=false;
    btn.textContent=STR.uploadBtn;
  };
  reader.readAsArrayBuffer(file);
}

function showStatus(msg,type){
  var s=document.getElementById('status');
  s.textContent=msg;
  s.className='status'+(type?' '+type:'');
}
</script>
</body>
</html>
""".trimIndent()
        }

        /**
         * 上传歌单文件（raw body = 文件字节，文件名来自 query `name`）。
         * 读取方式：按 Content-Length 精确读满即止——不能 `read 到 -1`
         * （keep-alive 连接上读完 body 后 read 会阻塞至 SO_TIMEOUT，恒抛
         * SocketTimeoutException，2026-09-18 实测）。不按 Content-Length
         * 预分配数组（可谎报 → OOM），分块累积 + [MAX_UPLOAD_BYTES] 硬上限。
         */
        private fun handleUpload(session: IHTTPSession): Response {
            return try {
                val fileName = session.parameters["name"]?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "playlist.txt"
                val contentLength = session.headers["content-length"]?.toLongOrNull() ?: -1L
                if (contentLength <= 0L) {
                    // fetch(Blob) 必然带 content-length；缺失（chunked）无法安全定长读取
                    return jsonResponse(false, context.getString(R.string.html_playlist_empty))
                }
                if (contentLength > MAX_UPLOAD_BYTES) {
                    return jsonResponse(false, context.getString(R.string.html_playlist_too_large))
                }
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var remaining = contentLength
                while (remaining > 0) {
                    val read = session.inputStream.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
                    if (read < 0) break // 对端提前关闭
                    buffer.write(chunk, 0, read)
                    remaining -= read
                }
                if (buffer.size() == 0) {
                    return jsonResponse(false, context.getString(R.string.html_playlist_empty))
                }
                AppLog.i(TAG, "upload received: $fileName (${buffer.size()} bytes)")
                val message = onFileReceived.invoke(fileName, buffer.toByteArray())
                if (message != null) {
                    jsonResponse(true, message)
                } else {
                    jsonResponse(false, context.getString(R.string.html_playlist_import_failed))
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "upload failed", e)
                jsonResponse(
                    false,
                    context.getString(R.string.html_playlist_import_failed) + ": " + (e.message ?: e.javaClass.simpleName)
                )
            }
        }

        private fun jsonResponse(ok: Boolean, message: String): Response {
            val json = gson.toJson(mapOf("ok" to ok, "message" to message))
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=UTF-8",
                json
            )
        }
    }
}