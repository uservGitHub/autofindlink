package link.find.auto.server

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import group.SocketConfigure
import gxd.socket.BroadcastTask
import gxd.socket.GroupBroadcastSocket
import org.jetbrains.anko.*
import org.jetbrains.anko.sdk25.coroutines.onClick
import java.net.InetAddress

/**
 * 默认自动接收/发送组播
 * 同一地址组播信息只接收4次
 */
class LinkMainActivity:AppCompatActivity(){
    lateinit var broadcastTask:BroadcastTask
    var multicastLock: WifiManager.MulticastLock? = null
    //键=接收的IP+端口，值=接收该键的次数的UI显示
    val recvMap = mutableMapOf<Pair<String,Int>, TextView>()
    //键=接收到IP地址，值=接收到该键的次数
    val recvIpMap = mutableMapOf<String, Int>()

    //region UI
    lateinit var scroll: ScrollView
    lateinit var panel: LinearLayout
    lateinit var tvLog: TextView
    lateinit var tvLastRecv: TextView
    lateinit var btnSend: Button
    lateinit var btnStopSend: Button
    lateinit var btnRecv: Button
    lateinit var btnStopRecv: Button
    lateinit var btnClear: Button   //清空接收列表及日志

    private fun onReceivedPort(addr:ByteArray, port:Int){
        val source = Pair<String,Int>(InetAddress.getByAddress(addr).hostAddress, port)
        runOnUiThread {
            tvLastRecv.text = "addr.size=${addr.size}"
        }
        synchronized(recvMap){
            if (recvMap.containsKey(source).not()){
                runOnUiThread {
                    val newLine = TextView(this@LinkMainActivity).apply {
                        onClick { toast(text) }
                        textSize = sp(7).toFloat()
                        text = "收到 ${source.first}:${source.second} 1 次"
                        panel.addView(this, ViewGroup.LayoutParams(matchParent, wrapContent))
                    }
                    recvMap.put(source, newLine)
                }
                recvIpMap.put(source.first, 1)
                return@synchronized
            }

            //region    已存在
            //更新次数
            val count = recvIpMap[source.first]!! + 1
            recvIpMap[source.first] = count
            //更新次数的显示
            runOnUiThread {
                val tvSource = recvMap[source]!!
                tvSource.text = "收到 ${source.first}:${source.second} $count 次"
            }
            //endregion
        }
    }
    private fun uiSetRecv(isStart:Boolean){
        if (isStart){
            btnRecv.isEnabled = false
        }else{
            btnRecv.isEnabled = true
        }
        btnStopRecv.isEnabled = btnRecv.isEnabled.not()
        btnSend.isEnabled = btnRecv.isEnabled
    }
    private fun uiSetSend(isStart:Boolean){
        if (isStart){
            btnSend.isEnabled = false
        }else{
            btnSend.isEnabled = true
        }
        btnStopSend.isEnabled = btnSend.isEnabled.not()
        btnRecv.isEnabled = btnSend.isEnabled
    }
    //endregion
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        verticalLayout {
            verticalLayout {
                orientation = LinearLayout.HORIZONTAL
                btnRecv = button("开启接收"){
                    onClick {
                        uiSetRecv(true)
                        broadcastTask.startListen() {
                            runOnUiThread {
                                //broadcastTask.errors.forEach { println(it) }
                                uiSetRecv(false)
                            }
                        }
                    }
                }.lparams { weight = 1.0F }
                btnStopRecv = button("关闭接收"){
                    onClick {
                        uiSetRecv(false)
                        broadcastTask.stop()
                    }
                }.lparams { weight = 1.0F }
                btnSend = button("开启发送"){
                    onClick {
                        uiSetSend(true)
                        val selfPort = 20_201
                        broadcastTask.startSend(500, 1500, 600, selfPort,
                                {
                                    runOnUiThread { tvLastRecv.text = "发送第 $it 次" }
                                },
                                {
                                    runOnUiThread { uiSetSend(false) }
                                })
                    }
                }.lparams { weight = 1.0F }
                btnStopSend = button("关闭发送"){
                    onClick {
                        uiSetSend(false)
                        broadcastTask.stop()
                    }
                }.lparams { weight = 1.0F }

                btnClear = button("清空日志"){
                    onClick { clear() }
                }.lparams { weight = 1.0F }
            }
            scroll = scrollView {
                panel = verticalLayout {
                    orientation = LinearLayout.VERTICAL
                    tvLastRecv = textView {
                        hint = "最近接收信息..."
                        textSize = sp(8.4F).toFloat()
                    }
                    tvLog = textView {
                        textSize = sp(8).toFloat()
                    }.lparams(matchParent, wrapContent)
                }
            }.lparams(matchParent, matchParent)
        }

        btnStopRecv.isEnabled = false
        btnStopSend.isEnabled = false
    }
    init {
        broadcastTask = BroadcastTask().apply {
            setOnReceived(this@LinkMainActivity::onReceivedPort)
            setUnlockListen(
                    {
                        val wifiManager = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager?
                        multicastLock = wifiManager?.createMulticastLock("multicast.test")
                        multicastLock?.acquire()
                    },
                    {
                        multicastLock?.release()
                    })
        }
    }
    override fun onStart() {
        super.onStart()
        //已开启，返回
        if (broadcastTask.type != 0){
            return
        }
        val isTv = true
        //TV自动开启
        if (isTv){
            btnSend.performClick()
        }
    }
    protected fun logLine(str: String) {
        val line = if (str.endsWith('\n')) str else "$str\n"
        scroll.post {
            //tvLog.append(line)
            tvLog.text = line
            scroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
    protected fun clear(){
        runOnUiThread {
            tvLog.text = "Clear\n"
        }
        synchronized(recvMap){
            runOnUiThread {
                recvMap.values.forEach {
                    panel.removeView(it)
                }
            }
            recvMap.clear()
            recvIpMap.clear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        broadcastTask.stop()
    }

}