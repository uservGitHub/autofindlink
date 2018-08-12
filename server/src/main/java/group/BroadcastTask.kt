package gxd.socket

import group.fromInt
import group.toInt
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.text.SimpleDateFormat
import java.util.*

class BroadcastTask {
    companion object {
        /**
         * 组播IP
         */
        val GROUP_IP = "239.0.1.117" //224改成239
        /**
         * 组播监听端口
         */
        val GROUP_PORT = 51234
    }
    private var onReceived:((ip:ByteArray,port:Int)->Unit)? = null

    private var onListeningBefore: (() -> Unit)? = null
    private var onStopListenAfter: (() -> Unit)? = null
    private var socket: MulticastSocket? = null
    private var thread: Thread? = null
    //状态在线程自动结束后无法复位，只能在stop后置0
    var type = 0 //0无效，1监听，2发送
        private set
    var isStop = true
        private set
    private val errFmt: (funName: String, message: String?) -> String = { funName, message ->
        //val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")//设置日期格式// new Date()为获取当前系统时间
        val timeDf = SimpleDateFormat("HH:mm:ss")
        "Exception:${this.javaClass.simpleName}:$funName:${Thread.currentThread()}:${timeDf.format(Date())}:\n$message"
    }
    private val errs = mutableListOf<String>()
    val errors: List<String>
        get() = errs
    /**
     * 设置解锁监听的操作
     * @param listenBefore 监听前执行的代码块（非UI线程）
     * @param listenStop 监听后执行的代码块（非UI线程）
     */
    fun setUnlockListen(listenBefore:()->Unit,listenStop:()->Unit) {
        this.onListeningBefore = listenBefore
        this.onStopListenAfter = listenStop
    }

    /**
     * 设置监听
     * @param action 监听操作(IP地址，传输的端口参数)
     */
    fun setOnReceived(action:(ByteArray, Int)->Unit){
        this.onReceived = action
    }
    fun stop() {
        if (isStop.not()) {
            isStop = true
            Thread.yield()
            socket?.close()
            try {
                thread?.let {
                    if (it.isAlive) {
                        it.interrupt()
                        it.join()
                    }
                }
                thread = null
            }catch (e:Exception){
                errs.add(errFmt("stop", e.message))
            }
        }
    }

    fun startSend(initDealy:Long, period:Long,count:Int, ip:ByteArray, port:Int):Boolean {
        if (thread != null) {
            return type == 2
        }
        try {
            thread = Thread({
                try {
                    if (initDealy > 0) {
                        sleep(initDealy)
                    }
                    val buf = ByteArray(2+4).apply {
                        fromInt(port,2,4)
                    }
                    socket = MulticastSocket(GROUP_PORT).apply {
                        // 如果只是发送组播，上面的端口号和下面的三项内容不需要
                        joinGroup(InetAddress.getByName(GROUP_IP))
                        timeToLive = 128 //本地网络一般不需要
                        loopbackMode = false //非回环网络
                    }
                    val packet = DatagramPacket(buf, buf.size, InetAddress.getByAddress(ip), port)
                    var i = 0
                    while (isStop.not() && i<count) {
                        socket?.send(packet)
                        i++
                        if (i >= count || isStop) {
                            return@Thread
                        }
                        Thread.sleep(period)
                    }
                }
                catch (e:Exception)
                {

                }finally {
                    socket?.close()
                    socket = null
                    type = 0
                }
            }).apply {
                isDaemon = true
                type = 2
                isStop = false
                start()
            }
            return true
        } catch (e: Exception) {
            errs.add(errFmt("startSend", e.message))
        }
        return false
    }

    fun startListen(): Boolean {
        if (thread != null) {
            return type == 1
        }
        try {
            thread = Thread({
                try {
                    socket = MulticastSocket(GROUP_PORT).apply {
                        // 如果只是发送组播，上面的端口号和下面的三项内容不需要
                        joinGroup(InetAddress.getByName(GROUP_IP))
                        timeToLive = 128 //本地网络一般不需要
                        loopbackMode = false //非回环网络
                    }
                    onListeningBefore?.invoke()
                    while (isStop.not()) {
                        val buf = ByteArray(2 + 4)
                        val packet = DatagramPacket(buf, buf.size)
                        socket!!.receive(packet)
                        if (isStop) {
                            return@Thread
                        }
                        val ip = packet.address.address
                        val port = packet.data.toInt(2, 4)
                        onReceived?.invoke(ip, port)
                        Thread.yield()
                    }
                }catch (e:Exception){

                }
                finally {
                    onStopListenAfter?.invoke()
                    socket?.close()
                    socket = null
                    type = 0
                }
            }).apply {
                isDaemon = true
                type = 1
                isStop = false
                start()
            }
            return true
        } catch (e: Exception) {
            errs.add(errFmt("startListen", e.message))
        }
        return false
    }

    inline fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: Exception) {

        }
    }
}