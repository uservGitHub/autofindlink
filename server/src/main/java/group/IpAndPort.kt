package group

import java.net.InetAddress

/**
 * IP地址及端口（可以用于本机或远程机）
 * @param ip ipv4 本机IP或远程IP
 * @param port 本机监听端口或远程目标端口
 */
data class IpAndPort(val ip:String, val port:Int) {
    companion object {
        fun fromString(string: String): IpAndPort {
            val items = string.split(';')
            return IpAndPort(items[0], items[1].toInt())
        }
    }

    val inetAddr: InetAddress get() = InetAddress.getByName(ip)

    override fun toString() = "$ip;$port"
}