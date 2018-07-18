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
 */

class MainActivity:BaseLinkActivity(),AnkoLogger{
    override val loggerTag: String
        get() = "_main"
    private var msgIndex = 0

    /**
     * 此方法不在主线程中
     */
    override fun receiveAppClient(packet: DatagramPacket) {
        msgIndex++
        info { "No.$msgIndex ${String(packet.data)} ${packet.address.hostName}" }
        logLine("app.link ${packet.address.hostAddress},${String(packet.data)}")
    }
}
