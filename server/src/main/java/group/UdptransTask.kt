import group.fromInt
import group.toInt
import java.io.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.LinkedList



/**
 * Udp传输任务
 * @param localIp 可以指定本地接收IP地址，默认自动获取
 */
class UdptransTask private constructor(localIp: String, localPort:Int) {
    companion object {
        fun create() = UdptransTask("", 0)
        fun create(port: Int) = UdptransTask("", port)
        fun create(ip: String) = UdptransTask(ip, 0)
        fun create(ip: String, port: Int) = UdptransTask(ip, port)

        val DATA_HEAD_SIZE = 10
        var MAX_DATA_SIZE = 1468
        val NAME_PORT: Byte = 11 //端口号
        val NAME_IP: Byte = 12 //IP地址
        val NAME_FILE: Byte = 21 //文件
        val NAME_PRINT: Byte = 31 //打印信息
        private val NAME_CMD_RESET: Byte = 51 //开始命令
        val NAME_CMD_FINISH_STREAM: Byte = 52 //完成流的传输
        val NAME_CMD_EMPTY: Byte = 53 //无内容

        val NAME_STREAM: Byte = -1 //流信息
        val NAME_ECHO:Byte = -2 //响应
        val NAME_FILEPART:Byte = -3 //文件的一部分
    }
    var type = 0 //0无效，1上推，2下拉
        private set
    @Volatile
    private var state = 0
    private val sigLock = java.lang.Object()

    //region    Socket相关（初始化本地Socket）
    val isSameSocket = true
    private lateinit var remoteAddr: InetAddress
    private var remotePort: Int = 0

    /**
     * 设置远程IP和端口
     * @param remoteIp 远程IP
     * @param remotePort 远程PORT
     */
    fun setRemoteIpAndPort(remoteIp: ByteArray, remotePort: Int) {
        this.remoteAddr = InetAddress.getByAddress(remoteIp)
        this.remotePort = remotePort
    }

    lateinit var localAddr: InetAddress
        private set
    var localPort: Int = 0
        private set
    //同一模式下的收和发，可能是相同的sokect，也可能是不同的socket
    private var socketForSend: DatagramSocket? = null
    private var socketForRecv: DatagramSocket? = null
    var canWork = false
        private set
    init {
        try {
            socketForRecv =
                    if (localIp.length > 0) DatagramSocket(localPort, InetAddress.getByName(localIp))
                    else DatagramSocket(localPort)

            this.localAddr = socketForRecv!!.localAddress
            this.localPort = socketForRecv!!.localPort

            if(isSameSocket){
                socketForSend = socketForRecv
            }else{
                socketForSend = DatagramSocket()
            }
            canWork = true
        } catch (e: SocketException) {
            println("创建接收Scoket失败（Socket异常）：${e.message}")
        } catch (e: Exception) {
            println("创建接收Socket失败（非Socket异常）：${e.message}")
        }
    }
    //endregion

    //region    Thread相关（线程初始化及启动）
    private var taskThread: Thread? = null
    @Volatile
    var isStopTask = false
        private set
    private val errs = mutableListOf<String>()
    val errors: List<String>
        get() = errs
    private val errFmt: (funName: String, message: String?) -> String = { funName, message ->
        //val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")//设置日期格式// new Date()为获取当前系统时间
        val timeDf = SimpleDateFormat("HH:mm:ss")
        "Exception:${this.javaClass.simpleName}:$funName:${Thread.currentThread()}:${timeDf.format(Date())}:\n$message"
    }



    //endregion

    //region    应用接口（下拉）
    //下拉字节流，参数（流名称，流内容）
    private var onPulledBytes:((name:Byte,bytes:ByteArray)->Unit)? = null
    fun setOnPulledBytes(action:(name:Byte,bytes:ByteArray)->Unit){
        this.onPulledBytes = action
    }
    //下拉文件，参数（文件大小，文件名称），完成后触发
    private var onPulledFile:((size:Int,name:String)->Unit)? = null
    //存储文件的目录
    private var fileDir:File? = null
    fun setOnPulledFile(fileDir:File, action:(size:Int,name:String)->Unit){
        this.fileDir = fileDir
        this.onPulledFile = action
    }
    private var pullStream:FileOutputStream? = null
    private var pullFileMessage:Pair<String,Int>? = null
    private var tickRecv = 0L
    var canPull:Boolean = false
        private set
    private var pullPacketId = 0

    private fun doPullWork():Boolean{
        println(Thread.currentThread())
        val recvBuf = ByteArray(MAX_DATA_SIZE)
        val recvPack = DatagramPacket(recvBuf, recvBuf.size)
        try {
            socketForRecv!!.receive(recvPack)

            //RESET 不校验
            if (recvPack.toName1() == NAME_CMD_RESET){
                pullPacketId = recvPack.toId()
                canPull = true
                socketForSend!!.send(buildEchoPacket())
                return true
            }

            if (canPull){
                if (recvPack.toId() == pullPacketId+1){
                    tickRecv = System.currentTimeMillis()
                    pullPacketId++
                    socketForSend!!.send(buildEchoPacket())

                    //region    拆包回调
                    val packSize = recvBuf.toInt(8, 2)
                    when(recvPack.toName1()){
                        NAME_FILE -> { //创建输出流
                            val fileSize = recvBuf.toInt(DATA_HEAD_SIZE, 4)
                            val fileName = String(recvBuf, DATA_HEAD_SIZE + 4, packSize - 4)
                            pullFileMessage = Pair(fileName, fileSize)
                            val file = File(fileDir!!.absolutePath+"\\"+fileName)
                            try {
                                if (file.exists()){
                                    file.createNewFile()
                                }
                                pullStream = FileOutputStream(file)
                            }catch (e:Exception){
                                println("无法创建文件：${e.message}")
                            }
                        }
                        NAME_PRINT, NAME_STREAM -> {
                            onPulledBytes!!.invoke(recvPack.toName1(), recvPack.toInnerPack1())
                        }
                        NAME_FILEPART -> {
                            pullStream!!.write(recvBuf, DATA_HEAD_SIZE, packSize)
                        }
                        NAME_CMD_FINISH_STREAM -> {
                            pullStream!!.close()
                            onPulledFile!!.invoke(pullFileMessage!!.second,pullFileMessage!!.first)
                            pullStream = null
                            pullFileMessage = null
                        }
                    }
                    if (recvPack.hasInnerPack2()){
                        when(recvPack.toName2()){
                            NAME_FILEPART -> {
                                pullStream!!.write(recvBuf, DATA_HEAD_SIZE, packSize)
                            }
                            NAME_CMD_FINISH_STREAM -> {
                                pullStream!!.close()
                                onPulledFile!!.invoke(pullFileMessage!!.second,pullFileMessage!!.first)
                                pullStream = null
                                pullFileMessage = null
                            }
                        }
                    }

                    //endregion

                    return true
                }else{
                    println("接收ID错：${recvPack.toId()} != ${pullPacketId+1}")
                }
            }
        }catch (e:IOException){
            println("接收传输包异常：${e.message}")
        }
        return false
    }
    /**
     * 开启下拉任务
     */
    fun startPullTask():Boolean{
        if (taskThread != null){
            return type == 2
        }
        if (canWork.not()){
            println("未能启动下拉任务：Scoket不能正常工作")
            return false
        }
        try {
            type = 2
            isStopTask = false
            val executor = Executors.newCachedThreadPool().submit {
                while (isStopTask.not()){
                    if(doPullWork().not()){
                        //println("拆包错误")
                    }
                }
            }
            /*taskThread = Thread({
                while (isStopTask.not()){
                    if(doPullWork().not()){
                        //println("拆包错误")
                    }
                }
            }).apply {
                isDaemon = true
                type = 2
                isStopTask = false
                //socketForRecv!!.soTimeout = 1200  //以后可以动态调整
                start()
            }*/
            return true
        }catch (e:Exception){
            errs.add(errFmt("startPullTask", e.message))
        }finally {
            type = 0
            taskThread?.let {
                if (it.isAlive) {
                    it.interrupt()
                    it.join()
                }
            }
            taskThread = null
        }
        return false
    }
    //endregion

    /**
     * 停止任务
     */
    fun stop() {
        if (isStopTask.not()) {
            isStopTask = true
            Thread.yield()
            socketForRecv?.close()
            socketForSend?.close()
            Thread.sleep(500)
            try {
                taskThread?.let {
                    if (it.isAlive) {
                        it.interrupt()
                        it.join()
                    }
                }
                taskThread = null
            }catch (e:Exception){
                errs.add(errFmt("stop", e.message))
            }
        }
    }

    //region    应用接口（上推）
    /**
     * 开启上推任务
     */
    fun startPushTask():Boolean{
        if (taskThread != null){
            return type == 1
        }
        if (canWork.not()){
            println("未能启动上推任务：Scoket不能正常工作")
            return false
        }
        try {
            type = 1
            isStopTask = false
            atomId.set(1)
            //socketForRecv!!.soTimeout = 1200
            val executor = Executors.newCachedThreadPool().submit {
                while (isStopTask.not()){
                    doPushWork()

                    //Thread.sleep(3000)
                }
                println("循环外：state=$state,isStopTask=$isStopTask")
            }
            /*taskThread = Thread({
                while (isStopTask.not()){
                    doPushWork()
                }
            }).apply {
                isDaemon = true
                type = 1
                isStopTask = false
                atomId.set(1)
                socketForRecv!!.soTimeout = 1200  //以后可以动态调整
                start()
            }*/
            return true
        }catch (e:Exception){
            errs.add(errFmt("startPullTask", e.message))
        }finally {
            type = 0
            taskThread?.let {
                if (it.isAlive) {
                    it.interrupt()
                    it.join()
                }
            }
            taskThread = null
        }
        return false
    }

    /**
     * 上推文件
     * @param file 文件
     * @return 检查大小、类型或其他，通过返回true；反之false
     */
    fun pushFile(file:File):Boolean{
        //校验通过，创建输入流，加入队尾
        if (file.exists() && file.isFile && file.length() <= Int.MAX_VALUE.toLong()){
            pushFileList.add(file)
            return true
        }
        return false
    }

    /**
     * 上推字符串
     * @param str 字符串，超过容量被自动截断
     */
    fun pushString(str:String){
        pushWorkList.add(Pair(NAME_PRINT, str))
        if (state == 1){
            synchronized(sigLock){
                if(state == 1) {
                    sigLock.notify()
                }
            }
        }
    }

    /**
     * 上推字节流
     * @param bytes 字节流
     */
    fun pushByteArray(bytes:ByteArray){
        pushWorkList.add(Pair(NAME_STREAM, bytes))
    }
    //上推JSON或其他
    private fun pushOther()=Unit
    private val pushWorkList = mutableListOf<Pair<Byte,Any>>()
    private val pushFileList = mutableListOf<File>()
    private var lastPushPacket:DatagramPacket? = null
    private var tickEcho = 0L
    private var pushStream:InputStream? = null
    @Volatile
    var canPush:Boolean = false
        private set

    /**
     * 上推工作，只能单线程调用
     */
    private fun doPushWork() {
        println("dopushwork state=$state")
        synchronized(sigLock){
            if (state == 2){
                state = 1
                println("等待...")
                sigLock.wait()
                state = 0
            }
        }
        if (lastPushPacket != null) {
            println("  重发 ${atomId.get()}")
            socketForSend!!.send(lastPushPacket)
        } else if (canPush.not()) {
            val sendBuf = ByteArray(DATA_HEAD_SIZE)
            sendBuf[4] = NAME_CMD_RESET
            sendBuf.fromInt(atomId.get(), 0, 4)
            lastPushPacket = DatagramPacket(sendBuf, sendBuf.size, remoteAddr, remotePort)
            println("发送 RESET ${atomId.get()}")
            socketForSend!!.send(lastPushPacket)
        } else {
            val sendBuf = ByteArray(MAX_DATA_SIZE)
            var hasWorkPack = false
            var workPackSize = 0

            //region    试图创建包1
            if (pushWorkList.size > 0) {
                val work = pushWorkList.removeAt(0)
                when (work.first) {
                    NAME_PRINT, NAME_STREAM -> {
                        sendBuf[4] = work.first
                        val tmpBuf =
                                if (work.first == NAME_PRINT) (work.second as String).toByteArray()
                                else work.second as ByteArray
                        workPackSize = Math.min(tmpBuf.size, sendBuf.size - DATA_HEAD_SIZE)
                        sendBuf.fromInt(tmpBuf.size, 8, 2)
                        System.arraycopy(tmpBuf, 0, sendBuf, DATA_HEAD_SIZE, workPackSize)
                        hasWorkPack = true
                    }
                }
            }
            //endregion

            //region    不存在包1的情况下，才能试图创建NAME_FILE
            if (hasWorkPack.not() && pushStream == null && pushFileList.size > 0) {
                try {
                    val tmpFile = pushFileList.removeAt(0)
                    pushStream = FileInputStream(tmpFile)
                    sendBuf[4] = NAME_FILE
                    val tmpBuf = tmpFile.name.toByteArray()
                    workPackSize = Math.min(tmpBuf.size+4, sendBuf.size - DATA_HEAD_SIZE)
                    sendBuf.fromInt(workPackSize, 8, 2)
                    sendBuf.fromInt(tmpFile.length().toInt(), DATA_HEAD_SIZE, 4)
                    System.arraycopy(tmpBuf, 0, sendBuf, DATA_HEAD_SIZE + 4, workPackSize - 4)
                    hasWorkPack = true
                } catch (e: FileNotFoundException) {
                    println("创建上拉流失败：${e.message}")
                } catch (e: Exception) {
                    println("创建上拉流失败：${e.message}")
                }
            }
            //endregion

            //region    试图添加流
            val offset = DATA_HEAD_SIZE + workPackSize
            //剩余容量
            val remainPackSize = sendBuf.size - offset

            if (pushStream != null && remainPackSize > 0) {
                val readSize = pushStream!!.read(sendBuf, offset, remainPackSize)
                println("readSize = $readSize")
                if (readSize == -1) {
                    if (hasWorkPack) sendBuf[5] = NAME_CMD_FINISH_STREAM
                    else sendBuf[4] = NAME_CMD_FINISH_STREAM
                    pushStream!!.close()
                    pushStream = null
                    hasWorkPack = true
                } else if (readSize > 0) {
                    if (hasWorkPack) sendBuf[5] = NAME_FILEPART
                    else {
                        sendBuf[4] = NAME_FILEPART
                        sendBuf.fromInt(readSize, 8, 2)
                    }
                    workPackSize += readSize
                    hasWorkPack = true
                }
            }
            //endregion

            //发送数据包
            if (hasWorkPack.not()) {
                sendBuf[4] = NAME_CMD_EMPTY //无包时，置空包
                state = 2
                println("没有数据要发送，返回")
                return
            }
            sendBuf.fromInt(atomId.get(), 0, 4)
            lastPushPacket = DatagramPacket(sendBuf, DATA_HEAD_SIZE + workPackSize, remoteAddr, remotePort)
            println("发送新数据 ${atomId.get()}")
            socketForSend!!.send(lastPushPacket)
        }

        try {
            val recvBuf = ByteArray(DATA_HEAD_SIZE)
            val recvPack = DatagramPacket(recvBuf, recvBuf.size)
            socketForRecv!!.receive(recvPack)
            println("收到响应 ${recvPack.data.toInt(0, 4)}")
            if (checkEcho(recvPack)) {
                if(canPush.not()){
                    canPush = true
                    //里面可能有初始化连接数据
                    //...
                }
                atomId.incrementAndGet()
                tickEcho = System.currentTimeMillis()
                lastPushPacket = null
            }
        } catch (e: IOException) {
            println("接收回声包异常：${e.message}")
        }
    }

    //endregion

    //region    数据装包/拆包
    fun checkEcho(packet: DatagramPacket):Boolean{
        return packet.toId() == atomId.get() && packet.toName1() == NAME_ECHO
    }

    fun buildEchoPacket():DatagramPacket{
        println("要响应 $pullPacketId")
        val echoBuf = ByteArray(DATA_HEAD_SIZE)
        echoBuf.fromInt(pullPacketId, 0, 4)
        echoBuf[4] = NAME_ECHO
        return DatagramPacket(echoBuf, echoBuf.size, remoteAddr, remotePort)
    }

    private inline fun DatagramPacket.toName1(): Byte {
        return this.data[this.offset + 4]
    }

    private inline fun DatagramPacket.toName2(): Byte {
        return this.data[this.offset + 5]
    }

    private inline fun DatagramPacket.toId(): Int {
        return this.data.toInt(this.offset, 4)
    }

    private inline fun DatagramPacket.hasInnerPack2(): Boolean {
        return this.data[this.offset + 5].toInt() != 0
    }

    private inline fun DatagramPacket.toInnerPack1(): ByteArray {
        val packSize = this.data.toInt(this.offset + 8, 2)
        val packBeg = this.offset + 10
        val buf = ByteArray(packSize)
        System.arraycopy(this.data, packBeg, buf, 0, packSize)
        return buf
    }

    private inline fun DatagramPacket.toInnerPack2(): ByteArray {
        val packSize1 = this.data.toInt(this.offset + 8, 2)
        val packBeg = this.offset + 10 + packSize1
        val packSize = this.length - packBeg
        val buf = ByteArray(packSize)
        System.arraycopy(this.data, packBeg, buf, 0, packSize)
        return buf
    }

    //原子Id，每一个上推工作都有唯一的一个id，与相应的回声Id相等
    private val atomId = AtomicInteger(-1)
    //endregion
}

class Test{

}
//如何使用？？
fun main(argv:Array<String>) {
    var server = UdptransTask.create("192.168.200.201", 50201).apply {
        setOnPulledBytes { name, bytes ->
            //根据name进行解析
            println("server 收到 name=$name content=${String(bytes)}")
        }
        setOnPulledFile(File("D:\\tServer\\"),
                {size: Int, name: String ->
                    println("server 收到 文件：$name")
                })
    }

    var client = UdptransTask.create("192.168.200.200", 50200).apply {
        setRemoteIpAndPort(server.localAddr.address, server.localPort)
    }
    server.setRemoteIpAndPort(client.localAddr.address, client.localPort)
    //server 向外广播，client 获得地址，进行发送...
    if(server.startPullTask()) println("server 启动 下拉任务")
    if(client.startPushTask()) println("client 启动 上推任务")

    //上推内容...
    Executors.newCachedThreadPool().submit {
        println(Thread.currentThread())
        //Thread.sleep(1000)
        //client.pushString("Hello 服务器1！")
        //client.pushString("Hello 服务器2！")
        //client.pushString("Hello 服务器3！")
        client.pushFile(File("D:\\path地址.txt"))
        //Thread.sleep(30)
        //client.pushString("Hello 服务器4！")

    }
/*    Thread({
        Thread.sleep(5000)
        client.pushString("Hello 服务器！")
        //client.pushByteArray("字节流".toByteArray())
        //client.pushFile(File("D:\\path地址.txt"))
    }).apply {
        isDaemon = true
        start()
    }*/
    println("打印")

    Thread.sleep(19_000)
    client.stop()
    server.stop()
    println("--完毕--")
}










