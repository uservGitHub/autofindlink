package group

import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * UdpScoket 使用
 */
class UdpSocket private constructor(localIp: String, localPort:Int){
    companion object {
        fun create():UdpSocket {
            val ip = SocketConfigure.localLongIp
            return UdpSocket(ip, 0)
        }
        fun create(localPort:Int):UdpSocket {
            val ip = SocketConfigure.localLongIp
            return UdpSocket(ip, localPort)
        }
        fun create(localIpAndPort: IpAndPort) = UdpSocket(localIpAndPort.ip,localIpAndPort.port)
        fun create(localIp:String) = UdpSocket(localIp, 0)

        /**
         * 发送数据（临时使用）
         */
        fun send(byteArray: ByteArray, remote: IpAndPort) {
            var sendSocket: DatagramSocket? = null
            try {
                sendSocket = DatagramSocket()
                sendSocket?.send(DatagramPacket(byteArray, byteArray.size, remote.inetAddr, remote.port))
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                sendSocket?.close()
            }
        }

        fun main(argv:Array<String>){
            val autoRespond = UdpSocket.create("192.168.200.200").apply {
                println("$canWork,$localIpAndPort")
            }
            val target = UdpSocket.create("192.168.200.201").apply {
                println("$canWork,$localIpAndPort")
            }
            val HAND_TEST:Byte = -4
            val DATA_ECHO:Byte = 5
            val DATA_PING:Byte = 11

            //region    自动回复
            var autoRecvCount = 0
            val autoRemote = target.localIpAndPort
            autoRespond.setOnRecvProcess { state, datagramPacket ->
                when(state){
                    0 -> {
                        println("First Recevied ${Thread.currentThread()}")
                        autoRespond.updateState(1)
                        autoRecvCount = 0
                    }
                    1 -> {
                        autoRecvCount++
                        if (autoRecvCount == 10){
                            autoRespond.updateState(2)
                            autoRecvCount = 0
                        }
                    }
                    2 -> {
                        //校验
                        val handType = PackDefinition.getHandType(datagramPacket)
                        val dataType = PackDefinition.getDataType(datagramPacket)
                        when(handType){
                            HAND_TEST -> {
                                when(dataType){
                                    DATA_PING -> {
                                        //回复
                                        val content = ByteArray(datagramPacket.length-PackDefinition.contentOffset)
                                        System.arraycopy(datagramPacket.data,datagramPacket.offset+PackDefinition.contentOffset,
                                                content,0, content.size)
                                        //val recvId = content.toInt()
                                        //println("Echo recvId = $recvId ,length=${datagramPacket.length}")
                                        val echoPack = PackDefinition(HAND_TEST, DATA_ECHO, content)
                                        autoRespond.send(echoPack.toByteArray(), autoRemote)
                                    }
                                    else -> println("Error DataType")
                                }
                            }
                            else -> println("Error HandType")
                        }
                    }
                }
            }
            println("启动自动回复：${autoRespond.start()}")
            //endregion

            //region    测试包的时间
            val tickMap = HashMap<Int,Long>()
            target.setOnRecvProcess { state, datagramPacket ->
                val handType = PackDefinition.getHandType(datagramPacket)
                val dataType = PackDefinition.getDataType(datagramPacket)
                when(handType){
                    HAND_TEST -> {
                        when(dataType){
                            DATA_ECHO -> {
                                if (state == 0){
                                    target.updateState(1)
                                    println("First 耗时 ${Thread.currentThread()}")
                                }
                                //测量时间间隔
                                val recvId = datagramPacket.data.toInt(datagramPacket.offset+PackDefinition.contentOffset)

                                if (datagramPacket.length != 1472) println("数据长度不对")
                                //synchronized(tickMap){
                                    if (tickMap.containsKey(recvId)) {
                                        println("$recvId 耗时: ${System.currentTimeMillis() - tickMap[recvId]!!}")
                                    }else{
                                        println("not key:$recvId")
                                    }
                                //}
                            }
                            else -> println("Error DataType")
                        }
                    }
                    else -> println("Error HandType")
                }
            }
            println("启动时间检测：${target.start()}")
            //endregion

            var sendId = 100
            val targetRemote = autoRespond.localIpAndPort
            Thread.sleep(300)

            var sendBeginSpan = 0L
            //region    发送数据，进行测试
            Observable.create<Int> {
                for (i in 1..1000){
                    val fixData = ByteArray(1470, {it.toByte()})
                    sendId++

                    fixData.fromInt(sendId, 0)
                    val pack = PackDefinition(HAND_TEST, DATA_PING, fixData)
                    //Thread.sleep(300)
                    //synchronized(tickMap){
                        tickMap.put(sendId, System.currentTimeMillis())
                    //}
                    Thread.sleep(1)
                    target.send(pack.toByteArray(), targetRemote)
                }
                println("First 发送 ${Thread.currentThread()}")
                it.onComplete()
            }.subscribeOn(Schedulers.io())
                    .subscribe({},{},
                            {
                                println("发送数据持续时间：${System.currentTimeMillis()-sendBeginSpan} 毫秒")
                            },
                            {
                                sendBeginSpan = System.currentTimeMillis()
                            })
            //endregion

            Thread.sleep(3_000)
            println("关闭")
            autoRespond.close()
            target.close()
        }
    }
    /**
     * 能否工作
     */
    var canWork: Boolean = false
        private set
    /**
     * 获得核心（socket）
     * 无法工作的情况下，触发异常
     */
    val core:DatagramSocket get() = socket
    private lateinit var socket: DatagramSocket
    var localIpAndPort: IpAndPort = IpAndPort("",0)
        private set
    private var listeningDisposable: Disposable? = null
    private var onReceived:((Int,DatagramPacket)->Unit)? = null
    private var state:Int = 0  //这个状态由用户控制，注意0是无效状态
    protected val keyLog:((String)->Unit)? = { println("$it from ${this.javaClass.simpleName}:$localIpAndPort")}

    init {
        try {
            socket = DatagramSocket(localPort, InetAddress.getByName(localIp))
            localIpAndPort = IpAndPort(socket.localAddress.hostAddress, socket.localPort)
            canWork = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    /**
     * 启动，即监听
     * @return 是否启动成功（通常给UI状态用）
     */
    fun start():Boolean{
        if (canWork.not()){
            return false
        }
        //已启动，返回true
        if (listeningDisposable!=null && listeningDisposable!!.isDisposed.not()){
            return true
        }
        //启动
        val source = Observable.create<Unit> {

            while (it.isDisposed.not() && canWork){
                //动态分配
                val buf = ByteArray(1472)
                val packet = DatagramPacket(buf, buf.size)

                try {
                    socket.receive(packet)
                }catch (e:Exception){
                    //it.onError(e)  //上返并跳出
                }
                if (it.isDisposed.not()){
                    onReceived?.invoke(state, packet)
                }
            }
            it.onComplete()
        }.doOnDispose { keyLog?.invoke("Disposed") }
        listeningDisposable = source.subscribeOn(Schedulers.io())
                .subscribe({},{keyLog?.invoke("Error")},{keyLog?.invoke("Complete")})
        return true
    }
    /**
     * 关闭
     */
    fun close(){
        stop()
        if (canWork && socket.isClosed.not()){
            socket.close()
        }
    }
    fun setOnRecvProcess(onReceived:(preState:Int, packet:DatagramPacket)->Unit){
        this.onReceived = onReceived
    }
    fun updateState(state:Int){
        this.state = state
    }
    /**
     * 停止监听
     */
    fun stop(){
        listeningDisposable?.dispose()
        listeningDisposable = null
    }
    fun send(byteArray: ByteArray, remote: IpAndPort):Boolean {
        if (canWork.not()) {
            return false
        }
        try {
            socket.send(DatagramPacket(byteArray, byteArray.size,remote.inetAddr,remote.port))
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}

/*fun main(argv:Array<String>){
    UdpSocket.main(argv)
    Thread.sleep(500)
}*/
