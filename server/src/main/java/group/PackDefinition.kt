package group

import java.net.DatagramPacket


/**
 * 包定义
 * @param handType 交互类型
 * @param dataType 数据类型
 * @param dataContent 数据内容（可空）
 * 【数据类型决定数据内容】现有两种：
 * 1 数据类型=类 4B 类名称hashCode + 字符串内容
 * 2 数据类型=流 4B 流序号/流Id + 流内容
 *
 * 用法：提供与DatagramPacket的对接
 */
class PackDefinition(val handType:Byte, val dataType:Byte, var dataContent:ByteArray? = null) {
    /**
     * 转换成字节流
     */
    fun toByteArray(): ByteArray {
        val contentSize = dataContent?.size ?: 0
        val byteArray = ByteArray(2 + contentSize)
        byteArray[0] = handType
        byteArray[1] = dataType
        dataContent?.let {
            System.arraycopy(dataContent, 0, byteArray, 2, contentSize)
        }
        return byteArray
    }

    companion object {
        /**
         * 内容偏移量
         */
        val contentOffset = 2
        /**
         * 获取交互类型
         */
        fun getHandType(byteArray: ByteArray, offset: Int, length: Int): Byte {
            return byteArray[offset]
        }

        fun getHandType(datagramPacket: DatagramPacket):Byte {
            return getHandType(datagramPacket.data, datagramPacket.offset, datagramPacket.length)
        }
        /**
         * 获取数据类型
         */
        fun getDataType(byteArray: ByteArray, offset: Int, length: Int): Byte {
            return byteArray[offset + 1]
        }

        fun getDataType(datagramPacket: DatagramPacket): Byte {
            return getDataType(datagramPacket.data, datagramPacket.offset, datagramPacket.length)
        }


        fun main(argv: Array<String>) {
            val offset = 0
            val length = 2
            val handType:Byte = 1
            val dataType:Byte = 2
            val udpPacket = DatagramPacket(byteArrayOf(handType, dataType), offset, length)

            when(getHandType(udpPacket)){
                handType -> {
                    println("交互类型是：$handType")
                    when(getDataType(udpPacket)){
                        dataType -> {
                            println("  数据类型是：$dataType")
                            println("    用户根据不同的数据类型对后续的字节流内容进行相应的解析")
                        }
                    }
                }
            }
            println()
            val PackDefinition = PackDefinition(handType, dataType, byteArrayOf(127, -1))
            println(PackDefinition.toByteArray().map { it.toString(16) }.joinToString(" "))
        }
    }
}

/*fun main(args: Array<String>) {
    PackDefinition.main(args)
}*/
