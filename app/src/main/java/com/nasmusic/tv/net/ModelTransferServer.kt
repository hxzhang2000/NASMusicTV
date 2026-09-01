package com.nasmusic.tv.net

import android.content.Context
import com.nasmusic.tv.R
import com.nasmusic.tv.util.AppLog
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 模型文件传输服务器
 *
 * 提供 HTTP 接口，允许手机扫码后通过浏览器上传模型文件到 TV。
 * 架构与 [BackupTransferServer] 一致：NanoHTTPD 单线程，端口 18082。
 *
 * 端点：
 * - GET  /         → 上传页面 HTML
 * - POST /api/upload → 接收模型文件（multipart/form-data，流式解析避免大文件 OOM）
 * - GET  /api/status → 返回模型文件状态（JSON）
 */
class ModelTransferServer(
    private val context: Context,
    private val modelFile: File,
    private val onModelUploaded: () -> Unit
) : NanoHTTPD(18082) {

    companion object {
        private const val TAG = "ModelTransferServer"
        private const val MIN_SIZE_BYTES = 50L * 1024 * 1024 // 50MB 最低阈值
        private const val MODEL_FILENAME = "htdemucs_ft_vocals.onnx"

        /**
         * 获取模型文件路径，与 ModelDownloadManager 保持一致。
         * 优先用外部存储；若不可用则回退到内部存储。
         */
        fun getModelFile(context: Context): File {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val modelsDir = File(baseDir, "models").apply { if (!exists()) mkdirs() }
            return File(modelsDir, MODEL_FILENAME)
        }

        fun create(context: Context, onModelUploaded: () -> Unit): ModelTransferServer {
            return ModelTransferServer(context, getModelFile(context), onModelUploaded)
        }
    }

    fun startServer(): Boolean {
        return try {
            start(SOCKET_READ_TIMEOUT, false)
            AppLog.i(TAG, "Started on port 18082")
            true
        } catch (e: IOException) {
            AppLog.e(TAG, "Failed to start on port 18082", e)
            false
        }
    }

    fun stopServer() {
        try {
            stop()
            AppLog.i(TAG, "Stopped")
        } catch (e: Exception) {
            AppLog.w(TAG, "Error stopping", e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return when {
            uri == "/" && method == Method.GET -> servePage()
            uri == "/api/upload" && method == Method.POST -> handleUpload(session)
            uri == "/api/status" && method == Method.GET -> handleStatus()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun servePage(): Response {
        val exists = modelFile.exists()
        val sizeMB = if (exists) "%.1f".format(modelFile.length() / (1024.0 * 1024.0)) else "0"
        val page = buildModelTransferPageHtml(context)
            .replace("{{MODEL_EXISTS}}", exists.toString())
            .replace("{{MODEL_SIZE}}", sizeMB)
            .replace("{{MODEL_PATH}}", modelFile.absolutePath)
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", page)
    }

    private fun handleStatus(): Response {
        val exists = modelFile.exists()
        val json = """{"exists":$exists,"size":${modelFile.length()},"path":"${modelFile.absolutePath}"}"""
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", json)
    }

    /**
     * 流式解析 multipart/form-data，绕过 NanoHTTPD parseBody() 的大文件限制。
     * 直接从 InputStream 读取 boundary，将文件内容流式写入目标文件。
     */
    private fun handleUpload(session: IHTTPSession): Response {
        // 1. 从 Content-Type 提取 boundary
        val contentType = session.headers["content-type"] ?: ""
        AppLog.d(TAG, "handleUpload: content-type=$contentType, headers=${session.headers.keys}")
        val boundaryMatch = Regex("boundary=([^;]+)").find(contentType)
        if (boundaryMatch == null) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json; charset=UTF-8",
                """{"ok":false,"message":"缺少 multipart boundary，content-type=$contentType"}"""
            )
        }
        val boundaryStr = boundaryMatch.groupValues[1].trim().trim('"')
        // body 格式：--boundary\r\n<headers>\r\n\r\n<file content>\r\n--boundary--
        // 用 "\r\n--boundary" 作为文件结束标记（boundary 前缀的 \r\n 不属于文件内容）
        val endMarker = "\r\n--$boundaryStr".toByteArray()
        val firstBoundary = "--$boundaryStr\r\n".toByteArray()
        AppLog.d(TAG, "handleUpload: boundary=$boundaryStr, endMarker size=${endMarker.size}, firstBoundary size=${firstBoundary.size}")

        // 2. 流式解析
        val target = modelFile
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()

        try {
            val inputStream = BufferedInputStream(session.inputStream, 256 * 1024)

            // 跳过第一个 boundary 之前的 preamble
            skipToBoundary(inputStream, firstBoundary)
            AppLog.d(TAG, "handleUpload: first boundary skipped")

            // 读取 part headers 直到空行
            val partHeaders = readPartHeaders(inputStream)
            AppLog.d(TAG, "handleUpload: part headers = $partHeaders")

            // 3. 流式写入文件直到遇到 endMarker
            val fileSize = streamToFile(inputStream, target, endMarker)
            AppLog.d(TAG, "handleUpload: file written, size=${fileSize / (1024 * 1024)}MB, target.exists=${target.exists()}, target.size=${target.length()}")

            if (fileSize < MIN_SIZE_BYTES) {
                target.delete()
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json; charset=UTF-8",
                    """{"ok":false,"message":"文件太小（${fileSize / (1024 * 1024)}MB，预期约166MB），请确认上传的是 htdemucs_ft_vocals_fp16weights.onnx"}"""
                )
            }

            AppLog.i(TAG, "Upload saved: ${target.absolutePath} (${fileSize / (1024 * 1024)}MB)")
            onModelUploaded.invoke()
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=UTF-8",
                """{"ok":true,"message":"模型上传成功！文件已保存（${fileSize / (1024 * 1024)}MB）"}"""
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "handleUpload failed", e)
            target.delete()
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json; charset=UTF-8",
                """{"ok":false,"message":"上传失败：${e.javaClass.simpleName}: ${e.message?.take(200) ?: "未知错误"}"}"""
            )
        }
    }

    /** 跳过输入流直到遇到 boundary 标记（boundary 本身被消费掉） */
    private fun skipToBoundary(input: BufferedInputStream, boundary: ByteArray) {
        var matched = 0
        while (true) {
            val b = input.read()
            if (b == -1) {
                AppLog.w(TAG, "skipToBoundary: EOF before boundary, matched=$matched/${boundary.size}")
                break
            }
            if (b == boundary[matched].toInt()) {
                matched++
                if (matched == boundary.size) return
            } else {
                matched = 0
            }
        }
    }

    /** 读取 part headers 直到空行（CRLF CRLF 中的最后一个空行），返回 header 内容 */
    private fun readPartHeaders(input: BufferedInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            sb.appendLine(line)
        }
        return sb.toString()
    }

    /** 读一行（\r\n 分隔） */
    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isNotEmpty()) sb.toString() else null
            if (b == '\r'.code) {
                val next = input.read()
                if (next == '\n'.code) return sb.toString()
                sb.append(b.toChar())
                if (next != -1) sb.append(next.toChar())
            } else {
                sb.append(b.toChar())
            }
        }
    }

    /**
     * 流式将数据写入文件，直到遇到 boundary 标记。
     *
     * 算法：KMP 思路 + 批量写入。
     * 维护 matched（已匹配字节数）和 pending 缓冲（可能匹配 boundary 的字节）。
     * - 匹配中：字节存到 pending，不写文件
     * - 匹配失败：pending 批量 flush 到文件
     * - 完全匹配：返回（pending 即 boundary，不写入）
     *
     * 时间复杂度 O(n)，每字节只 1 次比较；写入用批量 write(buf, off, len) 减少系统调用。
     */
    private fun streamToFile(input: BufferedInputStream, target: File, boundary: ByteArray): Long {
        FileOutputStream(target).use { output ->
            val bLen = boundary.size
            var totalWritten = 0L
            var totalRead = 0L
            var matched = 0
            val pending = ByteArray(bLen)
            var pendingLen = 0
            // 批量写入缓冲，减少 FileOutputStream.write(int) 系统调用
            val writeBuf = java.io.ByteArrayOutputStream(128 * 1024)
            val buf = ByteArray(128 * 1024)

            while (true) {
                val n = input.read(buf)
                if (n == -1) {
                    AppLog.w(TAG, "streamToFile: EOF, totalRead=$totalRead, written=$totalWritten, matched=$matched, no boundary")
                    break
                }
                totalRead += n

                for (i in 0 until n) {
                    val b = buf[i]
                    if (b == boundary[matched]) {
                        // 继续匹配，存到 pending
                        pending[pendingLen++] = b
                        matched++
                        if (matched == bLen) {
                            // 完全匹配 boundary！pending 全是 boundary，不写入
                            writeBuf.writeTo(output)
                            totalWritten += writeBuf.size()
                            AppLog.i(TAG, "streamToFile: boundary found, totalRead=$totalRead, written=$totalWritten")
                            return totalWritten
                        }
                    } else {
                        // 匹配失败，flush pending + 当前字节
                        if (pendingLen > 0) {
                            writeBuf.write(pending, 0, pendingLen)
                            pendingLen = 0
                        }
                        writeBuf.write(b.toInt())
                        matched = 0
                        // 定期 flush，避免 writeBuf 过大
                        if (writeBuf.size() >= 64 * 1024) {
                            writeBuf.writeTo(output)
                            totalWritten += writeBuf.size()
                            writeBuf.reset()
                        }
                    }
                }
            }
            // EOF，flush 剩余
            writeBuf.writeTo(output)
            totalWritten += writeBuf.size()
            output.write(pending, 0, pendingLen)
            totalWritten += pendingLen
            return totalWritten
        }
    }
}

/** 模型上传 HTML 页面 — 动态生成，所有 UI 字符串走 context.getString() */
private fun buildModelTransferPageHtml(context: Context): String {
    // Escape single quotes for JS string injection
    fun esc(s: String): String = s.replace("'", "\\'")

    return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>${context.getString(R.string.html_model_title)}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:#1a1a2e;color:#eee;min-height:100vh;padding:16px}
.card{background:#16213e;border-radius:16px;padding:24px;max-width:640px;margin:0 auto;box-shadow:0 8px 32px rgba(0,0,0,.3)}
h2{text-align:center;margin-bottom:4px;font-size:20px}
.sub{text-align:center;color:#888;font-size:13px;margin-bottom:20px}
.section{margin-bottom:20px}
.section h3{font-size:15px;color:#e94560;margin-bottom:10px}
.info-box{background:#0a0a23;border-radius:10px;padding:14px;margin-bottom:12px;font-size:13px;line-height:1.6;color:#ccc}
.info-box code{color:#4ecca3;background:#0f3460;padding:2px 6px;border-radius:4px;font-size:12px;word-break:break-all}
.btn{width:100%;padding:14px;font-size:16px;background:#e94560;color:#fff;border:none;border-radius:10px;font-weight:600;cursor:pointer}
.btn:active{background:#c73e54}
.btn:disabled{background:#555;color:#999}
input[type=file]{width:100%;padding:10px;font-size:14px;background:#0a0a23;border:2px solid #0f3460;border-radius:8px;color:#eee;margin-bottom:4px}
.status{text-align:center;margin-top:12px;font-size:14px;min-height:20px}
.status.ok{color:#4ecca3}
.status.err{color:#e94560}
.status.info{color:#888}
.download-link{display:block;text-align:center;color:#4ecca3;text-decoration:underline;margin-top:12px;font-size:13px}
</style>
</head>
<body>
<div class="card">
<h2>NAS Music TV</h2>
<p class="sub">${context.getString(R.string.html_model_subtitle)}</p>

<div class="section">
<h3>${context.getString(R.string.html_model_section_info)}</h3>
<div class="info-box">
<div>${context.getString(R.string.html_model_filename)}<code>htdemucs_ft_vocals.onnx</code></div>
<div>${context.getString(R.string.html_model_expected_size)}</div>
<div>${context.getString(R.string.html_model_current_status)}<span id="modelStatus">{{MODEL_EXISTS}}</span></div>
<div>${context.getString(R.string.html_model_downloaded_size)}<span id="modelSize">{{MODEL_SIZE}}</span> MB</div>
<div style="margin-top:8px">${context.getString(R.string.html_model_storage_path)}</div>
<code>{{MODEL_PATH}}</code>
</div>
</div>

<div class="section">
<h3>${context.getString(R.string.html_model_section_upload)}</h3>
<div class="info-box">
<div>${context.getString(R.string.html_model_instruction_1)}</div>
<div>${context.getString(R.string.html_model_instruction_2).replace("htdemucs_ft_vocals.onnx", "<code>htdemucs_ft_vocals.onnx</code>")}</div>
<div>${context.getString(R.string.html_model_instruction_3)}</div>
<div style="margin-top:8px;color:#e94560">${context.getString(R.string.html_model_warning)}</div>
</div>

<a class="download-link" href="https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx/resolve/main/htdemucs_ft_vocals_fp16weights.onnx" target="_blank">
${context.getString(R.string.html_model_download_link)}
</a>

<input type="file" id="fileInput" accept=".onnx,application/octet-stream">
<button class="btn" id="uploadBtn" onclick="uploadModel()" disabled>${context.getString(R.string.html_model_upload_btn)}</button>
</div>

<div id="status" class="status info">${context.getString(R.string.html_model_status_please_select)}</div>
</div>

<script>
var STR = {
  statusExist: '${esc(context.getString(R.string.html_model_status_exist))}',
  statusNotExist: '${esc(context.getString(R.string.html_model_status_not_exist))}',
  uploading: '${esc(context.getString(R.string.html_model_status_uploading))}',
  pleaseSelect: '${esc(context.getString(R.string.html_model_status_please_select))}',
  networkError: '${esc(context.getString(R.string.html_model_status_network_error))}',
  timeout: '${esc(context.getString(R.string.html_model_status_timeout))}',
  uploadBtn: '${esc(context.getString(R.string.html_model_upload_btn))}'
};
var fileInput=document.getElementById('fileInput');
var uploadBtn=document.getElementById('uploadBtn');
fileInput.addEventListener('change',function(){
  uploadBtn.disabled=!fileInput.files||!fileInput.files[0];
  if(fileInput.files&&fileInput.files[0]){
    var f=fileInput.files[0];
    showStatus(STR.pleaseSelect+': '+f.name+' ('+(f.size/(1024*1024)).toFixed(1)+'MB)','info');
  }
});

function uploadModel(){
  if(!fileInput.files||!fileInput.files[0])return;
  var file=fileInput.files[0];
  uploadBtn.disabled=true;
  uploadBtn.textContent=STR.uploading;
  showStatus(STR.uploading+' '+file.name+' ('+(file.size/(1024*1024)).toFixed(1)+'MB)','info');

  var formData=new FormData();
  formData.append('file',file,file.name);

  var xhr=new XMLHttpRequest();
  xhr.open('POST','/api/upload',true);
  xhr.timeout=300000; // 5分钟超时

  xhr.upload.onprogress=function(e){
    if(e.lengthComputable){
      var pct=Math.round(e.loaded/e.total*100);
      showStatus(STR.uploading+'...'+pct+'% ('+(e.loaded/(1024*1024)).toFixed(1)+'/'+(e.total/(1024*1024)).toFixed(1)+'MB)','info');
    }
  };

  xhr.onload=function(){
    var body=xhr.responseText||'';
    var msg='';
    try{
      var d=JSON.parse(body);
      msg=d.message||body.substring(0,300);
    }catch(e){
      msg=body.substring(0,300);
    }
    if(xhr.status===200){
      var ok=false;
      try{var d=JSON.parse(body);ok=d.ok;}catch(e){}
      showStatus(msg,ok?'ok':'err');
      if(ok){
        fileInput.value='';
        refreshStatus();
      }
    }else{
      showStatus('HTTP '+xhr.status+': '+msg,'err');
    }
    uploadBtn.disabled=false;
    uploadBtn.textContent=STR.uploadBtn;
  };

  xhr.onerror=function(){
    showStatus(STR.networkError,'err');
    uploadBtn.disabled=false;
    uploadBtn.textContent=STR.uploadBtn;
  };

  xhr.ontimeout=function(){
    showStatus(STR.timeout,'err');
    uploadBtn.disabled=false;
    uploadBtn.textContent=STR.uploadBtn;
  };

  xhr.send(formData);
}

function refreshStatus(){
  fetch('/api/status')
    .then(function(r){return r.json()})
    .then(function(d){
      document.getElementById('modelStatus').textContent=d.exists?STR.statusExist:STR.statusNotExist;
      document.getElementById('modelSize').textContent=(d.size/(1024*1024)).toFixed(1);
    })
    .catch(function(){});
}

function showStatus(msg,cls){
  var el=document.getElementById('status');
  el.textContent=msg;
  el.className='status '+cls;
}
</script>
</body>
</html>
"""
}
