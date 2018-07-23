package link.find.auto.client

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
         * 写整数到字节流中(大端格式)
         * @param value 整数
         * @param buf 字节流
         * @param offset 起点偏移量
         * @param isTwoByte 写入的长度false表示4个，true表示2个
         */
        inline fun writeIntToByteArray(value:Int,buf:ByteArray, offset:Int, isTwoByte:Boolean = false):Boolean {
            val ind = if (isTwoByte) 1 else 3
            if (offset + ind >= buf.size) {
                return false
            }
            for (i in 0..ind) {
                buf[offset + i] = value.ushr((ind - i) * 8).toByte()
            }
            return true
        }

        /**
         * 读取整数从字节流中（大端格式）
         * @param buf 字节流
         * @param offset 起点偏移
         * @param isTwoByte 读取的长度false表示4个，true表示2个
         */
        inline fun readIntFromByteArray(buf:ByteArray, offset:Int, isTwoByte:Boolean = false):Int{
            val ind = if (isTwoByte) 1 else 3
            if (offset + ind >= buf.size) {
                return 0
            }
            var value:Int = 0
            var tmp:Int = 0
            for (i in 0..ind) {
                tmp = buf[offset + i].toInt()
                if (tmp<0){
                    tmp += 256
                }
                value = value.or(tmp.shl((ind-i)*8))
            }
            return value
        }


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