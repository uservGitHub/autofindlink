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
import group.GroupSocket
import group.IpAndPort
import group.SocketConfigure
import gxd.socket.GroupBroadcastSocket
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import org.jetbrains.anko.*
import org.jetbrains.anko.sdk25.coroutines.onClick
import java.util.concurrent.TimeUnit

/**
 * 默认自动接收/发送组播
 * 同一地址组播信息只接收4次
 */
class AutoSendAndListenActivity:AppCompatActivity(){
    var broadcastSocket:GroupBroadcastSocket? = null
    var multicastLock: WifiManager.MulticastLock? = null
    //键=接收的IP+端口，值=接收该键的次数的UI显示
    val recvMap = mutableMapOf<Pair<String,Int>, TextView>()
    //键=接收到IP地址，值=接收到该键的次数
    val recvIpMap = mutableMapOf<String, Int>()
    val recvKeyCount = 4
    var applyControl:GroupBroadcastControl? = null
    //region UI
    lateinit var scroll: ScrollView
    lateinit var panel: LinearLayout
    lateinit var tvLog: TextView
    lateinit var btnAutoSend: Button
    lateinit var btnAutoSendStop: Button
    lateinit var btnAutoRecv: Button
    lateinit var btnAutoRecvStop: Button
    lateinit var btnClear: Button   //清空接收列表及日志
    //endregion
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        verticalLayout {
            verticalLayout {
                orientation = LinearLayout.HORIZONTAL
                btnAutoSend = button("自动发送"){
                    isEnabled = false
                    onClick {
                        btnAutoSend.isEnabled = false
                        applyControl?.let {
                            it.startSend()
                            if (it.canSend){
                                btnAutoSendStop.isEnabled = true
                            }
                        }
                    }
                }.lparams { weight = 1.0F }
                btnAutoSendStop = button("停止发送"){
                    isEnabled = false
                    onClick {
                        btnAutoSendStop.isEnabled = false
                        broadcastSocket!!.stopSend()
                        applyControl?.let {
                            if (it.canSend){
                                btnAutoSend.isEnabled = true
                            }
                        }
                    }
                }.lparams { weight = 1.0F }
                btnAutoRecv = button("自动接收"){
                    isEnabled = false
                    onClick {
                        btnAutoRecv.isEnabled = false
                        applyControl?.let {
                            it.startListen()
                            if (it.canListen){
                                btnAutoRecvStop.isEnabled = true
                            }
                        }
                    }
                }.lparams { weight = 1.0F }
                btnAutoRecvStop = button("停止接收"){
                    isEnabled = false
                    onClick {
                        btnAutoRecvStop.isEnabled = false
                        broadcastSocket!!.stopListen()
                        applyControl?.let {
                            if (it.canListen){
                                btnAutoRecv.isEnabled = true
                            }
                        }
                    }
                }.lparams { weight = 1.0F }
                btnClear = button("清空日志"){
                    onClick { clear() }
                }.lparams { weight = 1.0F }
            }
            scroll = scrollView {
                panel = verticalLayout {
                    orientation = LinearLayout.VERTICAL

                    tvLog = textView {
                        textSize = sp(8).toFloat()
                    }.lparams(matchParent, wrapContent)
                }
            }.lparams(matchParent, matchParent)
        }
    }

    override fun onStart() {
        super.onStart()
        if (broadcastSocket != null){
            return
        }
        broadcastSocket = GroupBroadcastSocket.create().apply {
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
        applyControl = GroupBroadcastControl()
        //自动开启
        applyControl!!.startListen()
        applyControl!!.startSend()
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
        broadcastSocket?.close()
    }

    inner class GroupBroadcastControl{
        var canSend = false
            private set
        var canListen = false
            private set
        var isSending = false
            private set
        var isListening = false
            private set

        fun startSend(){
            val localEndian = broadcastSocket!!.localIpAndPort
            //发广播的IP - 本机最长IP
            val sendMsg = "广播IP ${localEndian.first}，本地最长IP ${SocketConfigure.localLongIp}"
            canSend = broadcastSocket!!.startSend(
                    sendMsg.toByteArray(),
                    {
                        //复位
                        isSending = false
                        runOnUiThread {
                            if (canSend) {
                                btnAutoSendStop.performClick()
                            }
                        }
                    }
            )
            isSending = canSend
            if (isSending){
                btnAutoSendStop.isEnabled = true
            }
        }
        fun startListen(){
            canListen = broadcastSocket!!.startListen(
                    {
                        val msg = String(it.data, it.offset, it.length)
                        val source = Pair<String, Int>(it.address.hostAddress, it.port)
                        logLine(msg)

                        synchronized(recvMap){
                            //不存在
                            if (recvMap.containsKey(source).not()){
                                runOnUiThread {
                                    val newLine = TextView(this@AutoSendAndListenActivity).apply {
                                        textSize = sp(7).toFloat()
                                        text = "收到 ${source.first} 1 次"
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
                                tvSource.text = "收到 ${source.first} $count 次"
                            }
                            //endregion
                        }
                        //已达到上限，不打印
                        true //继续接收
                    },
                    {
                        //复位
                        isListening = false
                        runOnUiThread {
                            if(canListen) {
                                btnAutoSendStop.performClick()
                            }
                        }
                    }
            )
            isListening = canListen
            if (isListening){
                //启动后就可以停止
                btnAutoRecvStop.isEnabled = true
            }
        }
    }
}