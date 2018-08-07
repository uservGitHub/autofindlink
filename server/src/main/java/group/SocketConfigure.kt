package group

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Socket全局配置
 */
class SocketConfigure {
    companion object {
        //测试用的IP地址，实际运行程序时不使用

        val TestLocalIp200 = "192.168.200.200"
        val TestLocalPort200 = 40_200

        val DOMAIN_REQ = 1  //请求域
        val DOMAIN_RUN = 2  //运行域

        val OPERATION_TIMEOUT = 11  //超时
        val OPERATION_RUNNING = 11  //执行中
        val DATATAG_CONF = 1  //配置
        val DATATAG_FINEPATH = 2  //文件路径
        val DATATAG_STDOUT = 3  //控制台正常输出
        val DATATAG_STDERR = 4  //控制台异常输出
        val DATATAG_EXIT = 5  //传输结束


        /**
         * 获得本机IP列表（如配置了回环IP，会是多个）
         */
        val localIps: List<String>
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
        /**
         * 获得本地长IP地址（无法获取时，返回 127.0.0.1）
         */
        val localLongIp: String
            get() = localIps.maxBy { it.length } ?: "127.0.0.1"
    }
}