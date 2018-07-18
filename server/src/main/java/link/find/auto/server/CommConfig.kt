package link.find.auto.server

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

class CommConfig{
    companion object {
        @Deprecated("不再使用")
        val clientPort = 8008
        @Deprecated("不再使用")
        val serverPort = 8009
        /**
         * 组播IP
         */
        val groupIp = "224.0.1.117"
        /**
         * 组播端口
         */
        val groupPort = 51234

        /**
         * 获得本机IP列表（如配置了回环IP）
         */
        val localIps:List<String>
            get() {
                val ips = mutableListOf<String>()
                try {
                    val en = NetworkInterface.getNetworkInterfaces()
                    while (en.hasMoreElements()) {
                        val nif = en.nextElement()
                        val inet = nif.inetAddresses
                        while (inet.hasMoreElements()) {
                            val ip = inet.nextElement()
                            if (!ip.isLoopbackAddress && ip is Inet4Address) {
                                ips.add(ip.hostAddress)
                            }
                        }
                    }
                } catch (e: SocketException) {
                    e.printStackTrace()
                }
                return ips.toList()
            }
    }
}