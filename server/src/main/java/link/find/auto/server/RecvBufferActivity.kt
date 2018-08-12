package link.find.auto.server

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import group.UdpTransfer
import org.jetbrains.anko.*
import org.jetbrains.anko.sdk25.coroutines.onClick

class RecvBufferActivity:AppCompatActivity(){
    val udpTransfer:UdpTransfer
    lateinit var scroll: ScrollView
    lateinit var panel: LinearLayout
    lateinit var tvLog: TextView
    lateinit var tvIpPort: TextView
    lateinit var btnAutoRecv: Button
    lateinit var btnAutoRecvStop: Button
    lateinit var btnClear: Button   //清空接收列表及日志

    init {
        udpTransfer = UdpTransfer.create()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        verticalLayout {
            verticalLayout {
                orientation = LinearLayout.HORIZONTAL
                btnAutoRecv = button("自动接收"){
                    isEnabled = udpTransfer.canWork
                    onClick {
                        btnAutoRecv.isEnabled = false
                        btnAutoRecvStop.isEnabled = true
                        udpTransfer.startListenBuffer {
                            logLine("接收完毕，回调码：$it")
                            //查看接收的数据
                            if (it == UdpTransfer.CALLBACK_FINISH) {
                                val dataControl = udpTransfer.bufferControl!!
                                logLine("接收数据大小：${dataControl.buf.size}")
                            }
                        }
                    }
                }.lparams { weight = 1.0F }
                btnAutoRecvStop = button("停止接收"){
                    isEnabled = false
                    onClick {
                        btnAutoRecvStop.isEnabled = false
                        btnAutoRecv.isEnabled = true
                        udpTransfer.stopListen()
                    }
                }.lparams { weight = 1.0F }
                btnClear = button("清空日志"){
                    onClick { clear() }
                }.lparams { weight = 1.0F }
            }
            scroll = scrollView {
                panel = verticalLayout {
                    orientation = LinearLayout.VERTICAL
                    tvIpPort = textView{
                        textSize = sp(8).toFloat()
                        val ipport = udpTransfer.localIpAndPort
                        text = "${ipport.first}:${ipport.second} ${udpTransfer.localHostName}"
                    }
                    tvLog = textView {
                        textSize = sp(8).toFloat()
                    }.lparams(matchParent, wrapContent)
                }
            }.lparams(matchParent, matchParent)
        }
    }

    protected fun clear(){
        runOnUiThread {
            tvLog.text = "Clear\n"
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
}

















