package link.find.auto.server

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
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
    //键=接收的IP+端口，值=接收该键的次数
    val recvMap = mutableMapOf<Pair<String,Int>, Int>()
    val recvKeyCount = 4
    var applyControl:GroupBroadcastControl? = null
    //region UI
    lateinit var scroll: ScrollView
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
                tvLog = textView {
                    textSize = sp(11).toFloat()
                }.lparams(matchParent, matchParent)
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
            tvLog.append(line)
            scroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
    protected fun clear(){
        runOnUiThread {
            tvLog.text = "Clear\n"
            synchronized(recvMap){
                recvMap.clear()
            }
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
            val sendMsg = "${localEndian.first}:${localEndian.second} - ${SocketConfigure.localLongIp}"
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
                        synchronized(recvMap){
                            //不存在
                            if (recvMap.containsKey(source).not()){
                                recvMap.put(source, 1)
                                logLine(msg)
                                return@synchronized
                            }
                            //未达上限
                            val count = recvMap[source]!!
                            if (count<recvKeyCount){
                                recvMap[source] = count+1
                                logLine(msg)
                                return@synchronized
                            }
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