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

    // ---- 本地错误码（不与百度官方 errno 冲突）----
    /** 本地 token 缺失或无效，未调用百度 API（非服务器返回） */
    const val LOCAL_ERRNO_NO_TOKEN = -100
    /** 本地网络请求异常，未获得百度响应 */
    const val LOCAL_ERRNO_NETWORK = -101

    // ---- API 错误码映射表（errno）----
    // 对照百度网盘开放平台官方错误码表（2026-08-13 更新）
    // 本地错误码（-100 系列）不与官方 errno 冲突，明确区分本地与服务器错误
    val ERRNO_MAP: Map<Int, String> = mapOf(
        -100 to "本地 token 缺失或无效，未调用百度 API，请重新登录百度网盘",
        -101 to "网络请求异常，未获得百度响应，请检查网络连接",
        -1 to "权益已过期",
        -3 to "文件不存在",
        -6 to "身份验证失败，请检查 access_token 是否有效、授权是否成功",
        -7 to "文件或目录名错误或无权访问",
        -8 to "文件或目录已存在",
        -9 to "文件或目录不存在",
        2 to "参数错误，请检查必选参数是否已填写、参数位置和值是否正确",
        6 to "不允许接入用户数据，建议10分钟后重新授权",
        10 to "转存文件已经存在",
        11 to "用户不存在(uid不存在)",
        111 to "有其他异步任务正在执行，稍后可重新请求",
        31023 to "参数错误",
        31024 to "没有访问权限",
        31034 to "命中接口频控，请降低请求频率",
        31045 to "access_token 验证未通过，请检查 token 是否过期或用户是否已授权网盘权限",
        31061 to "文件已存在",
        31062 to "文件名无效，包含特殊字符",
        31064 to "上传路径错误",
        31066 to "文件名不存在",
        31300 to "下载相关错误",
        31326 to "命中防盗链，请检查 User-Agent 请求头",
        31341 to "视频正在转码，可重新请求",
        31346 to "视频转码失败",
        31360 to "url 过期，请重新获取",
        31362 to "签名错误，请检查链接地址是否完整",
        31363 to "分片缺失",
        31649 to "字幕不存在",
        42213 to "共享目录鉴权失败",
        42905 to "查询用户名失败，可重试",
        20011 to "应用审核中，仅限前10个完成 OAuth 授权的用户测试应用",
        20012 to "访问超限，调用次数已达上限",
        20013 to "权限不足，当前应用无接口权限，请完成应用上线审核并申请对应接口权限",
        20015 to "该应用已失效，暂不支持访问",
        20016 to "access_token 已过期",
        20017 to "access_token 无效，可能因用户解绑或授权撤销等原因失效",
        20020 to "路径不在允许的访问范围内，仅限 /apps/应用名 目录",
        20021 to "无法获取应用名称，请检查应用id是否准确",
        20022 to "路径参数格式不正确",
        20023 to "缺少必需的路径参数"
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
