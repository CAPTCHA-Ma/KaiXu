package com.kaixu.domain.model.resources

/** 独立本地空间的链接或文本资料；工作区归属和记录 ID 在编辑时保持不变。 */
data class Resource(
    val id: String,
    val workspaceId: String,
    val createdBy: String,
    val name: String,
    val description: String?,
    val kind: ResourceKind,
    val url: String?,
    val textContent: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val localRevision: Long,
)

enum class ResourceKind { LINK, TEXT }

/** 仅接受可交给浏览器的 HTTP(S) 链接，不接受本机路径、脚本及自定义协议。 */
fun isResourceHttpUrl(value: String): Boolean {
    if (value.any { it.isWhitespace() || it.code < 32 || it == '\\' }) return false
    val match = Regex("^https?://([^/?#]+)(?:[/?#].*)?$", RegexOption.IGNORE_CASE).matchEntire(value) ?: return false
    val authority = match.groupValues[1]
    // 不把账号密码嵌进资料链接；IPv6 地址使用方括号。
    if ('@' in authority) return false
    val host: String
    val port: String?
    if (authority.startsWith('[')) {
        val end = authority.indexOf(']')
        if (end < 2) return false
        host = authority.substring(1, end)
        if (':' !in host || host.any { it !in "0123456789abcdefABCDEF:." }) return false
        val tail = authority.substring(end + 1)
        if (tail.isNotEmpty() && !tail.startsWith(':')) return false
        port = tail.takeIf { it.isNotEmpty() }?.drop(1)
    } else {
        if (authority.count { it == ':' } > 1) return false
        host = authority.substringBefore(':')
        if (host.isEmpty() || host.split('.').any { label ->
                label.isEmpty() || !label.first().isLetterOrDigit() || !label.last().isLetterOrDigit() ||
                    label.any { !it.isLetterOrDigit() && it != '-' }
            }) return false
        port = if (':' in authority) authority.substringAfter(':') else null
    }
    if (port != null && (port.isEmpty() || port.any { !it.isDigit() } || port.toIntOrNull()?.let { it in 1..65535 } != true)) return false
    return true
}
