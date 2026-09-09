package com.nasmusic.tv.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 网络工具
 *
 * 获取设备在局域网中的 IP 地址，用于生成扫码输入的 URL。
 */
object NetworkUtils {

    private const val TAG = "NetworkUtils"

    /**
     * 获取设备在局域网中的 IPv4 地址（点分十进制，如 "192.168.1.100"）
     *
     * F-6 修复：TV 常见双网卡（以太网 + Wi-Fi）场景下，原 firstOrNull() 不分接口
     * 优先级，可能返回错误网段的 IP（手机扫码后连不上）。改进：
     * 1. 有序候选——「有默认路由（实际在用）的接口」优先，其次 eth（有线），
     *    再 wlan（无线），最后其余接口；
     * 2. 同一接口内取第一个非回环 IPv4；
     * 3. 全部失败时回退原行为（任意非回环 IPv4）。
     * 不需要 ACCESS_WIFI_STATE 权限，兼容所有 API 级别（接口名前缀匹配，不用 ICU）。
     *
     * @return IP 地址字符串；无可用网络时返回 null
     */
    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.asSequence()?.toList().orEmpty()

            fun interfacePriority(ni: NetworkInterface): Int {
                // 在用（hasUp）优先；接口名前缀粗略区分有线/无线（API 22 兼容，不用 ConnectivityManager）
                val name = ni.name.lowercase()
                val bonus = when {
                    name.startsWith("eth") -> 2   // 有线（TV 首选）
                    name.startsWith("wlan") || name.startsWith("wifi") -> 1  // 无线
                    else -> 0
                }
                return (if (ni.isUp) 8 else 0) + bonus
            }

            fun ipv4Of(ni: NetworkInterface): String? =
                ni.inetAddresses?.asSequence()
                    ?.filter { !it.isLoopbackAddress && it is Inet4Address }
                    ?.map { it.hostAddress }
                    ?.firstOrNull()

            interfaces
                .sortedByDescending { interfacePriority(it) }
                .firstNotNullOfOrNull { ni -> ipv4Of(ni) }
                // 兜底：保持原行为（任意非回环 IPv4）
                ?: interfaces.flatMap { it.inetAddresses?.asSequence() ?: emptySequence() }
                    .filter { !it.isLoopbackAddress && it is Inet4Address }
                    .map { it.hostAddress }
                    .firstOrNull()
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to get local IP", e)
            null
        }
    }
}
