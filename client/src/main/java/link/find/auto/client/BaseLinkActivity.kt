package link.find.auto.client

import android.graphics.Color
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import book.hill.gxd.voice.SimpleRecognition
import org.jetbrains.anko.*
import org.jetbrains.anko.sdk25.coroutines.onClick
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.*


/**
 * 客户端UDP：
 * 1 开启应用UDP接收
 * 2 加入组播并定时发送组播
 * 3 收到组播，获取服务器应用IP及端口，停止组播发送
 * 4 正常向服务器发送
 * 补充：
 * 1 应用Socket作用，只要收到服务器IP和端口，就完成了任务，关闭
 * 2 只要收到服务器IP和端口，组播Socket就没必要广播了，关闭
 * 3 进行应用数据的发送（不缓存socket）
 *
 * 修改：将向服务器发送信息的方法向子类打开
 * 修改2：每发一包数据都要创建Socket再删除、关闭，速度慢，应用Socket予以保留。
 * 修改3：1 应用Socket可以接收处理的类型必须是带标志的
 *   2 与服务器的接收和发送是交互过程
 *   向服务器请求发送流 --> 服务器相应 -->
 *   一直发送 --> 直到收到服务器的结束标志才算完成。
 */
open class BaseLinkActivity : AppCompatActivity() {
    //组播Socket
    lateinit var groupSocket: GroupSocket
    //应用Socket
    lateinit var appSocket: AppSocket
    //本地IP
    //lateinit var localIps: List<String>
    //初始化错误信息
    private var error: String = ""
    var serverIp = ""
    var serverPort = 0
    var sendError = ""
    //region UI
    lateinit var etText: EditText
    lateinit var btnSend: Button
    lateinit var btnFileSend: Button
    lateinit var scroll: ScrollView
    lateinit var tvLog: TextView
    //endregion

    //region    speech
    private lateinit var recog: SimpleRecognition
    private lateinit var recogTrackInMs: TextView
    private lateinit var recogText: TextView
    private lateinit var recogName: TextView
    private var isSpeechWorking = false
    //endregion

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //val localIp = BroadcastAddress.getLocalhostIp()
        val localIp = CommConfig.localIps.first()

        //先开启应用Socket处理
        appSocket = AppSocket(localIp)
        groupSocket = GroupSocket(CommConfig.groupIp, CommConfig.groupPort)

        if (!appSocket.canWork || appSocket.port <= 0) {
            error += "无法启动应用Socket"
        }
        if (!groupSocket.canWork) {
            if (error.length > 0) {
                error += "且"
            }
            error += "无法启动组播Socket"
        }
        if (error.length > 0) {
            setContentView(TextView(this).apply {
                textColor = Color.RED
                textSize = sp(12).toFloat()
                text = error
                gravity = Gravity.CENTER
                layoutParams = ViewGroup.LayoutParams(matchParent, matchParent)
            })
            return
        }

        var btnTemp1: Button? = null

        verticalLayout {

            recogText = textView {
                gravity = Gravity.CENTER
                textSize = sp(10).toFloat()
            }.lparams(wrapContent, wrapContent)
            scrollView {
                recogName = textView {
                    //backgroundColor = Color.GREEN
                    textSize = sp(8).toFloat()
                }.lparams(matchParent, matchParent)
            }.lparams(matchParent, dip(100))
            verticalLayout {
                orientation = LinearLayout.HORIZONTAL
                button("Load") {
                    onClick {
                        if (!isSpeechWorking) {
                            recog = SimpleRecognition(this@BaseLinkActivity).apply {
                                setOnResult { no, text ->
                                    recogText.text = "${no}. $text"
                                }
                                setOnName { no, text ->
                                    recogName.text = "${recogName.text}${no}. $text\n"
                                }
                            }
                            clearText()
                            recog.starApp()
                            isSpeechWorking = true
                        }
                    }
                }
                button("Close") {
                    onClick {
                        if (isSpeechWorking) {
                            isSpeechWorking = false
                            clearText()
                            recog.dispose()
                        }
                    }
                }
            }.lparams(matchParent, wrapContent)
            recogTrackInMs = editText {
                setText("1500")
            }.lparams(matchParent, wrapContent)
            etText = editText {
                hint = "发送内容"
            }.lparams(matchParent, wrapContent)
            verticalLayout {
                orientation = LinearLayout.HORIZONTAL
                btnTemp1 = button("开启检测") {
                    onClick {
                        // 开启
                        if (appSocket.canWork) {
                            appSocket.resetReceive()
                            logLine("启动应用接收 ${appSocket.addr}:${appSocket.port}")
                        }
                        if (groupSocket.canWork) {
                            groupSocket.resetSend()
                            logLine("启动组播接收 ${CommConfig.groupIp}:${CommConfig.groupPort}")
                        }
                        btnTemp1?.isEnabled = false
                    }
                }
                btnSend = button("发送") {
                    isEnabled = false
                    onClick {
                        doAsync {
                            val isSendOk = appSocket.sendText(etText.text.toString().trim())
                            if (!isSendOk){
                                logLine("发送失败")
                            }
                        }
                    }
                }
                btnFileSend = button("发送文件"){
                    isEnabled = false
                    onClick {
                        doAsync {
                            //4MB
                            val fileBuf = ByteArray(4*1024*1024, {-1})
                            //appSocket.send()
                        }
                    }
                }
            }

            scroll = scrollView {
                tvLog = textView {
                    textSize = sp(8).toFloat()
                }.lparams(matchParent, matchParent)
            }.lparams(matchParent, matchParent)
        }


    }

    private fun clearText() {
        recogName.text = ""
        recogText.text = ""
    }

    /**
     * onResume对应onStop
     */
    override fun onResume() {
        super.onResume()
        // 开启操作放onStart中，对应onDestroy
    }

    override fun onDestroy() {
        if (groupSocket.canWork) {
            groupSocket.close()
        }
        if (appSocket.canWork) {
            appSocket.close()
        }
        super.onDestroy()
        if (isSpeechWorking) {
            recog.dispose()
        }
    }

    private fun logLine(str: String) {
        val line = if (str.endsWith('\n')) str else "$str\n"
        scroll.post {
            tvLog.append(line)
            scroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    /**
     * 向服务器发送应用数据
     * @param msg 向服务器发送的字符串信息
     */
    @Deprecated("不再使用,请使用appSocket.send")
    protected fun sendAppData(msg: String): Boolean {
        if (serverPort <= 0) {
            return false
        }
        var socket: DatagramSocket? = null
        try {
            sendError = "未设置错误"
            socket = DatagramSocket()
            val buf = msg.toByteArray()
            val packet = DatagramPacket(buf, buf.size,
                    InetAddress.getByName(serverIp),
                    serverPort)
            socket?.send(packet)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            sendError = e.toString()
        } finally {
            socket?.close()
        }
        return false
    }

    /**
     * 接收服务端的应用数据报（这里只有服务器连接配置）
     * 一旦收到配置，将不再发送检测广播
     */
    private fun receiveServerConfig(addr:String,port:Int) {
        //结束广播
        groupSocket.close()
        btnSend.post { btnSend.isEnabled = true }
    }

    /**
     * 应用UdpSocket处理
     * 1 收到服务器的应用配置，结束该socket
     */
    inner class AppSocket(val localAddr: String) {
        var canWork: Boolean = false
            private set
        var addr: String = ""
            private set
        var port: Int = 0
            private set
        val isLinkServer: Boolean
            get() = serverPort > 0
        val serverNetIp: InetAddress by lazy {
            InetAddress.getByName(serverIp)
        }
        var serverIp = ""
            private set
        var serverPort = 0
            private set
        private val reqQueue: Queue<ByteArray> = LinkedList<ByteArray>()
        /**
         * 是否有请求列表
         */
        val hasReqList:Boolean
            get() = reqQueue.size>0
        /**
         * 获取请求列表
         */
        fun getReqList():ByteArray {
            synchronized(reqQueue) {
                if (reqQueue.size > 0) {
                    return reqQueue.poll()
                }
            }
            return ByteArray(0)
        }

        /**
         * 添加请求
         */
        private fun pushReqList(buf:ByteArray, offset: Int, length: Int) {
            val appBuf = ByteArray(length)
            System.arraycopy(buf, offset, appBuf, 0, appBuf.size)
            synchronized(reqQueue) {
                reqQueue.add(appBuf)
            }
        }
        @Deprecated("不再使用。接收应用数据时，自动赋值")
        fun serverInit(serverIp: String, serverPort: Int) {
            this.serverIp = serverIp
            this.serverPort = serverPort
        }

        private var isStop = false
        private lateinit var socket: DatagramSocket

        init {
            try {
                socket = DatagramSocket()
                //可能需要获取IPV4
                //addr = socket.localAddress.hostName.takeIf { it.length > "::".length } ?: localAddr
                addr = localAddr
                port = socket.localPort
                canWork = true
                logLine("初始化监听 ${addr}:${port}")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 重置接收
         * @param stop 是否停止接收
         */
        fun resetReceive(stop: Boolean = false): Boolean {
            if (canWork) {
                isStop = stop
                if (!isStop) {
                    receive()
                }
                return true
            }
            return false
        }

        /**
         * 发送配置
         * @param params 键值对
         */
        fun sendConf(params:LinkedHashMap<String, Any>):Boolean{
            val buf = JSONObject(params).toString().toByteArray()
            val CONF = "cnf.".toByteArray()
            val sendBuf = ByteArray(buf.size+CONF.size)
            System.arraycopy(CONF,0,sendBuf,0,CONF.size)
            System.arraycopy(buf,0,sendBuf,CONF.size,buf.size)
            return send(sendBuf, 0, sendBuf.size)
        }

        /**
         * 发送文本
         * @param msg 文本信息
         */
        fun sendText(msg: String):Boolean{
            val buf = msg.toByteArray()
            val TEXT = "txt.".toByteArray()
            val sendBuf = ByteArray(buf.size+TEXT.size)
            System.arraycopy(TEXT,0,sendBuf,0,TEXT.size)
            System.arraycopy(buf,0,sendBuf,TEXT.size,buf.size)
            return send(sendBuf, 0, sendBuf.size)
        }
        /**
         * 由其他应用调用数据发送
         * @param buf 要发送的数据流
         * @param offset 数据流起始偏移
         * @param length 数据流长度
         */
        fun send(buf: ByteArray, offset: Int, length: Int) :Boolean{
            if (canWork && isLinkServer) {
                val packet = DatagramPacket(buf, offset, length,
                        serverNetIp,
                        serverPort)
                try {
                    socket.send(packet)
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return false
        }

        /**
         * 关闭
         */
        fun close() {
            if (canWork) {
                resetReceive(true)
                if (socket.isClosed.not()) {
                    socket.close()
                }
            }
        }

        /**
         * 接收处理
         */
        private inline fun receive() {
            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, 0, buf.size)
            doAsync {
                try {
                    while (!isStop) {
                        socket.receive(packet)
                        //数据由head和body组成
                        val data = packet.data
                        val headSize = 4
                        val bodySize = data.size - headSize
                        if (data.size< headSize){
                            //不合法
                            logLine("数据长度小于$headSize，不合法")
                        }else{
                            val head = String(data, 0 ,headSize)

                            when(head){
                                "cnf." -> {
                                    //region    配置处理
                                    val json = JSONObject(String(data, headSize, bodySize))
                                    val port = json.optInt("port", 0)
                                    val addr = json.optString("addr", "")
                                    //只赋值一次
                                    if (serverPort == 0 && port > 0) {
                                        serverPort = port
                                        serverIp = addr
                                        receiveServerConfig(serverIp, serverPort)
                                    }
                                    //endregion
                                }
                                "txt." -> {
                                    //region    文本消息
                                    logLine(String(data, headSize, bodySize))
                                    //endregion
                                }
                                "req." -> {
                                    //region    请求重发
                                    if (bodySize > 0 && bodySize.rem(4) == 0) {
                                        pushReqList(data, headSize, bodySize)
                                    }
                                    //endregion
                                }
                                "end." -> {
                                    //region    服务端接收完毕
                                    //endregion
                                }
                                "srt." -> {
                                    //region    服务端开始接收
                                    //rendregion
                                }
                            }
                        }

                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }


    /**
     * 组播Socket处理
     * @param addr 组播地址
     * @param port 组播端口
     */
    inner class GroupSocket(val addr: String, val port: Int) {
        var canWork: Boolean = false
            private set
        private var isStop = false
        private lateinit var socket: MulticastSocket

        init {
            try {
                socket = MulticastSocket().apply {
                    // 只发送检测，无需下面的操作
                    //joinGroup(InetAddress.getByName(addr))
                    //timeToLive = 1
                }
                canWork = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 重置发送
         * @param stop 是否停止接收
         */
        fun resetSend(stop: Boolean = false): Boolean {
            if (canWork) {
                isStop = stop
                if (!isStop) {
                    send()
                }
                return true
            }
            return false
        }

        /**
         * 关闭
         */
        fun close() {
            if (canWork) {
                resetSend(true)
                if (socket.isClosed.not()) {
                    socket.close()
                }
            }
        }

        /**
         * 发送处理
         */
        private fun send() {
            doAsync {
                try {
                    while (!isStop) {
                        Thread.sleep(3000)
                        //数据构成 {"clientId":"adef","port":8890,"addr":"127.0.0.1"}
                        val params = LinkedHashMap<String, Any>()
                        params["addr"] = appSocket.addr
                        params["port"] = appSocket.port
                        val buf = JSONObject(params).toString().toByteArray()
                        val packet = DatagramPacket(buf, buf.size,
                                InetAddress.getByName(addr),
                                port)
                        socket.send(packet)
                        //logLine("发送组播")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * 字节流传输，使用UdpSocket
     * @param buf 字节流(最大约2.1G)
     */
    inner class TransBuffer(val buf: ByteArray, val toBuf: (ByteArray) -> Unit) {

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
                this@BaseLinkActivity.appSocket.send(sendBuffer, 0, sendBuffer.size)
            } else {
                System.arraycopy(buf, packInd * UNITSIZE, sendBuffer, offset, tailByteSize)
                this@BaseLinkActivity.appSocket.send(sendBuffer, 0, offset + tailByteSize)
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

            this@BaseLinkActivity.appSocket.send(sendBuffer, 0, offset)
            this@BaseLinkActivity.appSocket.send(sendBuffer, 0, offset)
        }
        private fun sendEndPacket(){
            var offset = 0
            System.arraycopy(END, 0, sendBuffer, offset, END.size)
            offset += END.size
            CommConfig.writeIntToByteArray(packetCount, sendBuffer, offset)
            offset += 4
            CommConfig.writeIntToByteArray(tailByteSize, sendBuffer, offset, true)
            offset += 2

            this@BaseLinkActivity.appSocket.send(sendBuffer, 0, sendBuffer.size)
            this@BaseLinkActivity.appSocket.send(sendBuffer, 0, sendBuffer.size)
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
}
