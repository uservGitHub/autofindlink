package link.find.auto.client

import android.graphics.Color
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import book.hill.gxd.voice.SimpleRecognition
import org.jetbrains.anko.*
import org.jetbrains.anko.sdk25.coroutines.onClick
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket

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
    lateinit var scroll: ScrollView
    lateinit var tvLog: TextView
    //endregion

    //region    speech
    private lateinit var recog: SimpleRecognition
    private lateinit var recogText:TextView
    private lateinit var recogName:TextView
    private var isSpeechWorking = false
    //endregion

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //val localIp = BroadcastAddress.getLocalhostIp()
        val localIp = CommConfig.localIps.first()

        //先开启应用Socket处理
        appSocket = AppSocket(localIp)
        groupSocket = GroupSocket(CommConfig.groupIp,CommConfig.groupPort)

        if (!appSocket.canWork || appSocket.port <= 0){
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
            button("Load"){
                onClick {
                    if(!isSpeechWorking) {
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
            button("Close"){
                onClick {
                    if (isSpeechWorking) {
                        isSpeechWorking = false
                        clearText()
                        recog.dispose()
                    }
                }
            }
            etText = editText {
                hint = "发送内容"
            }.lparams(matchParent, wrapContent)
            btnSend = button("发送") {
                isEnabled = false
                onClick {
                    doAsync {
                        val isSendOk = sendAppData(etText.text.toString().trim())
                        logLine(if (isSendOk) "发送成功" else sendError)
                    }
                }
            }.lparams(matchParent, wrapContent)
            scroll = scrollView {
                tvLog = textView {
                    textSize = sp(8).toFloat()
                }.lparams(matchParent, matchParent)
            }.lparams(matchParent, matchParent)
        }

        // 开启
        if (appSocket.canWork){
            appSocket.resetReceive()
            logLine("启动应用接收 ${appSocket.addr}:${appSocket.port}")
        }
        if (groupSocket.canWork){
            groupSocket.resetSend()
            logLine("启动组播接收 ${CommConfig.groupIp}:${CommConfig.groupPort}")
        }
    }

    private fun clearText(){
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
        if (groupSocket.canWork){
            groupSocket.close()
        }
        if (appSocket.canWork){
            appSocket.close()
        }
        super.onDestroy()
        if(isSpeechWorking) {
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
    protected fun sendAppData(msg:String):Boolean{
        if (serverPort <= 0){
            return false
        }
        var socket:DatagramSocket? = null
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
        }finally {
            socket?.close()
        }
        return false
    }

    /**
     * 接收服务端的应用数据报（这里只有服务器连接配置）
     * 一旦收到配置，将不再发送检测广播
     */
    private fun receiveAppServer(packet:DatagramPacket):Boolean {
        //解析服务器发来的数据
        try {
            val json = JSONObject(String(packet.data))
            val port = json.optInt("port")
            val addr = json.optString("addr")
            if (port > 0) {
                serverPort = port
                serverIp = addr
                //结束广播
                groupSocket.close()
                btnSend.post { btnSend.isEnabled = true }
                return true
            }
            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * 应用UdpSocket处理
     * 1 收到服务器的应用配置，结束该socket
     */
    inner class AppSocket(val localAddr:String){
        var canWork: Boolean = false
            private set
        var addr:String = ""
            private set
        var port:Int = 0
            private set
        private var isStop = false
        private lateinit var socket:DatagramSocket
        init {
            try {
                socket = DatagramSocket()
                //可能需要获取IPV4
                addr = socket.localAddress.hostName.takeIf { it.length>"::".length } ?: localAddr
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
        fun resetReceive(stop:Boolean = false):Boolean {
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
         * 关闭
         */
        fun close(){
            if (canWork){
                resetReceive(true)
                if (socket.isClosed.not()){
                    socket.close()
                }
            }
        }

        /**
         * 接收处理
         */
        private fun receive(){
            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, 0, buf.size)
            doAsync {
                try {
                    while (!isStop) {
                        socket.receive(packet)
                        // 应用处理
                        if(receiveAppServer(packet)){
                            //自动停止
                            isStop = true
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
    inner class GroupSocket(val addr: String,val port: Int) {
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
        fun resetSend(stop:Boolean = false):Boolean {
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
        fun close(){
            if (canWork){
                resetSend(true)
                if (socket.isClosed.not()){
                    socket.close()
                }
            }
        }

        /**
         * 发送处理
         */
        private fun send(){
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
                        logLine("发送组播")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
