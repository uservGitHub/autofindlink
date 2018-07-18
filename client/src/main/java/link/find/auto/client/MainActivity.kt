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
 */
class MainActivity:BaseLinkActivity(),AnkoLogger{

}
