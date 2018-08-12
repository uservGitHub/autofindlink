package group

import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Udp传输，发送和接收只能开一个
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
class UdpTransfer private constructor(localIp: String, localPort:Int){
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
        //val CALLBACK_COMPLETE = 1
        val CALLBACK_ERROR = 2
        val CALLBACK_DISPOSE = 3
        val CALLBACK_FINISH = 4

        val STATE_RECV_END = 1 //接收结束（成功、错误、或取消）
        val STATE_SEND_END = 2 //发送结束（成功、错误、或取消）

        val RET_CODE_THROW = 1 //返回状态码，异常
        val RET_CODE_TIMEOUT = 2 //返回状态码，超时
        val RET_CODE_CHECK = 3 //返回状态码，校验错误
        val RET_CODE_OK = 4 //返回状态码，成功
        val RET_CODE_IGNORE = 5 //返回状态码，忽略

        fun create():UdpTransfer {
            val ip = SocketConfigure.localIps.first()
            return UdpTransfer(ip, 0)
        }
        fun create(localPort:Int):UdpTransfer {
            val ip = SocketConfigure.localIps.first()
            return UdpTransfer(ip, localPort)
        }
        fun create(localIp:String) = UdpTransfer(localIp, 0)
        fun create(localIp: String, localPort: Int) = UdpTransfer(localIp, localPort)
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
    var localHostName: String = ""
        private set
    private lateinit var socket: DatagramSocket
    private var listeningDisposable: Disposable? = null
    private var sendingDisposable: Disposable? = null

    //private var onFinishSend:((endCode:Int)->Unit)? = null
    //private var onFinishRecv:((endCode:Int)->Unit)? = null

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
            socket = DatagramSocket(localPort, InetAddress.getByName(localIp))
            localIpAndPort = Pair(socket.localAddress.hostAddress, socket.localPort)
            localHostName = "[${socket.localAddress.hostName}][${socket.localAddress.canonicalHostName}]"
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

    /**
     * 启动发送
     * @param appBuffer 应用数据流
     * @param endCallback 发送完毕后的回调操作
     * @param speed 发送速度（字节数/每秒）
     * @return 是否正常启动发送
     */
    fun startSendBuffer(appBuffer: ByteArray,remoteIp:String, remotePort:Int, endCallback:(callbackKey:Int)->Unit, speed:(bytesPerSecond:Int)->Unit):Boolean {
        if (canWork.not() || isSending || isRecving) {
            return false
        }
        if (sendingDisposable != null && sendingDisposable!!.isDisposed.not()) {
            return true
        }
        isSending = true

        val inetAddress = InetAddress.getByName(remoteIp)

        //启动源
        val sourceStart = Observable.create<Int> {
            var sendPackCount = 0 //重发次数
            while (it.isDisposed.not()){ //canWork已检查，去掉
                when(state){
                    0 -> {
                        bufferControl = BufferControl(appBuffer, inetAddress, remotePort)
                        state = 22_01
                        sendPackCount = 0
                    }
                    22_01 -> {
                        //最多发送3次间隔50毫秒，第4次结束
                        if (sendPackCount<3){
                            sendPackCount++
                            bufferControl!!.sendTagBeg()
                            Thread.sleep(50)
                        }else{
                            return@create
                        }
                    }
                    else -> {
                        //后续状态流转处理已启动，结束
                        return@create
                    }
                }
            }
        }
        //响应式接收源
        val sourceRespond = Observable.create<Int> {
            while (it.isDisposed.not()){
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
                //汇报并中断任务处理
                if (isRecvError){
                    it.onNext(RET_CODE_THROW)
                    break
                }
                when(state){
                    22_01, 22_02 -> {
                        if (state == 22_01){
                            state = 22_02
                        }
                        when(getHandType(packet)){
                            HAND_ID_REQ -> {
                                recvOkTick = System.currentTimeMillis()
                                val dataId = getDataId(packet)
                                bufferControl!!.send(dataId)
                            }
                            HAND_END_SIZE -> {
                                recvOkTick = System.currentTimeMillis()
                                val size = getDataId(packet)
                                if (size != bufferControl!!.buf.size){
                                    //数量不符，上报
                                    it.onNext(RET_CODE_CHECK)
                                }else{
                                    //结束
                                    state = STATE_SEND_END
                                    it.onNext(RET_CODE_OK)
                                }
                                return@create
                            }
                        }
                    }
                }
            }
        }.subscribeOn(Schedulers.newThread()) //局域网的响应速度快，不用IO线程
        //超时判断源
        val sourceCheckTimeout = Observable.interval(1_000, TimeUnit.MICROSECONDS)
                .map {
                    val timeSpan = System.currentTimeMillis() - recvOkTick
                    if (timeSpan > 2000 && state != STATE_SEND_END)RET_CODE_TIMEOUT
                    else RET_CODE_IGNORE
                }

        //启动时，复位状态机
        when(state){
            0, STATE_RECV_END, STATE_SEND_END -> {
                //可以启动，复位状态机，启动
                state = 0
                var selfDisposed = false
                val source = Observable.merge(listOf(sourceRespond,sourceStart,sourceCheckTimeout))
                sendingDisposable = source//.subscribeOn(Schedulers.computation())
                        .observeOn(Schedulers.computation())
                        .doOnDispose {
                            if (selfDisposed.not()) {
                                try {
                                    onLogLine?.invoke(keyLog("Dispose"))
                                } catch (e: Exception) {

                                }
                                try {
                                    endCallback(CALLBACK_DISPOSE)
                                } catch (e: Exception) {

                                }
                            }
                        }
                        .subscribe(
                                {
                                    when(it){
                                        RET_CODE_OK -> {
                                            //完成
                                            selfDisposed = true
                                            stopSend()
                                            try {
                                                onLogLine?.invoke(keyLog("Finish"))
                                            } catch (e: Exception) {

                                            }
                                            try {
                                                endCallback(CALLBACK_FINISH)
                                            }catch (e:Exception){

                                            }
                                        }
                                        RET_CODE_IGNORE -> {}
                                        else ->{
                                            selfDisposed = true
                                            stopSend()
                                            try {
                                                onLogLine?.invoke(keyLog("Error"))
                                            } catch (e: Exception) {

                                            }
                                            try {
                                                endCallback(CALLBACK_ERROR)
                                            }catch (e:Exception){

                                            }
                                        }
                                    }
                                },
                                {
                                    try {
                                        onLogLine?.invoke(keyLog("Error"))
                                    } catch (e: Exception) {

                                    }
                                    try {
                                        endCallback(CALLBACK_ERROR)
                                    }catch (e:Exception){

                                    }
                                    isSending = false
                                }
                        )
                return true
            }
        }

        return false

    }

    fun startListenBuffer(endCallback: (callbackKey: Int) -> Unit):Boolean{
        if (canWork.not() || isRecving || isSending) {
            return false
        }
        if (listeningDisposable != null && listeningDisposable!!.isDisposed.not()) {
            return true
        }
        isRecving = true

        val sourceStart = Observable.create<Int> {
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
                //汇报并中断任务处理
                if (isRecvError){
                    it.onNext(RET_CODE_THROW)
                    break
                }

                if (isSending.not()) {
                    val handType = getHandType(packet)
                    when (state) {
                        0 -> {
                            when (handType) {
                                HAND_BEG_SIZE -> {
                                    state = 11_01
                                    recvOkTick = System.currentTimeMillis()
                                    try {
                                        val reqBuf = ByteArray(packet.data.toInt(packet.offset + 2, 4))
                                        bufferControl = BufferControl(reqBuf, packet.address, packet.port)
                                        bufferControl!!.request(bufferControl!!.idFromRecv.get() + 1)
                                    } catch (e: Exception) {
                                        //可能是申请内存失败
                                        //要上报
                                        it.onNext(RET_CODE_THROW)
                                        return@create
                                    }
                                }
                            }
                        }
                        11_01 -> {
                            when (handType) {
                                HAND_ID_DATA -> {
                                    val canWrite = bufferControl!!.write(packet)
                                    if (canWrite.not()) {
                                        //退出
                                        it.onNext(RET_CODE_THROW)
                                        return@create
                                    } else {
                                        recvOkTick = System.currentTimeMillis()
                                    }
                                    val idFromRecv = bufferControl!!.idFromRecv.get()
                                    if (idFromRecv == bufferControl!!.dataUnitSize) {
                                        //转结束
                                        state = STATE_RECV_END
                                        bufferControl!!.sendTagEnd()
                                        it.onNext(RET_CODE_OK)
                                        return@create
                                    } else if (idFromRecv < bufferControl!!.dataUnitSize) {
                                        bufferControl!!.request(bufferControl!!.idFromRecv.get() + 1)
                                    }
                                }
                            }
                        }
                    }
                }
                //endregion
            }
        }.subscribeOn(Schedulers.newThread())

        val sourceReqAndTimeout = Observable.interval(500, TimeUnit.MICROSECONDS)
                .map {
                    //启动的情况下，才执行超时判断
                    if (state != 0) {
                        val timeSpan = System.currentTimeMillis() - recvOkTick
                        if (timeSpan>100){
                            //重新请求
                            bufferControl!!.request(bufferControl!!.idFromRecv.get() + 1)
                            return@map RET_CODE_IGNORE
                        }
                        if (timeSpan > 1500) return@map RET_CODE_TIMEOUT
                    }
                    RET_CODE_IGNORE
                }
        when(state){
            0, STATE_SEND_END, STATE_RECV_END ->{
                //可以启动，复位状态机，启动
                state = 0
                var selfDisposed = false
                val source = Observable.merge(listOf(sourceStart,sourceReqAndTimeout))
                listeningDisposable = source//.subscribeOn(Schedulers.computation())
                        .observeOn(Schedulers.computation())
                        .doOnDispose {
                            if (selfDisposed.not()) {
                                try {
                                    onLogLine?.invoke(keyLog("Dispose"))
                                } catch (e: Exception) {

                                }
                                try {
                                    endCallback(CALLBACK_DISPOSE)
                                } catch (e: Exception) {

                                }
                            }
                            isRecving = false
                        }
                        .subscribe(
                                {
                                    when(it){
                                        RET_CODE_OK -> {
                                            //完成
                                            selfDisposed = true
                                            stopListen()
                                            try {
                                                onLogLine?.invoke(keyLog("Finish"))
                                            } catch (e: Exception) {

                                            }
                                            try {
                                                endCallback(CALLBACK_FINISH)
                                            }catch (e:Exception){

                                            }
                                            isRecving = false
                                        }
                                        RET_CODE_IGNORE -> {}
                                        else ->{
                                            selfDisposed = true
                                            stopListen()
                                            try {
                                                onLogLine?.invoke(keyLog("Error"))
                                            } catch (e: Exception) {

                                            }
                                            try {
                                                endCallback(CALLBACK_ERROR)
                                            }catch (e:Exception){

                                            }
                                            isRecving = false
                                        }
                                    }
                                },
                                {
                                    try {
                                        onLogLine?.invoke(keyLog("Error"))
                                    } catch (e: Exception) {

                                    }
                                    try {
                                        endCallback(CALLBACK_ERROR)
                                    }catch (e:Exception){

                                    }
                                    isRecving = false
                                }
                        )
                return true
            }
        }
        return false
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
            println("${localIpAndPort.first} 最大单元号：$dataUnitSize")
        }

        /**
         * 发送起始标签
         */
        fun sendTagBeg(){
            val sendBuf = ByteArray(6)
            sendBuf[0] = HAND_BEG_SIZE
            sendBuf.fromInt(buf.size, 2, 4)
            val packet = DatagramPacket(sendBuf, sendBuf.size, inetAddress, remotePort)
            println("${localIpAndPort.first} 发送：Beg")
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
            println("${localIpAndPort.first} 发送End")
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
            val packet = DatagramPacket(sendBuf, sendBuf.size, inetAddress, remotePort)
            //println("${localIpAndPort.first} 响应：$dataId")
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
            //println("${localIpAndPort.first} 请求：$dataId")
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
                val offset = id - idFromRecv.get()
                if (offset == 1) {
                    //更新接收标志
                    val dataId = idFromRecv.addAndGet(1)
                    val length = if (dataId == dataUnitSize) dataLastSize else DATAUNIT_SIZE
                    System.arraycopy(packet.data, packet.offset + 6,
                            buf, (dataId - 1) * DATAUNIT_SIZE, length)
                    return true
                }
                println("${localIpAndPort.first} 写入错误：$offset")
                return true
            }
            println("${localIpAndPort.first} 写入错误：包错误")
            return false
        }
    }
}