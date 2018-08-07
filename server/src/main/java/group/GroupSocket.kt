package group

import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.TimeUnit

class GroupSocket private constructor(val applyServer: IpAndPort? = null){
    companion object {
        fun getByServer(server:IpAndPort):GroupSocket{
            return GroupSocket(server)
        }
        fun getByClient():GroupSocket{
            return GroupSocket()
        }
        /**
         * 组播IP
         */
        val GROUP_IP = "224.0.1.117"
        /**
         * 组播监听端口
         */
        val GROUP_PORT = 51234
        val handType:Byte = 1
        val dataIpAndPort:Byte = 2
    }
    /**
     * 能否工作
     */
    var canWork: Boolean = false
        private set
    var localIpAndPort: IpAndPort = IpAndPort("",0)
        private set
    private lateinit var socket: MulticastSocket
    private var listeningDisposable: Disposable? = null
    private var sendingDisposable: Disposable? = null
    private var onReceived:((Int, DatagramPacket)->Unit)? = null
    private var state:Int = 0  //这个状态由用户控制，注意0是无效状态
    protected val keyLog:((String)->Unit)? = { println("$it from ${this.javaClass.simpleName}:$localIpAndPort")}

    init {
        try {
            socket = MulticastSocket().apply {
                // 只发送检测，无需下面的操作
                joinGroup(InetAddress.getByName(GROUP_IP))
                timeToLive = 128
            }
            localIpAndPort = IpAndPort(socket.localAddress.hostAddress, socket.localPort)
            canWork = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    /**
     * 停止监听
     */
    fun stop(){
        listeningDisposable?.dispose()
        listeningDisposable = null
        sendingDisposable?.dispose()
        sendingDisposable = null
    }
    /**
     * 关闭
     */
    fun close(){
        stop()
        if (canWork && socket.isClosed.not()){
            socket.close()
        }
    }

    /**
     * 启动客户端（自动发送）
     * @param applyClient 应用客户端IP及Port（服务端不需要）
     * @return 是否启动成功（通常给UI状态用）
     */
    fun startClient(applyClient:IpAndPort):Boolean {
        if (canWork.not()){
            return false
        }
        if (sendingDisposable!=null && sendingDisposable!!.isDisposed.not()){
            return true
        }
        //共30秒
        val source = Observable.interval(1,TimeUnit.SECONDS)
                .doOnNext {
                    try {
                        val objBuf = applyClient.toString().toByteArray()
                        val dataBuf = PackDefinition(handType, dataIpAndPort, objBuf).toByteArray()
                        val packet = DatagramPacket(dataBuf,dataBuf.size, InetAddress.getByName(GROUP_IP), GROUP_PORT)
                        socket.send(packet)
                    } catch (e: Exception) {
                        //e.printStackTrace()
                        //it.onError(e)  //上返并跳出
                    }
                }.take(600)
                .doOnDispose { keyLog?.invoke("Disposed") }
        sendingDisposable = source.subscribeOn(Schedulers.io())
                .subscribe({},{keyLog?.invoke("Error")},{keyLog?.invoke("Complete")})
        return true
    }
    /**
     * 启动，即监听
     * @param onceFinished 一旦收到，即结束，只监听一包
     * @return 是否启动成功（通常给UI状态用）
     */
    fun startServer(onceFinished:((packet:DatagramPacket)->Unit)? = null):Boolean{
        if (canWork.not()){
            return false
        }
        //已启动，返回true
        if (listeningDisposable!=null && listeningDisposable!!.isDisposed.not()){
            return true
        }
        //启动
        val source = Observable.create<Unit> {

            while (it.isDisposed.not() && canWork) {
                //动态分配
                val buf = ByteArray(1472)
                val packet = DatagramPacket(buf, buf.size)

                try {
                    socket.receive(packet)
                } catch (e: Exception) {
                    //it.onError(e)  //上返并跳出
                }
                if (it.isDisposed.not()) {
                    //定义完成操作，自动结束
                    if (onceFinished != null) {
                        onceFinished.invoke(packet)
                        it.onComplete()
                    } else {
                        //否则 进行结束包适配
                        onReceived?.invoke(state, packet)
                    }
                }
            }
            it.onComplete()
        }.doOnDispose { keyLog?.invoke("Disposed") }
        listeningDisposable = source.subscribeOn(Schedulers.io())
                .subscribe({},{keyLog?.invoke("Error")},{keyLog?.invoke("Complete")})
        return true
    }
}