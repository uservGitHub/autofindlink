package group

import gxd.socket.GroupBroadcastSocket
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Udp传输
 * 基本约定：
 * 一、进入交互的情况下：
 * 1 要断开停止发送即可（250毫秒超时结束）
 * 二、未进入交互的情况下：
 * 1 一直监听，直到进入交互
 * 三、进入交互定义：
 * 1 由状态机进行控制，0 表示未进入交互
 * 2 如何进入交互状态？一端向另一端执行发送操作
 * 3 交互完成？收到接收方结束标志，或超时
 */
class UdpTransfer{
    companion object {
        val HAND_BEG_SIZE:Byte = 11 //开始时，数据大小
        val HAND_ID_REQ:Byte = 12 //请求的数据ID
        val HAND_END_SIZE:Byte = 13 //结束时，收到的数据大小
        val HAND_ID_DATA:Byte = 14 //发送数据的ID及数据

        /**
         * 获取交互类型
         * @param packet 数据报
         */
        inline fun getHandType(packet: DatagramPacket): Byte {
            return packet.data[packet.offset]
        }

        /**
         * 获取数据ID
         * @param packet 数据报
         */
        inline fun getDataId(packet: DatagramPacket): Int {
            return packet.data.toInt(packet.offset + 2)
        }

        val DATAUNIT_SIZE = 1400 //数据单位大小，不含ID

        //回调标志
        val CALLBACK_COMPLETE = 1
        val CALLBACK_ERROR = 2
        val CALLBACK_DISPOSE = 3
        val CALLBACK_FINISH = 4
    }
    /**
     * 能否工作
     */
    var canWork: Boolean = false
        private set
    var bufferControl:BufferControl? = null
        private set
    var localIpAndPort: Pair<String, Int> = Pair("", 0)
        private set
    private lateinit var socket: MulticastSocket
    private var listeningDisposable: Disposable? = null
    private var sendingDisposable: Disposable? = null

    private var onFinishSend:((endCode:Int)->Unit)? = null
    private var onFinishRecv:((endCode:Int)->Unit)? = null

    @Volatile
    var isSending = false //只能是发送线程修改
        private set
    @Volatile
    var isRecving = false //只能是接收线程修改
        private set
    @Volatile
    var state = 0 //只能是接收或发送线程修改
        private set
    @Volatile
    private var recvOkTick = 0L //接收OK的时刻，只能接收线程修改
    @Volatile
    private var isCheckTimeout = false //是否校验超时，由状态机开启，并设置recvOkTick
    @Volatile
    private var requestId = -1 //由接收端赋值
    private var timeoutCount = 0 //超时数量
    private var onLogLine:((String)->Unit)? = null
    /**
     * 关键信息输出格式
     */
    private val keyLog:(String)->String = {
        "$it from ${this.javaClass.simpleName} [${localIpAndPort.first}:${localIpAndPort.second}] [${Thread.currentThread()}]"
    }

    /**
     * 设置日志行输出
     * @param logLine 日志行输出
     */
    fun setLogLine(logLine:(String)->Unit){
        this.onLogLine = logLine
    }
    init {
        try {
            socket = MulticastSocket(GroupBroadcastSocket.GROUP_PORT).apply {
                // 如果只是发送组播，上面的端口号和下面的三项内容不需要
                joinGroup(InetAddress.getByName(GroupBroadcastSocket.GROUP_IP))
                timeToLive = 128 //本地网络一般不需要
                loopbackMode = false //非回环网络
            }
            localIpAndPort = Pair(socket.localAddress.hostAddress, socket.localPort)
            canWork = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    /**
     * 停止发送
     */
    fun stopSend(){
        sendingDisposable?.dispose()
        sendingDisposable = null
    }

    /**
     * 停止接收
     */
    fun stopListen(){
        listeningDisposable?.dispose()
        listeningDisposable = null
    }

    /**
     * 关闭
     */
    fun close(){
        stopSend()
        stopListen()
        if (canWork && socket.isClosed.not()){
            socket.close()
        }
    }
    private fun pack(handType: Byte, value:Int) = byteArrayOf(handType,0,*value.toByteArray(4))
    /**
     * 启动发送
     * @param appBuffer 应用数据流
     * @param endCallback 发送完毕后的回调操作
     * @param speed 发送速度（字节数/每秒）
     * @return 是否正常启动发送
     */
    fun startSend(appBuffer: ByteArray,remoteIp:String, remotePort:Int, endCallback:(callbackKey:Int)->Unit, speed:(bytesPerSecond:Int)->Unit):Boolean {
        if (canWork.not() || isSending) {
            return false
        }
        if (sendingDisposable != null && sendingDisposable!!.isDisposed.not()) {
            return true
        }
        isSending = true

        val inetAddress = InetAddress.getByName(remoteIp)


        //启动即可，剩下的靠监听来完成
        val source = Observable.create<Unit> {
            var sendPackCount = 0
            while (canWork && it.isDisposed.not()) {
                when(state){
                    0 -> {
                        //可以开启
                        bufferControl = BufferControl(appBuffer, inetAddress, remotePort)
                        this.onFinishSend = endCallback
                        state = 22_01
                        bufferControl!!.sendTagBeg()
                        sendPackCount = 0
                    }
                    22_01 -> {
                        if (sendPackCount < 4) {
                            sendPackCount++
                            Thread.sleep(50)
                            if (bufferControl!!.idFromSend <= 0) {
                                bufferControl!!.sendTagBeg()
                            }else{
                                sendPackCount = 4
                            }
                        }
                    }
                    22_03 -> {
                        state = 0
                        endCallback(CALLBACK_FINISH)
                        //onFinishSend!!.invoke(CALLBACK_FINISH)
                        isSending = false
                    }
                }
            }
            it.onComplete()
        } .doOnDispose {
            try {
                onLogLine?.invoke(keyLog("Dispose"))
            } catch (e: Exception) {

            }
            endCallback(CALLBACK_DISPOSE)
        }

        sendingDisposable = source//.subscribeOn(Schedulers.computation())
                .observeOn(Schedulers.computation())
                .subscribe(
                        {},
                        {
                            try {
                                onLogLine?.invoke(keyLog("Error"))
                            } catch (e: Exception) {

                            }
                            endCallback(CALLBACK_ERROR)
                        },
                        {
                            try {
                                onLogLine?.invoke(keyLog("Complete"))
                            } catch (e: Exception) {

                            }
                            endCallback(CALLBACK_COMPLETE)
                        }
                )
        return true
    }

    fun startListenBuffer(endCallback: (callbackKey: Int) -> Unit):Boolean{
        if (canWork.not()) {
            return false
        }
        if (listeningDisposable != null && listeningDisposable!!.isDisposed.not()) {
            return true
        }
        this.onFinishRecv = endCallback

        //永远接收，除非 关闭
        val source = Observable.create<Unit> {
            while (it.isDisposed.not()) {
                //动态分配，最大
                val buf = ByteArray(DATAUNIT_SIZE + 6)
                val packet = DatagramPacket(buf, buf.size)
                var isRecvError = false //中断本次接收任务
                try {
                    socket.receive(packet)
                } catch (e: Exception) {
                    isRecvError = true
                }
                //跳出
                if (it.isDisposed){
                    break
                }
                //中断任务处理
                if (isRecvError || timeoutCount>0){
                    state = 0
                    isRecving = false
                    //上报
                    //...
                    continue
                }

                //region    状态机(由接收状态为始点)
                if (isSending.not()) {
                    when (state) {
                        0 -> {
                            when (getHandType(packet)) {
                                HAND_BEG_SIZE -> {
                                    state = 11_01
                                    isRecving = true
                                    recvOkTick = System.currentTimeMillis()
                                    timeoutCount = 0
                                    isCheckTimeout = true

                                    try {
                                        val buf = ByteArray(packet.data.toInt(packet.offset + 2, 4))
                                        bufferControl = BufferControl(buf, packet.address, packet.port)
                                        bufferControl!!.request(bufferControl!!.idFromRecv.get())
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        //可能是申请内存失败
                                        //要上报
                                    }
                                }
                                else -> {
                                    //不该有的分支
                                    //...
                                }
                            }
                        }
                        11_01 -> {
                            when (getHandType(packet)) {
                                HAND_ID_DATA -> {
                                    val canWrite = bufferControl!!.write(packet)
                                    if (canWrite.not()) {
                                        //打印错误信息
                                    } else {
                                        recvOkTick = System.currentTimeMillis()
                                    }
                                    if (bufferControl!!.idFromRecv.get() == bufferControl!!.dataUnitSize) {
                                        //结束
                                        //执行回调
                                        try {
                                            endCallback(CALLBACK_FINISH)
                                        }finally {
                                            state = 11_02 //接收完毕
                                            bufferControl!!.sendTagEnd()
                                            Thread.sleep(10)
                                            bufferControl!!.sendTagEnd()
                                        }
                                    } else {
                                        bufferControl!!.request(bufferControl!!.idFromRecv.get())
                                    }
                                }
                            }
                        }
                    }
                }
                if (isSending){
                    when(state){
                        22_01, 22_02 -> {
                            when(getHandType(packet)){
                                HAND_ID_REQ -> {
                                    recvOkTick = System.currentTimeMillis()
                                    val dataId = getDataId(packet)
                                    bufferControl!!.send(dataId)
                                    if (state == 22_01){
                                        state == 22_02
                                    }
                                }
                                HAND_END_SIZE -> {
                                    val size = getDataId(packet)
                                    if (size != bufferControl!!.buf.size){
                                        //数量不符，上报
                                    }else{
                                        recvOkTick = System.currentTimeMillis()
                                        //结束
                                        isCheckTimeout = false
                                        state = 22_03 //发送完毕，返回发送任务
                                    }
                                }
                            }
                        }
                    }
                }
                //endregion
            }
        }.doOnDispose {
            try {
                onLogLine?.invoke(keyLog("Dispose"))
            } catch (e: Exception) {

            }
            try {
                endCallback(CALLBACK_DISPOSE)
            }catch (e:Exception){

            }
        }

        listeningDisposable = source.subscribeOn(Schedulers.io())
                .observeOn(Schedulers.computation())
                .subscribe(
                        {},
                        {
                            try {
                                onLogLine?.invoke(keyLog("Error"))
                            } catch (e: Exception) {

                            }
                            try {
                                endCallback(CALLBACK_ERROR)
                            }catch (e:Exception){

                            }
                        },
                        {
                            //没有完成状态
                            /*try {
                                onLogLine?.invoke(keyLog("Complete"))
                            } catch (e: Exception) {

                            }
                            try {
                                endCallback(CALLBACK_COMPLETE)
                            }catch (e:Exception){

                            }*/
                        },
                        {
                            //启动接收时的初始态
                            state = 0
                            isRecving = false
                        }
                )

        val sourceCheckTimeout = Observable.interval(250, TimeUnit.MICROSECONDS)
                .doOnNext {
                    if (isCheckTimeout){
                        val timeSpan = System.currentTimeMillis() - recvOkTick
                        if (timeSpan > 250){
                            timeoutCount++
                        }
                    }
                }.subscribe()
        return true
    }


    /**
     * 字节流控制
     * @param buf 字节流(收/发)
     * @param inetAddress 远程端IP地址
     * @param remotePort 远程端的Port
     */
    inner class BufferControl(val buf:ByteArray,val inetAddress: InetAddress,val remotePort: Int) {
        /**
         * 数据单元最后一包数量数量
         */
        val dataLastSize: Int
        /**
         * 数据单元数量
         */
        val dataUnitSize: Int
        /**
         * 接收Id，write函数进行设置，不能跳
         */
        var idFromRecv: AtomicInteger
            private set
        /**
         * 已发送的最近数据Id值
         */
        @Volatile var idFromSend = 0
            private set

        init {
            val dataRemSize = buf.size.rem(DATAUNIT_SIZE)
            dataUnitSize = buf.size / DATAUNIT_SIZE +
                    if (dataRemSize == 0) 0 else 1
            dataLastSize = dataRemSize.takeIf { it > 0 } ?: DATAUNIT_SIZE
            idFromRecv = AtomicInteger(0)
        }

        /**
         * 发送起始标签
         */
        fun sendTagBeg(){
            val sendBuf = ByteArray(6)
            sendBuf[0] = HAND_BEG_SIZE
            sendBuf.fromInt(buf.size, 2, 4)
            val packet = DatagramPacket(sendBuf, sendBuf.size, inetAddress, remotePort)
            socket.send(packet)
        }

        /**
         * 发送结束标签
         */
        fun sendTagEnd(){
            val sendBuf = ByteArray(6)
            sendBuf[0] = HAND_END_SIZE
            sendBuf.fromInt(buf.size, 2, 4)
            val packet = DatagramPacket(sendBuf, sendBuf.size, inetAddress, remotePort)
            socket.send(packet)
        }

        /**
         * 发送数据
         * @param dataId 数据ID，从1开始
         */
        fun send(dataId: Int) {
            idFromSend = dataId
            val sendBuf = ByteArray(DATAUNIT_SIZE+6)
            sendBuf[0] = HAND_ID_DATA
            sendBuf.fromInt(dataId,2,4)
            val length = if (dataId == dataUnitSize) dataLastSize else DATAUNIT_SIZE
            System.arraycopy(buf,(dataId-1)* DATAUNIT_SIZE,sendBuf,6,length)
            val packet = DatagramPacket(buf, buf.size, inetAddress, remotePort)
            socket.send(packet)
        }

        /**
         * 请求
         * @param dataId 数据ID，从1开始
         */
        fun request(dataId: Int) {
            val sendBuf = ByteArray(6)
            sendBuf[0] = HAND_ID_REQ
            sendBuf.fromInt(dataId, 2, 4)
            val packet = DatagramPacket(sendBuf, sendBuf.size, inetAddress, remotePort)
            socket.send(packet)
        }

        /**
         * 写入用户报
         * @param packet 用户报
         */
        fun write(packet: DatagramPacket):Boolean {
            //数据长度正确，且交互状态正确
            if (packet.length == DATAUNIT_SIZE + 6 && packet.data[packet.offset] == HAND_ID_DATA) {
                val id = packet.data.toInt(packet.offset + 2, 4)
                //id 是当前接收标志+1
                if (id - idFromRecv.get() == 1) {
                    //更新接收标志
                    val dataId = idFromRecv.addAndGet(1)
                    val length = if (dataId == dataUnitSize) dataLastSize else DATAUNIT_SIZE
                    System.arraycopy(packet.data, packet.offset + 6,
                            buf, (dataId - 1) * DATAUNIT_SIZE, length)
                    return true
                }
            }
            return false
        }
    }
}