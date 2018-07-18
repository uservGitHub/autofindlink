package link.find.auto.server

import android.graphics.Color
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import org.jetbrains.anko.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket

/**
 * 服务端UDP：
 * 1 加入组播并开启组播监听
 * 2 开启应用UDP接收
 * 3 收到组播，进行识别并回复
 * 4 收到应用端的UDP数据，做正常业务处理
 *
 * 注意：
 * 1 先启动应用接收（随机端口）
 * 2 再启动组播接收（客户端的临时应用端，用于客户端接收服务端随机端口配置）
 * 3 应用接收进行正常接收处理（应用数据）
 *
 * 修改：将客户端udp包处理进行上提
 */
abstract class BaseLinkActivity : AppCompatActivity() {
    //组播Socket
    lateinit var groupSocket: GroupSocket
    //应用Socket
    lateinit var appSocket: AppSocket
    //本地IP
    lateinit var localIps: List<String>
    //初始化错误信息
    private var error: String = ""
    //region UI
    lateinit var scroll: ScrollView
    lateinit var tvLog: TextView
    //endregion

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localIps = CommConfig.localIps

        //先开启应用Socket处理
        appSocket = AppSocket()
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
            scroll = scrollView {
                tvLog = textView {
                    textSize = sp(11).toFloat()
                }.lparams(matchParent, matchParent)
            }.lparams(matchParent, matchParent)
        }

        // 开启
        if (appSocket.canWork){
            appSocket.resetReceive()
            logLine("启动应用接收 ${appSocket.addr}:${appSocket.port}")
        }
        if (groupSocket.canWork){
            groupSocket.resetReceive()
            logLine("启动组播接收 ${CommConfig.groupIp}:${CommConfig.groupPort}")
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        if (groupSocket.canWork){
            groupSocket.close()
        }
        if (appSocket.canWork){
            appSocket.close()
        }
        super.onDestroy()
    }

    protected fun logLine(str: String) {
        val line = if (str.endsWith('\n')) str else "$str\n"
        scroll.post {
            tvLog.append(line)
            scroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    /**
     * 发送服务端的应用配置
     * @param clientAddr 客户端IP
     * @param clientPort 客户端监听端口
     */
    private fun sendAppConfig(clientAddr:String, clientPort:Int){
        // 选取同网段的本机IP
        val items = clientAddr.split('.')
        val findAddr = localIps.find {
            val itItems = it.split('.')
            items[0].equals(itItems[0]) && items[1].equals(itItems[1]) && items[2].equals(itItems[2])
        }
        if (findAddr == null){
            logLine("无客户端(${clientAddr})同网段IP")
            return
        }
        //{"serverId":"adef","port":8890,"addr":"127.0.0.1"}
        val params = LinkedHashMap<String, Any>()
        params["addr"] = findAddr
        params["port"] = appSocket.port
        try {
            val socket = DatagramSocket()
            val buf = JSONObject(params).toString().toByteArray()
            val packet = DatagramPacket(buf, buf.size,
                    InetAddress.getByName(clientAddr),
                    clientPort)
            socket.send(packet)
        } catch (e: Exception) {
            e.printStackTrace()
            logLine("向客户端(${clientAddr}:${clientPort})发送数据出错")
        }
    }

    /**
     * 接收客户端的应用数据报
     * 打印 app.link 客户端IP,客户端数据
     */
    abstract protected fun receiveAppClient(packet:DatagramPacket)

    /**
     * 应用UdpSocket处理
     */
    inner class AppSocket(){
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
                addr = socket.localAddress.hostName
                port = socket.localPort
                canWork = true
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
                        receiveAppClient(packet)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }


    /**
     * 组播Socket处理
     */
    inner class GroupSocket(addr: String, port: Int) {
        var canWork: Boolean = false
            private set
        private var isStop = false
        private lateinit var socket: MulticastSocket

        init {
            try {
                socket = MulticastSocket(port).apply {
                    joinGroup(InetAddress.getByName(addr))
                    timeToLive = 128
                }
                canWork = true
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
                        logLine("收到组播:IP=${packet.address.hostName}")
                        //解析数据,合法 {"clientId":"adef","port":8890,"addr":"127.0.0.1"}
                        val json = JSONObject(String(packet.data))
                        val port = json.optInt("port")
                        val addr = json.optString("addr")
                        //发送应用端口和IP
                        sendAppConfig(addr, port)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
