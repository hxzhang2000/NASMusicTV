package com.nasmusic.tv.net

import android.content.Context
import com.nasmusic.tv.util.AppLog
import fi.iki.elonen.NanoHTTPD
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
 * - POST /api/upload → 接收模型文件（multipart/form-data）
 * - GET  /api/status → 返回模型文件状态（JSON）
 */
class ModelTransferServer(
    private val context: Context,
    private val port: Int = DEFAULT_PORT,
    private val onModelUploaded: () -> Unit = {}
) {

    companion object {
        const val DEFAULT_PORT = 18082
        private const val TAG = "ModelTransferServer"
        private const val MODEL_FILENAME = "htdemucs_ft_vocals.onnx"
        private const val EXPECTED_SIZE_BYTES = 166_000_000L
    }

    private var server: Impl? = null

    fun start(): Boolean {
        if (server != null) return true
        val impl = Impl(context, onModelUploaded, port)
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

    /** 获取模型目录 */
    private fun getModelsDir(): File {
        val dir = File(context.getExternalFilesDir(null), "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ---- NanoHTTPD 实现 ----

    private class Impl(
        private val context: Context,
        private val onModelUploaded: () -> Unit,
        port: Int
    ) : NanoHTTPD(port) {

        private val modelsDir: File by lazy {
            File(context.getExternalFilesDir(null), "models").also { if (!it.exists()) it.mkdirs() }
        }
        private val modelFile: File get() = File(modelsDir, MODEL_FILENAME)

        override fun serve(session: IHTTPSession): Response {
            return when {
                session.uri == "/" && session.method == Method.GET -> servePage()
                session.uri == "/api/upload" && session.method == Method.POST -> handleUpload(session)
                session.uri == "/api/status" && session.method == Method.GET -> handleStatus()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }

        private fun servePage(): Response {
            val exists = modelFile.exists()
            val sizeMB = if (exists) "%.1f".format(modelFile.length() / (1024.0 * 1024.0)) else "0"
            val page = MODEL_PAGE_HTML
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

        private fun handleUpload(session: IHTTPSession): Response {
            return try {
                // 解析 multipart/form-data
                val files = HashMap<String, String>()
                session.parseBody(files)

                // NanoHTTPD 会将文件存为临时文件，key 为 "content" 或 "uploadedfile"
                val tmpFilePath = files["content"] ?: files["uploadedfile"]
                if (tmpFilePath == null) {
                    return newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        "application/json; charset=UTF-8",
                        """{"ok":false,"message":"未找到上传文件"}"""
                    )
                }

                val tmpFile = File(tmpFilePath)
                if (!tmpFile.exists()) {
                    return newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        "application/json; charset=UTF-8",
                        """{"ok":false,"message":"临时文件不存在"}"""
                    )
                }

                // 检查文件大小（允许 20% 误差）
                val fileSize = tmpFile.length()
                if (fileSize < EXPECTED_SIZE_BYTES * 0.8) {
                    tmpFile.delete()
                    return newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        "application/json; charset=UTF-8",
                        """{"ok":false,"message":"文件大小异常（${fileSize / (1024 * 1024)}MB，预期约166MB），请确认上传的是正确的模型文件"}"""
                    )
                }

                // 原子重命名到目标路径
                val target = modelFile
                if (target.exists()) target.delete()
                val renamed = tmpFile.renameTo(target)

                if (renamed) {
                    AppLog.i(TAG, "Upload saved: ${target.absolutePath} (${fileSize / (1024 * 1024)}MB)")
                    onModelUploaded.invoke()
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json; charset=UTF-8",
                        """{"ok":true,"message":"模型上传成功！文件已保存到 ${target.absolutePath}（${fileSize / (1024 * 1024)}MB）"}"""
                    )
                } else {
                    // 重命名失败，尝试复制
                    tmpFile.copyTo(target, overwrite = true)
                    tmpFile.delete()
                    if (target.exists() && target.length() == fileSize) {
                        AppLog.i(TAG, "Upload copied: ${target.absolutePath} (${fileSize / (1024 * 1024)}MB)")
                        onModelUploaded.invoke()
                        newFixedLengthResponse(
                            Response.Status.OK,
                            "application/json; charset=UTF-8",
                            """{"ok":true,"message":"模型上传成功！文件已保存到 ${target.absolutePath}（${fileSize / (1024 * 1024)}MB）"}"""
                        )
                    } else {
                        newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR,
                            "application/json; charset=UTF-8",
                            """{"ok":false,"message":"文件保存失败"}"""
                        )
                    }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "handleUpload failed", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json; charset=UTF-8",
                    """{"ok":false,"message":"上传失败：${e.message?.take(60) ?: "未知错误"}"}"""
                )
            }
        }
    }
}

/** 模型上传 HTML 页面 */
private val MODEL_PAGE_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>NAS Music TV - 模型上传</title>
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
<p class="sub">高质量分离模型上传</p>

<div class="section">
<h3>模型信息</h3>
<div class="info-box">
<div>文件名：<code>htdemucs_ft_vocals.onnx</code></div>
<div>预期大小：约 166MB（fp16weights 格式）</div>
<div>当前状态：<span id="modelStatus">{{MODEL_EXISTS}}</span></div>
<div>已下载大小：<span id="modelSize">{{MODEL_SIZE}}</span> MB</div>
<div style="margin-top:8px">存储路径：</div>
<code>{{MODEL_PATH}}</code>
</div>
</div>

<div class="section">
<h3>上传模型到电视</h3>
<div class="info-box">
<div>1. 在电脑/手机上下载模型文件（见下方链接）</div>
<div>2. 选择下载好的 <code>htdemucs_ft_vocals.onnx</code> 文件</div>
<div>3. 点击"上传到电视"按钮</div>
<div style="margin-top:8px;color:#e94560">注意：上传过程请勿关闭页面，约需 1-2 分钟</div>
</div>

<a class="download-link" href="https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx/resolve/main/htdemucs_ft_vocals_fp16weights.onnx" target="_blank">
下载模型文件（HuggingFace，166MB）
</a>

<input type="file" id="fileInput" accept=".onnx,application/octet-stream">
<button class="btn" id="uploadBtn" onclick="uploadModel()" disabled>上传到电视</button>
</div>

<div id="status" class="status info">请选择要上传的模型文件</div>
</div>

<script>
var fileInput=document.getElementById('fileInput');
var uploadBtn=document.getElementById('uploadBtn');
fileInput.addEventListener('change',function(){
  uploadBtn.disabled=!fileInput.files||!fileInput.files[0];
  if(fileInput.files&&fileInput.files[0]){
    var f=fileInput.files[0];
    showStatus('已选择：'+f.name+'（'+(f.size/(1024*1024)).toFixed(1)+'MB）','info');
  }
});

function uploadModel(){
  if(!fileInput.files||!fileInput.files[0])return;
  var file=fileInput.files[0];
  uploadBtn.disabled=true;
  uploadBtn.textContent='上传中...';
  showStatus('正在上传 '+file.name+'（'+(file.size/(1024*1024)).toFixed(1)+'MB）到电视...请勿关闭页面','info');

  var formData=new FormData();
  formData.append('file',file,file.name);

  var xhr=new XMLHttpRequest();
  xhr.open('POST','/api/upload',true);
  xhr.timeout=300000; // 5分钟超时

  xhr.upload.onprogress=function(e){
    if(e.lengthComputable){
      var pct=Math.round(e.loaded/e.total*100);
      showStatus('上传中...'+pct+'%（'+(e.loaded/(1024*1024)).toFixed(1)+'/'+(e.total/(1024*1024)).toFixed(1)+'MB）','info');
    }
  };

  xhr.onload=function(){
    if(xhr.status===200){
      try{
        var d=JSON.parse(xhr.responseText);
        showStatus(d.message,d.ok?'ok':'err');
        if(d.ok){
          fileInput.value='';
          refreshStatus();
        }
      }catch(e){
        showStatus('上传完成，但响应解析失败','err');
      }
    }else{
      showStatus('上传失败（HTTP '+xhr.status+'）','err');
    }
    uploadBtn.disabled=false;
    uploadBtn.textContent='上传到电视';
  };

  xhr.onerror=function(){
    showStatus('网络错误，上传失败','err');
    uploadBtn.disabled=false;
    uploadBtn.textContent='上传到电视';
  };

  xhr.ontimeout=function(){
    showStatus('上传超时，请检查网络连接','err');
    uploadBtn.disabled=false;
    uploadBtn.textContent='上传到电视';
  };

  xhr.send(formData);
}

function refreshStatus(){
  fetch('/api/status')
    .then(function(r){return r.json()})
    .then(function(d){
      document.getElementById('modelStatus').textContent=d.exists?'已下载':'未下载';
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
