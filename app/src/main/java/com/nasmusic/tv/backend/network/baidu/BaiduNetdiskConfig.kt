package com.nasmusic.tv.backend.network.baidu

import com.nasmusic.tv.data.model.CloudDriveType

/**
 * 百度网盘开放平台 API 集中常量表
 *
 * 百度网盘开放平台 API 无显式版本号（不像 `/v2/xxx` 带版本路径），接口会静默演进。
 * 所有端点、method、参数、category 码集中在此——API 变更时只改这一处。
 * 基线见 `docs/百度网盘音乐播放开发方案.md` §3.0。
 */
object BaiduNetdiskConfig {

    // ---- OAuth 端点 ----
    /** 设备码端点（请求设备码，此步无需 client_secret） */
    const val DEVICE_CODE_URL = "https://openapi.baidu.com/oauth/2.0/device/code"
    /** Token 端点（轮询换 token、刷新 token） */
    const val TOKEN_URL = "https://openapi.baidu.com/oauth/2.0/token"
    /** 用户授权验证页（用户用手机访问输入 user_code） */
    const val VERIFICATION_URL = "https://openapi.baidu.com/device"
    const val SCOPE = "basic,netdisk"
    /** 设备码有效期（秒） */
    const val DEVICE_CODE_EXPIRE_SEC = 300
    /** 默认轮询间隔（秒） */
    const val DEFAULT_POLL_INTERVAL_SEC = 5

    // ---- 文件接口 base（列表/搜索与元数据的端点不同，勿混用）----
    /** 列表 / 搜索 */
    const val FILE_BASE = "https://pan.baidu.com/rest/2.0/xpan/file"
    /** 元数据 / dlink */
    const val MULTIMEDIA_BASE = "https://pan.baidu.com/rest/2.0/xpan/multimedia"

    // ---- method ----
    const val METHOD_LIST = "list"
    const val METHOD_LISTALL = "listall"
    const val METHOD_SEARCH = "search"
    const val METHOD_FILEMETAS = "filemetas"

    // ---- 文件分类 category 代码 ----
    const val CATEGORY_VIDEO = 1
    const val CATEGORY_AUDIO = 2
    const val CATEGORY_IMAGE = 3
    const val CATEGORY_DOC = 4
    const val CATEGORY_APP = 5
    const val CATEGORY_BT = 7

    /** 单页 limit 上限 */
    const val PAGE_SIZE = 1000
    /** 搜索单页 num（BoxPlayer 用 500） */
    const val SEARCH_PAGE_SIZE = 500

    /**
     * API 字段指纹基线（SHA-256 十六进制，随 App 版本固化）。
     *
     * 上线前实测验证百度 API 探测端点（filemetas 或 uinfo）的响应字段结构后，
     * 调用 [com.nasmusic.tv.backend.network.baidu.ApiProbe.computeFieldFingerprint] 计算
     * 并回填此常量。空字符串 = 基线未固化（漂移检测暂不生效）。
     * 见 `docs/百度网盘音乐播放开发方案.md` §3.0。
     */
    const val API_PROBE_BASELINE = ""

    // ---- dlink 播放约束 ----
    /** dlink 请求必需的 User-Agent（>20MB 文件不加会被 403） */
    const val BAIDU_UA = "pan.baidu.com"
    /** 双保险 Referer */
    const val BAIDU_REFERER = "https://pan.baidu.com/"
    /** dlink 直链域名标记（用于 DataSource 拦截器判断是否注入百度请求头） */
    val DLINK_HOST_MARKERS = listOf("d.pcs.baidu.com", "pan.baidu.com", "dDownList")

    // ---- API 错误码映射表（errno）----
    val ERRNO_MAP: Map<Int, String> = mapOf(
        -1 to "未知错误",
        -6 to "access_token 失效（需重新授权）",
        -7 to "文件名或路径名非法",
        -9 to "文件不存在",
        -111 to "请求过于频繁（限流）",
        -118 to "带 dlink 参数的请求过于频繁",
        31034 to "命中接口频控",
        31045 to "用户未授权该 scope"
    )

    /** 网盘类型（首批仅百度） */
    val DRIVE_TYPE: CloudDriveType = CloudDriveType.BAIDU

    /** 歌曲唯一值前缀：ntwk_baidu_ */
    const val SONG_ID_PREFIX = "ntwk_baidu_"

    /** 网盘本地 MV 唯一标识前缀：ntwk_baidu_mv_ */
    const val MV_BVID_PREFIX = "ntwk_baidu_mv_"

    /** 构造歌曲 id：ntwk_baidu_${fs_id} */
    fun songId(fsId: Long): String = "$SONG_ID_PREFIX$fsId"

    /** 构造网盘 MV bvid：ntwk_baidu_mv_${mv_fs_id} */
    fun mvBvid(mvFsId: Long): String = "$MV_BVID_PREFIX$mvFsId"

    /** 判断 bvid 是否为百度网盘本地 MV（前缀路由用） */
    fun isBaiduMvBvid(bvid: String?): Boolean = bvid != null && bvid.startsWith(MV_BVID_PREFIX)

    /** 从百度 bvid 提取 fs_id */
    fun parseMvFsId(bvid: String): Long? = bvid.removePrefix(MV_BVID_PREFIX).toLongOrNull()

    /** errno → 用户友好提示 */
    fun describeErrno(errno: Int): String = ERRNO_MAP[errno] ?: "errno=$errno"
}
