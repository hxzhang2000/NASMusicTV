package com.nasmusic.tv.net

import android.content.Context
import com.google.gson.Gson
import com.nasmusic.tv.R
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.BackupFileUtils
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份传输服务器
 *
 * 在 TV 上启动 HTTP server，手机扫码后浏览器打开备份管理页面，支持：
 * - 查看 TV 端所有备份文件列表
 * - 下载备份到手机
 * - 上传手机上的备份文件到 TV
 * - 直接在手机上触发恢复
 *
 * 生命周期：由 BackupTransferDialog 在打开时 [start]，关闭时 [stop]。
 *
 * @param context 用于访问 BackupFileUtils
 * @param onRestore 恢复备份的回调，传入备份 JSON 内容，返回是否成功。
 *   非挂起类型：调用方负责在 NanoHTTPD 工作线程上同步桥接 suspend 逻辑
 *   （server 不依赖协程库，桥接职责集中在 ViewModel）。
 */
class BackupTransferServer(
    private val context: Context,
    private val onRestore: (String) -> Boolean,
    private val onBackupChanged: () -> Unit = {},
    private val port: Int = DEFAULT_PORT
) {

    companion object {
        const val DEFAULT_PORT = 18081
        private const val TAG = "BackupTransferServer"

        /**
         * 上传体量上限（32MB）。
         * 原实现按客户端 Content-Length 直接 `ByteArray(contentLength.toInt())`，
         * 同 LAN 攻击者谎报 1.5GB 即触发 OutOfMemoryError——该 Error 不被 catch(Exception) 捕获，
         * 直接崩进程（DoS）。
         */
        const val MAX_UPLOAD_BYTES = 32L * 1024 * 1024
    }

    private var server: Impl? = null

    fun start(): Boolean {
        AppLog.i(TAG, "start: called, server=$server")
        if (server != null) {
            AppLog.i(TAG, "start: already running, returning true")
            return true
        }
        val impl = Impl(context, onRestore, onBackupChanged, port)
        return try {
            impl.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = impl
            AppLog.i(TAG, "start: started on port $port")
            true
        } catch (e: IOException) {
            AppLog.e(TAG, "start: failed on port $port", e)
            false
        }
    }

    fun stop() {
        AppLog.i(TAG, "stop: called, server=$server")
        server?.let {
            try {
                it.stop()
                AppLog.i(TAG, "stop: stopped")
            } catch (e: Exception) {
                AppLog.w(TAG, "stop: error", e)
            }
        }
        server = null
    }

    // ---- NanoHTTPD 实现 ----

    private class Impl(
        private val context: Context,
        private val onRestore: (String) -> Boolean,
        private val onBackupChanged: () -> Unit,
        port: Int
    ) : NanoHTTPD(port) {

        private val gson = Gson()
        private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        override fun serve(session: IHTTPSession): Response {
            AppLog.i(TAG, "serve: ${session.method} ${session.uri}")
            return when {
                session.uri == "/" && session.method == Method.GET -> servePage()
                session.uri == "/api/list" && session.method == Method.GET -> handleList()
                session.uri.startsWith("/api/download") && session.method == Method.GET -> handleDownload(session)
                session.uri == "/api/upload" && session.method == Method.POST -> handleUpload(session)
                session.uri.startsWith("/api/restore") && session.method == Method.POST -> handleRestore(session)
                else -> {
                    AppLog.w(TAG, "serve: 404 for ${session.method} ${session.uri}")
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
                }
            }
        }

        private fun servePage(): Response {
            return newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=UTF-8",
                buildBackupPageHtml(context)
            )
        }

        private fun buildBackupPageHtml(context: Context): String {
            // 用 Gson 生成 STR JSON，自动转义换行符/引号等特殊字符
            // 避免 Android strings.xml 中的 \n 被解析为真实换行后破坏 JS 单引号字符串
            val strMap = mapOf(
                "empty" to context.getString(R.string.html_backup_empty),
                "loadError" to context.getString(R.string.html_backup_load_error),
                "downloading" to context.getString(R.string.html_backup_status_downloading),
                "downloadStarted" to context.getString(R.string.html_backup_status_download_started),
                "confirmRestore" to context.getString(R.string.html_backup_confirm_restore),
                "restoring" to context.getString(R.string.html_backup_status_restoring),
                "restoreFailed" to context.getString(R.string.html_backup_status_restore_failed),
                "uploading" to context.getString(R.string.html_backup_uploading),
                "uploadFailed" to context.getString(R.string.html_backup_status_upload_failed),
                "uploadBtn" to context.getString(R.string.html_backup_upload_btn)
            )
            val strJson = gson.toJson(strMap)
            return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>${context.getString(R.string.html_backup_title)}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:#1a1a2e;color:#eee;min-height:100vh;padding:16px}
.card{background:#16213e;border-radius:16px;padding:24px;max-width:640px;margin:0 auto;box-shadow:0 8px 32px rgba(0,0,0,.3)}
h2{text-align:center;margin-bottom:4px;font-size:20px}
.sub{text-align:center;color:#888;font-size:13px;margin-bottom:20px}
.section{margin-bottom:20px}
.section h3{font-size:15px;color:#e94560;margin-bottom:10px}
.backup-item{background:#0a0a23;border-radius:10px;padding:12px 14px;margin-bottom:8px;display:flex;flex-direction:column;gap:4px}
.backup-name{font-size:13px;word-break:break-all;color:#eee}
.backup-time{font-size:12px;color:#666}
.backup-actions{display:flex;gap:8px;margin-top:6px}
.btn{padding:8px 16px;font-size:13px;border:none;border-radius:8px;cursor:pointer;font-weight:600}
.btn-download{background:#0f3460;color:#eee}
.btn-download:active{background:#1a4a80}
.btn-restore{background:#e94560;color:#fff}
.btn-restore:active{background:#c73e54}
.btn-upload{width:100%;padding:14px;font-size:16px;background:#e94560;color:#fff;border:none;border-radius:10px;font-weight:600;cursor:pointer;margin-top:10px}
.btn-upload:active{background:#c73e54}
.btn-upload:disabled{background:#555;color:#999}
input[type=file]{width:100%;padding:10px;font-size:14px;background:#0a0a23;border:2px solid #0f3460;border-radius:8px;color:#eee;margin-bottom:4px}
.status{text-align:center;margin-top:12px;font-size:14px;min-height:20px}
.status.ok{color:#4ecca3}
.status.err{color:#e94560}
.empty{text-align:center;color:#555;font-size:14px;padding:20px}
.loading{text-align:center;color:#666;font-size:14px;padding:20px}
</style>
</head>
<body>
<div class="card">
<h2>NAS Music TV</h2>
<p class="sub">${context.getString(R.string.html_backup_subtitle)}</p>

<div class="section">
<h3>${context.getString(R.string.html_backup_section_files)}</h3>
<div id="backupList" class="loading">${context.getString(R.string.html_backup_loading)}</div>
</div>

<div class="section">
<h3>${context.getString(R.string.html_backup_section_upload)}</h3>
<input type="file" id="fileInput" accept=".json,application/json">
<button class="btn-upload" id="uploadBtn" onclick="uploadBackup()">${context.getString(R.string.html_backup_upload_btn)}</button>
</div>

<div id="status" class="status"></div>
</div>

<script>
var STR = $strJson;
function loadBackups(){
  fetch('/api/list')
    .then(function(r){return r.json()})
    .then(function(d){
      var list=document.getElementById('backupList');
      if(!d.backups||d.backups.length===0){
        list.innerHTML='<div class="empty">'+STR.empty+'</div>';
        return;
      }
      list.innerHTML=d.backups.map(function(b){
        return '<div class="backup-item">'+
          '<div class="backup-name">'+b.name+'</div>'+
          '<div class="backup-time">'+b.time+'</div>'+
          '<div class="backup-actions">'+
            '<button class="btn btn-download" onclick="downloadBackup(\''+b.name+'\')">${context.getString(R.string.html_backup_download)}</button>'+
            '<button class="btn btn-restore" onclick="restoreBackup(\''+b.name+'\')">${context.getString(R.string.html_backup_restore)}</button>'+
          '</div>'+
        '</div>';
      }).join('');
    })
    .catch(function(e){
      document.getElementById('backupList').innerHTML='<div class="empty">'+STR.loadError+'</div>';
    });
}

function downloadBackup(name){
  showStatus(STR.downloading,'');
  window.location='/api/download?name='+encodeURIComponent(name);
  setTimeout(function(){showStatus(STR.downloadStarted,'ok');},1000);
}

function restoreBackup(name){
  if(!confirm(STR.confirmRestore.replace('%s',name)))return;
  showStatus(STR.restoring,'');
  fetch('/api/restore?name='+encodeURIComponent(name),{method:'POST'})
    .then(function(r){return r.json()})
    .then(function(d){
      showStatus(d.message, d.ok?'ok':'err');
      if(d.ok) setTimeout(loadBackups,2000);
    })
    .catch(function(e){showStatus(STR.restoreFailed,'err');});
}

function uploadBackup(){
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
    var blob=new Blob([reader.result],{type:'application/json'});
    fetch('/api/upload',{method:'POST',body:blob})
      .then(function(r){return r.json()})
      .then(function(d){
        showStatus(d.message, d.ok?'ok':'err');
        if(d.ok){
          input.value='';
          loadBackups();
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

loadBackups();
</script>
</body>
</html>
""".trimIndent()
        }

        /** 列出 TV 端备份文件 */
        private fun handleList(): Response {
            return try {
                val backups = BackupFileUtils.listBackups(context)
                val items = backups.map { bf ->
                    mapOf(
                        "name" to bf.displayName,
                        "time" to timeFormat.format(Date(bf.lastModified))
                    )
                }
                val json = gson.toJson(mapOf("backups" to items))
                newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", json)
            } catch (e: Exception) {
                AppLog.e(TAG, "handleList failed", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json; charset=UTF-8",
                    """{"backups":[]}"""
                )
            }
        }

        /** 下载指定备份文件 */
        private fun handleDownload(session: IHTTPSession): Response {
            return try {
                val name = getQueryParam(session, "name") ?: return notFound("missing name")
                val backups = BackupFileUtils.listBackups(context)
                val target = backups.find { it.displayName == name }
                    ?: return notFound("backup not found: $name")

                val result = BackupFileUtils.read(context, target.uri)
                val json = result.getOrElse { e ->
                    return newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "text/plain; charset=UTF-8",
                        "读取失败: ${e.message}"
                    )
                }

                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json; charset=UTF-8",
                    json
                ).apply {
                    // 触发浏览器下载
                    addHeader("Content-Disposition", "attachment; filename=\"$name\"")
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "handleDownload failed", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain; charset=UTF-8",
                    "下载失败: ${e.message}"
                )
            }
        }

        /** 上传备份文件（raw body = JSON 内容） */
        private fun handleUpload(session: IHTTPSession): Response {
            AppLog.i(TAG, "handleUpload: called, method=${session.method}, uri=${session.uri}")
            return try {
                // 直接从 inputStream 读取原始字节，用 UTF-8 解码
                // 不用 parseBody()：某些 ROM 上 Charset.defaultCharset() 非 UTF-8，导致中文乱码
                val contentLength = session.headers["content-length"]?.toLongOrNull() ?: -1L
                AppLog.i(TAG, "handleUpload: contentLength=$contentLength")
                if (contentLength == 0L) {
                    return jsonResponse(false, "上传内容为空")
                }
                if (contentLength > MAX_UPLOAD_BYTES) {
                    AppLog.w(TAG, "handleUpload: rejected, contentLength=$contentLength > $MAX_UPLOAD_BYTES")
                    return jsonResponse(false, "上传内容过大（上限 ${MAX_UPLOAD_BYTES / 1024 / 1024}MB）")
                }
                // 安全：不按 Content-Length 预分配数组（客户端可谎报 → 巨型 ByteArray 直接 OOM），
                // 改为有硬上限的分块累积读取；超出上限立即中止
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var totalRead = 0
                while (true) {
                    val read = session.inputStream.read(chunk)
                    if (read < 0) break
                    totalRead += read
                    if (totalRead > MAX_UPLOAD_BYTES) {
                        AppLog.w(TAG, "handleUpload: aborted, body exceeded $MAX_UPLOAD_BYTES bytes")
                        return jsonResponse(false, "上传内容过大（上限 ${MAX_UPLOAD_BYTES / 1024 / 1024}MB）")
                    }
                    buffer.write(chunk, 0, read)
                }
                if (totalRead == 0) {
                    return jsonResponse(false, "上传内容为空")
                }
                var json = String(buffer.toByteArray(), Charsets.UTF_8)
                // 去掉 UTF-8 BOM（如果有）
                if (json.startsWith("\uFEFF")) {
                    json = json.substring(1)
                }
                AppLog.i(TAG, "handleUpload: body length=${json.length}")
                if (json.isEmpty()) {
                    return jsonResponse(false, "上传内容为空")
                }
                val result = BackupFileUtils.export(context, json)
                if (result.isSuccess) {
                    val savedName = result.getOrThrow()
                    AppLog.i(TAG, "Upload saved: $savedName (${json.length} chars)")
                    onBackupChanged.invoke()
                    jsonResponse(true, "已保存: $savedName")
                } else {
                    jsonResponse(false, "保存失败: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "handleUpload failed", e)
                jsonResponse(false, "上传失败: ${e.message}")
            }
        }

        /** 恢复指定备份文件 */
        private fun handleRestore(session: IHTTPSession): Response {
            return try {
                val name = getQueryParam(session, "name") ?: return jsonResponse(false, "缺少 name 参数")
                val backups = BackupFileUtils.listBackups(context)
                val target = backups.find { it.displayName == name }
                    ?: return jsonResponse(false, "备份不存在: $name")

                val result = BackupFileUtils.read(context, target.uri)
                val json = result.getOrElse { e ->
                    return jsonResponse(false, "读取失败: ${e.message}")
                }

                // 调用恢复回调（非挂起；调用方在工作线程上同步桥接 suspend 逻辑）
                val ok = onRestore.invoke(json)
                if (ok) {
                    jsonResponse(true, "恢复成功")
                } else {
                    jsonResponse(false, "恢复失败，数据格式可能不兼容")
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "handleRestore failed", e)
                jsonResponse(false, "恢复失败: ${e.message}")
            }
        }

        private fun getQueryParam(session: IHTTPSession, key: String): String? {
            return session.parameters[key]?.firstOrNull()
        }

        private fun jsonResponse(ok: Boolean, message: String): Response {
            val json = gson.toJson(mapOf("ok" to ok, "message" to message))
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=UTF-8",
                json
            )
        }

        private fun notFound(msg: String): Response {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=UTF-8", msg)
        }
    }
}
