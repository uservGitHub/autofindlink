package link.find.auto.client

import android.util.Log
import java.net.DatagramSocket

/**
 * 传输流
 * @param socket 传输通道
 * @param fail 发送失败(参数是send函数中的groupId)
 * @param success 发送成功(参数是send函数中的groupId)
 * @param groupCount 发送的组数量(默认只有一组)
 */
class TransByteArray(val socket: DatagramSocket,
                     val fail:()->Unit,
                     val success:()->Unit,
                     val groupCount:Int = 1,
                     val next:((finishNo: Int)->Unit)? = null) {
    companion object {
        fun test() {
            val bigBuffer = ByteArray(10 * 1024 * 1024) //10MB
            val end: (status: Boolean) -> Unit = {
                Log.v("_test", "测试TransByteArray结果:$it")
            }
            val socket: DatagramSocket? = null
            var trans:TransByteArray? = null
            trans = TransByteArray(socket!!,{end(false)}, {end(true)}, 3){
                if (it<=trans!!.groupCount){
                    Log.v("_test", "完成groupNo:$it")
                    trans!!.send(bigBuffer)
                }
            }
            trans.send(bigBuffer)

        }

    }

    /**
     * 发送字节流（异步），完毕或失败后进行响应
     * @param buf 字节流
     */
    fun send(buf: ByteArray) {

    }
}

/**
 * 字节流传输，使用UdpSocket
 * @param buf 字节流(最大约2.1G)
 */
class TransBuffer1(val buf: ByteArray, val toBuf: (ByteArray) -> Unit) {

    //发送字节缓存区
    private val sendBuffer = ByteArray(1472, { 0 })
    // 请求REQ + 4字节包序号（多个）
    val REQ = "req.".toByteArray()
    // 发送端END+4字节包数量+2字节的尾包长度
    val END = "end.".toByteArray()
    // 发送端START+4字节包数量+2字节的尾包长度
    val START = "srt.".toByteArray()
    // 发送端SEND+4字节包序号+传输字节流
    val SEND = "snd.".toByteArray()
    // 配置标志CONF+传输字节流（json）
    val CONF = "cnf.".toByteArray()
    // 文本标志TEXT+传输字节流（文本）
    val TEXT = "txt.".toByteArray()
    // Udp包1472-"snd."的长度-包序号=应用字节数
    val UNITSIZE = 1472 - 4 - SEND.size
    // 补充时刻基数
    val SUPPLYBASE = 350
    // 重新请求时刻基数
    val REREQBASE = 250
    // 基数增量
    val BASEINCREMENT = 300


    // 补包索引
    private var supplyIndex = 0
    //包序号
    private var packetIndex = -1
    //包数量
    private val packetCount: Int
    //尾包应用字节数
    private val tailByteSize: Int

    init {
        packetCount = 1 + buf.size / UNITSIZE
        tailByteSize = buf.size.rem(UNITSIZE)
    }
    //region    sendPacket
    private inline fun sendPacketFromPackInd(packInd:Int){
        var offset = 0
        System.arraycopy(SEND, 0, sendBuffer, offset, SEND.size)
        offset += SEND.size
        CommConfig.writeIntToByteArray(packInd, sendBuffer, offset)
        offset += 4
        if (packInd < packetCount - 1) {
            System.arraycopy(buf, packInd * UNITSIZE, sendBuffer, offset, UNITSIZE)
            //this@BaseLinkActivity.appSocket.send(sendBuffer, 0, sendBuffer.size)
        } else {
            System.arraycopy(buf, packInd * UNITSIZE, sendBuffer, offset, tailByteSize)
            //this@BaseLinkActivity.appSocket.send(sendBuffer, 0, offset + tailByteSize)
        }
    }
    private fun sendStartPacket(){
        var offset = 0
        System.arraycopy(START, 0, sendBuffer, offset, START.size)
        offset += START.size
        CommConfig.writeIntToByteArray(packetCount, sendBuffer, offset)
        offset += 4
        CommConfig.writeIntToByteArray(tailByteSize, sendBuffer, offset, true)
        offset += 2

        //this@BaseLinkActivity.appSocket.send(sendBuffer, 0, offset)
        //this@BaseLinkActivity.appSocket.send(sendBuffer, 0, offset)
    }
    private fun sendEndPacket(){
        var offset = 0
        System.arraycopy(END, 0, sendBuffer, offset, END.size)
        offset += END.size
        CommConfig.writeIntToByteArray(packetCount, sendBuffer, offset)
        offset += 4
        CommConfig.writeIntToByteArray(tailByteSize, sendBuffer, offset, true)
        offset += 2

        //this@BaseLinkActivity.appSocket.send(sendBuffer, 0, sendBuffer.size)
        //this@BaseLinkActivity.appSocket.send(sendBuffer, 0, sendBuffer.size)
    }

    /**
     * 发送请求包（未收到的包）
     */
    private fun sendReReqPacket(){

    }
    //endregion
    /**
     * 一旦发送，就不能停止
     */
    fun send() {
        while (true) {
            when {
            //传输完毕
                packetIndex >= packetCount -> {
                    sendEndPacket()
                }
            //发送Start标志
                packetIndex == -1 -> {
                    sendStartPacket()
                }
                else -> {
                    sendPacketFromPackInd(packetIndex)
                }
            }
            packetIndex++

            //补包时刻
            if (packetIndex.rem(10) == 0 && packetIndex==SUPPLYBASE+supplyIndex*BASEINCREMENT) {
                supplyIndex++
                sendReReqPacket()
            }
        }
    }
}