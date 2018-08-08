package gxd.socket

import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.TimeUnit

/**
 * 组播类
 * 1 监听和发送单独控制
 * 2 适用于Android和windows
 */
class GroupBroadcastSocket private constructor(){
    companion object {
        fun create() = GroupBroadcastSocket()
        /**
         * 全局实例
         */
        /*val instance:GroupBroadcastSocket by lazy {
            GroupBroadcastSocket()
        }*/
        /**
         * 组播IP
         */
        val GROUP_IP = "239.0.1.117" //224改成239
        /**
         * 组播监听端口
         */
        val GROUP_PORT = 51234

        //回调标志
        val CALLBACK_COMPLETE = 1
        val CALLBACK_ERROR = 2
        val CALLBACK_DISPOSE = 3
    }
    /**
     * 能否工作
     */
    var canWork: Boolean = false
        private set
    var localIpAndPort: Pair<String, Int> = Pair("", 0)
        private set
    private lateinit var socket: MulticastSocket
    private var listeningDisposable: Disposable? = null
    private var sendingDisposable: Disposable? = null
    private var onListeningBefore:(()->Unit)? = null
    private var onStopListenAfter:(()->Unit)? = null
    private var onLogLine:((String)->Unit)? = null
    /**
     * 关键信息输出格式
     */
    private val keyLog:(String)->String = {
        "$it from ${this.javaClass.simpleName} [${localIpAndPort.first}:${localIpAndPort.second}] [${Thread.currentThread()}]"
    }

    /**
     * 设置日志行输出
     * @param logLine 日志行输出
     */
    fun setLogLine(logLine:(String)->Unit){
        this.onLogLine = logLine
    }

    /**
     * 设置解锁监听的操作
     * @param listenBefore 监听前执行的代码块（非UI线程）
     * @param listenStop 监听后执行的代码块（非UI线程）
     */
    fun setUnlockListen(listenBefore:()->Unit,listenStop:()->Unit) {
        this.onListeningBefore = listenBefore
        this.onStopListenAfter = listenStop
    }

    init {
        try {
            socket = MulticastSocket(GROUP_PORT).apply {
                // 如果只是发送组播，上面的端口号和下面的三项内容不需要
                joinGroup(InetAddress.getByName(GROUP_IP))
                timeToLive = 128 //本地网络一般不需要
                loopbackMode = false //非回环网络
            }
            localIpAndPort = Pair(socket.localAddress.hostAddress, socket.localPort)
            canWork = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    /**
     * 停止发送
     */
    fun stopSend(){
        sendingDisposable?.dispose()
        sendingDisposable = null
    }

    /**
     * 停止接收
     */
    fun stopListen(){
        listeningDisposable?.dispose()
        listeningDisposable = null
        try {
            onStopListenAfter?.invoke()
        }catch (e:Exception){

        }
    }

    /**
     * 关闭
     */
    fun close(){
        stopSend()
        stopListen()
        if (canWork && socket.isClosed.not()){
            socket.close()
        }
    }

    /**
     * 启动发送（每秒发送1次，持续30秒）
     * @param appBuffer 应用数据流
     * @param endCallback 发送完毕后的回调操作
     * @param secondCount 持续的时间（秒）
     * @return 是否正常启动发送
     */
    fun startSend(appBuffer: ByteArray, endCallback:(callbackKey:Int)->Unit, secondCount:Long = 30):Boolean {
        if (canWork.not()) {
            return false
        }
        if (sendingDisposable != null && sendingDisposable!!.isDisposed.not()) {
            return true
        }
        val count = if (secondCount<0) 10 else secondCount
        val inetAddress = InetAddress.getByName(GROUP_IP)
        val source = Observable.interval(1, TimeUnit.SECONDS)
                .doOnNext {
                    //没有进行异常捕获
                    // 原因：1 发生异常时，后续的发送通常也异常，没有必要继续发送
                    //       2 及时退出，交于归口处理
                    val packet = DatagramPacket(appBuffer, appBuffer.size, inetAddress, GROUP_PORT)
                    socket.send(packet)
                }.take(count)
                .doOnDispose {
                    try {
                        onLogLine?.invoke(keyLog("Dispose"))
                    } catch (e: Exception) {

                    }
                    endCallback(CALLBACK_DISPOSE)
                }

        sendingDisposable = source//.subscribeOn(Schedulers.computation())
                .observeOn(Schedulers.computation())
                .subscribe(
                        {},
                        {
                            try {
                                onLogLine?.invoke(keyLog("Error"))
                            } catch (e: Exception) {

                            }
                            endCallback(CALLBACK_ERROR)
                        },
                        {
                            try {
                                onLogLine?.invoke(keyLog("Complete"))
                            } catch (e: Exception) {

                            }
                            endCallback(CALLBACK_COMPLETE)
                        }
                )
        return true
    }

    /**
     * 启动监听
     * @param onReceived 接收数据包的处理，返回值表示是否继续接收
     * @param endCallback 接收过程中的回调操作
     * @return 返回是否启动
     */
    fun startListen(onReceived:(pack:DatagramPacket)->Boolean, endCallback: (callbackKey: Int) -> Unit):Boolean{
        if (canWork.not()) {
            return false
        }
        if (listeningDisposable != null && listeningDisposable!!.isDisposed.not()) {
            return true
        }
        //组播接收在Android上会有一定的限制，这里提供一个进行解锁处理块的执行
        try {
            onListeningBefore?.invoke()
        }catch (e:Exception){

        }
        val source = Observable.create<Unit> {
            while (it.isDisposed.not()) {
                //动态分配
                val buf = ByteArray(1472)
                val packet = DatagramPacket(buf, buf.size)
                //这里要求一旦出现错误，不再进行后续的接收
                socket.receive(packet)
                /*try {
                    socket.receive(packet)
                } catch (e: Exception) {
                    //要进行后续接收
                }*/

                if (it.isDisposed || onReceived(packet).not()){
                    break
                }
            }
            it.onComplete()
        }.doOnDispose {
            try {
                onLogLine?.invoke(keyLog("Dispose"))
            } catch (e: Exception) {

            }
            endCallback(CALLBACK_DISPOSE)
        }

        listeningDisposable = source.subscribeOn(Schedulers.io())
                .observeOn(Schedulers.computation())
                .subscribe(
                        {},
                        {
                            try {
                                onLogLine?.invoke(keyLog("Error"))
                            } catch (e: Exception) {

                            }
                            endCallback(CALLBACK_ERROR)
                        },
                        {
                            try {
                                onLogLine?.invoke(keyLog("Complete"))
                            } catch (e: Exception) {

                            }
                            endCallback(CALLBACK_COMPLETE)
                        }
                )
        return true
    }
}