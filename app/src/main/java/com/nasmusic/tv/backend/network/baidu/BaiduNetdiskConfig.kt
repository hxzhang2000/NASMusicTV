package com.nasmusic.tv.backend.network.baidu

import android.content.Context
import com.nasmusic.tv.data.model.CloudDriveType
import com.nasmusic.tv.R

/**
 * 百度网盘开放平台 API 集中常量表
 *
 * 百度网盘开放平台 API 无显式版本号（不像 `/v2/xxx` 带版本路径），接口会静默演进。
 * 所有端点、method、参数、category 码集中在此——API 变更时只改这一处。
 * 基线见 `docs/archive/百度网盘音乐播放开发方案.md` §3.0。
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

    // ---- 应用沙箱目录 ----
    /**
     * 百度网盘应用默认可访问目录。
     * 自 2026-08-31 起，应用仅可访问 /apps/{应用名称} 目录。
     * 验证 token 和默认音乐根目录都应在此目录下。
     */
    const val APP_DIR = "/apps/NASMusicTV"

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
    /** 创建文件/目录 */
    const val METHOD_CREATE = "create"

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
     * 见 `docs/archive/百度网盘音乐播放开发方案.md` §3.0。
     */
    const val API_PROBE_BASELINE = ""

    // ---- dlink 播放约束 ----
    /** dlink 请求必需的 User-Agent（>20MB 文件不加会被 403） */
    const val BAIDU_UA = "pan.baidu.com"
    /** 双保险 Referer */
    const val BAIDU_REFERER = "https://pan.baidu.com/"
    /** dlink 直链域名标记（用于 DataSource 拦截器判断是否注入百度请求头） */
    val DLINK_HOST_MARKERS = listOf("d.pcs.baidu.com", "pan.baidu.com", "dDownList")

    // ---- 本地错误码（不与百度官方 errno 冲突）----
    /** 本地 token 缺失或无效，未调用百度 API（非服务器返回） */
    const val LOCAL_ERRNO_NO_TOKEN = -100
    /** 本地网络请求异常，未获得百度响应 */
    const val LOCAL_ERRNO_NETWORK = -101

    // ---- API 错误码映射表（errno）----
    // 对照百度网盘开放平台官方错误码表（2026-08-13 更新）
    // 本地错误码（-100 系列）不与官方 errno 冲突，明确区分本地与服务器错误
    val ERRNO_MAP: Map<Int, Int> = mapOf(
        -100 to R.string.baidu_errno_n100,
        -101 to R.string.baidu_errno_n101,
        -1 to R.string.baidu_errno_n1,
        -3 to R.string.baidu_errno_n3,
        -6 to R.string.baidu_errno_n6,
        -7 to R.string.baidu_errno_n7,
        -8 to R.string.baidu_errno_n8,
        -9 to R.string.baidu_errno_n9,
        2 to R.string.baidu_errno_2,
        6 to R.string.baidu_errno_6,
        10 to R.string.baidu_errno_10,
        11 to R.string.baidu_errno_11,
        111 to R.string.baidu_errno_111,
        31023 to R.string.baidu_errno_31023,
        31024 to R.string.baidu_errno_31024,
        31034 to R.string.baidu_errno_31034,
        // P1#2 修复（2026-09-13）：补 31079 映射（百度"文件不存在/已删除"类错误）
        31079 to R.string.baidu_errno_31079,
        31045 to R.string.baidu_errno_31045,
        31061 to R.string.baidu_errno_31061,
        31062 to R.string.baidu_errno_31062,
        31064 to R.string.baidu_errno_31064,
        31066 to R.string.baidu_errno_31066,
        31300 to R.string.baidu_errno_31300,
        31326 to R.string.baidu_errno_31326,
        31341 to R.string.baidu_errno_31341,
        31346 to R.string.baidu_errno_31346,
        31360 to R.string.baidu_errno_31360,
        31362 to R.string.baidu_errno_31362,
        31363 to R.string.baidu_errno_31363,
        31649 to R.string.baidu_errno_31649,
        42213 to R.string.baidu_errno_42213,
        42905 to R.string.baidu_errno_42905,
        20011 to R.string.baidu_errno_20011,
        20012 to R.string.baidu_errno_20012,
        20013 to R.string.baidu_errno_20013,
        20015 to R.string.baidu_errno_20015,
        20016 to R.string.baidu_errno_20016,
        20017 to R.string.baidu_errno_20017,
        20020 to R.string.baidu_errno_20020,
        20021 to R.string.baidu_errno_20021,
        20022 to R.string.baidu_errno_20022,
        20023 to R.string.baidu_errno_20023,
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
    fun describeErrno(context: Context, errno: Int): String =
        context.getString(ERRNO_MAP[errno] ?: R.string.baidu_errno_unknown, errno)
}

