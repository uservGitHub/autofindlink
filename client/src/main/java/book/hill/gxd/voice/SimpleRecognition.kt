package book.hill.gxd.voice

import android.content.Context
import com.baidu.speech.EventListener
import com.baidu.speech.EventManager
import com.baidu.speech.EventManagerFactory
import com.baidu.speech.asr.SpeechConstant
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.info
import org.json.JSONObject

class SimpleRecognition(ctx:Context):EventListener,AnkoLogger{
    override val loggerTag: String
        get() = "_recog"
    private val asr:EventManager
    private val wp:EventManager
    private val receiveCallback: ReceiveCallback
    var canUse:Boolean = false
        private set
    private val backTrackInMs = 1500

    private var tick:Long = 0
    private var isLog:Boolean = true //调试
    private var isDump:Boolean = true //调试时开启
    private var recvEventIndex = 0
    private var sendEventIndex = 0
    private var wakekeyEventIndex = 0
    private var dump:((String)->Unit)? = null
    private var lastParams:String? = null
    private var onResult:((Int,String)->Unit)? = null
    private var onName:((Int,String)->Unit)? = null
    init {
        tick = System.currentTimeMillis()
        receiveCallback = ReceiveCallback()

        asr = EventManagerFactory.create(ctx, "asr")
        asr.registerListener(this)
        wp = EventManagerFactory.create(ctx, "wp")
        wp.registerListener(this)
    }
    fun setOnResult(fn:(Int,String)->Unit){
        onResult = fn
    }
    fun setOnName(fn:(Int,String)->Unit){
        onName = fn
    }
    /**
     * 开启App唤醒
     */
    fun starApp(){
        if (!canUse) {
            canUse = true
            loadedOffline()
            startWp()
        }
    }

    /**
     * 关闭并释放
     */
    fun dispose(){
        releaseWp()
        release()
        canUse = false
    }

    override fun onEvent(name: String?, params: String?, data: ByteArray?, offset: Int, length: Int) {
        //lastJson = params
        if (name == null) {
            return
        }

        when (name!!) {
            SpeechConstant.CALLBACK_EVENT_ASR_READY -> {
                recvEventIndex++
                //就绪等待语音
                receiveCallback.onAsrReady()
            }
            SpeechConstant.CALLBACK_EVENT_ASR_BEGIN -> {
                //检测到语音开始
                receiveCallback.onAsrBegin()
            }
            SpeechConstant.CALLBACK_EVENT_ASR_END -> {
                //停止
                receiveCallback.onAsrEnd()
            }
            SpeechConstant.CALLBACK_EVENT_ASR_FINISH -> {
                //识别结束，有可能是错的
                val result = RecogResult.parseJson(lastParams!!)
                if (result.hasError()) {
                    val errCode = result.error
                    val subErr = result.subError
                    receiveCallback.onAsrFinishError(ErrorTranslation.recogError(errCode), result.desc, result)
                } else {
                    //对结果进行特殊的额外打印
                    val text = if (result.resultsRecognition.size > 0) result.resultsRecognition[0] else ""
                    onResult?.invoke(recvEventIndex, text)
                    dumpResult(text)
                    receiveCallback.onAsrFinish(result)
                }
            }
            SpeechConstant.CALLBACK_EVENT_ASR_EXIT -> {
                //结束
                receiveCallback.onAsrExit()
            }
            SpeechConstant.CALLBACK_EVENT_ASR_LOADED -> {
                //离线预料库载入
                receiveCallback.onOfflineLoaded()
            }
            SpeechConstant.CALLBACK_EVENT_ASR_UNLOADED -> {
                //离线预料库未能成功载入??
                receiveCallback.onOfflineUnLoaded()
            }

        //region 未启用，但记录
            SpeechConstant.CALLBACK_EVENT_ASR_AUDIO -> {
                //传来的语音
            }
            SpeechConstant.CALLBACK_EVENT_ASR_VOLUME -> {
                //传来的音量设置
            }
            SpeechConstant.CALLBACK_EVENT_ASR_LONG_SPEECH -> {
                //长语音
            }
            SpeechConstant.CALLBACK_EVENT_ASR_PARTIAL -> {
                //长语音模式中的部分语音
            }
        //endregion

        //region    唤醒识别
            SpeechConstant.CALLBACK_EVENT_WAKEUP_SUCCESS -> {
                wakekeyEventIndex++
                //wp.data	{"errorDesc":"wakup success","errorCode":0,"word":"播放"}
                val json = JSONObject(params)
                val word = json.optString("word")
                onResult?.invoke(wakekeyEventIndex, word)
                dumpResult(word)
                receiveCallback.onWakeupSuccess()
            }
        //endregion

            else -> {
                //未能想到的情况
            }
        }
        lastParams = params
        dumpName(
                if (name.startsWith("wp.")) wakekeyEventIndex else recvEventIndex,
                "$name",
                params)
    }

    private fun release(){
        asr.unregisterListener(this)
    }
    private fun releaseWp(){
        stopWp()
        wp.unregisterListener(this)
    }



    fun openPrint(baseTick:Long, print:(String)->Unit){
        tick = baseTick
        dump = print
        isDump = true
        isLog = true
    }

    private fun dumpName(index:Int, name: String, params:String? = null) {
        onName?.invoke(index, name)
        if (isDump) {
            val line = "${index}\t${System.currentTimeMillis() - tick}\t$name\t$params\n"
            dump?.invoke(line)
            if (isLog) {
                info { line }
            }
        }
    }
    private fun dumpResult(text: String) {
        if (isDump) {
            val line = "text\t${System.currentTimeMillis() - tick}\t$text\n"
            dump?.invoke(line)
            if (isLog) {
                info { line }
            }
        }
    }


    /**
     * 启动
     */
    private fun start(){
        val params = LinkedHashMap<String, Any>()
        params[SpeechConstant.DECODER] = 2
        params[SpeechConstant.ACCEPT_AUDIO_VOLUME] = false
        //params.put(SpeechConstant.PID, 1537); // 中文输入法模型，有逗号
        sendEventIndex++
        dumpName(sendEventIndex, SpeechConstant.ASR_START)
        asr.send(SpeechConstant.ASR_START, JSONObject(params).toString(), null, 0, 0)
    }

    /**
     * 停止（录音）
     */
    private fun stop(){
        dumpName(sendEventIndex, SpeechConstant.ASR_STOP)
        asr.send(SpeechConstant.ASR_STOP, null, null, 0, 0)
    }

    /**
     * 取消
     * 取消本次识别，取消后将立即停止不会返回识别结果。
     * cancel 与stop的区别是 cancel在stop的基础上，完全停止整个识别流程，
     */
    private fun cancel(){
        dumpName(sendEventIndex, SpeechConstant.ASR_CANCEL)
        asr.send(SpeechConstant.ASR_CANCEL, "{}", null, 0, 0)
    }

    /**
     * 装载离线文件
     */
    private fun loadedOffline(){
        val params = LinkedHashMap<String, Any>()
        params[SpeechConstant.DECODER] = 2
        params[SpeechConstant.ASR_OFFLINE_ENGINE_GRAMMER_FILE_PATH] = "assets://baidu_speech_grammar.bsg"
        dumpName(sendEventIndex, SpeechConstant.ASR_KWS_LOAD_ENGINE)
        asr.send(SpeechConstant.ASR_KWS_LOAD_ENGINE, JSONObject(params).toString(), null, 0, 0)
    }

    /**
     * 开启唤醒模式
     */
    private fun startWp(){
        val params = LinkedHashMap<String, Any>()
        params[SpeechConstant.WP_WORDS_FILE] = "assets://WakeUp.bin"
        params[SpeechConstant.ACCEPT_AUDIO_VOLUME] = false
        // params.put(SpeechConstant.ACCEPT_AUDIO_DATA,true);
        // params.put(SpeechConstant.IN_FILE,"res:///com/baidu/android/voicedemo/wakeup.pcm");
        wp.send(SpeechConstant.WAKEUP_START, JSONObject(params).toString(),null, 0, 0)
    }

    /**
     * 停止唤醒模式
     */
    private fun stopWp(){
        wp.send(SpeechConstant.WAKEUP_STOP, "{}", null, 0, 0)
    }

    /**
     * 卸载离线文件
     */
    private fun unloadedOffline(){
        dumpName(sendEventIndex, SpeechConstant.ASR_KWS_UNLOAD_ENGINE)
        asr.send(SpeechConstant.ASR_KWS_UNLOAD_ENGINE, null, null, 0, 0)
    }
    inner class ReceiveCallback:OfflineListener{
        override fun onWakeupSuccess() {
            super.onWakeupSuccess()
            val params = java.util.LinkedHashMap<String, Any>()
            /*params[SpeechConstant.ACCEPT_AUDIO_VOLUME] = false
            params[SpeechConstant.VAD] = SpeechConstant.VAD_DNN
            //http://ai.baidu.com/docs#/ASR-Android-SDK/3b83299d
            // 如识别短句，不需要需要逗号，使用1536搜索模型。其它PID参数请看文档
            params[SpeechConstant.PID] = 1536
            //params[SpeechConstant.DECODER] = 2
            //params[SpeechConstant.ACCEPT_AUDIO_VOLUME] = false
            //params.put(SpeechConstant.PID, 1537); // 中文输入法模型，有逗号*/
            if (backTrackInMs > 0) { // 方案1， 唤醒词说完后，直接接句子，中间没有停顿。
                params[SpeechConstant.AUDIO_MILLS] = System.currentTimeMillis() - backTrackInMs
            }
            cancel()
            params[SpeechConstant.DECODER] = 2
            params[SpeechConstant.ACCEPT_AUDIO_VOLUME] = false
            params[SpeechConstant.VAD] = SpeechConstant.VAD_DNN //默认
            params[SpeechConstant.PID] = 1536 //默认
            //params.put(SpeechConstant.PID, 1537); // 中文输入法模型，有逗号
            sendEventIndex++
            dumpName(sendEventIndex, SpeechConstant.ASR_START)
            asr.send(SpeechConstant.ASR_START, JSONObject(params).toString(), null, 0, 0)
            //myRecognizer.cancel()
            //myRecognizer.start(params)
        }

    }

    interface OfflineListener {

        /**
         * ASR_START 输入事件调用后，引擎准备完毕
         */
        fun onAsrReady() = Unit

        /**
         * onAsrReady后检查到用户开始说话
         */
        fun onAsrBegin()= Unit

        /**
         * 检查到用户开始说话停止，或者ASR_STOP 输入事件调用后，
         */
        fun onAsrEnd()= Unit

        /**
         * onAsrBegin 后 随着用户的说话，返回的临时结果
         *
         * @param results     可能返回多个结果，请取第一个结果
         * @param recogResult 完整的结果
         */
        //fun onAsrPartialResult(results: Array<String>, recogResult: RecogResult)

        /**
         * 最终的识别结果
         *
         * @param results     可能返回多个结果，请取第一个结果
         * @param recogResult 完整的结果
         */
        //用在长语音中
        //fun onAsrFinalResult(results: Array<String>, recogResult: RecogResult)

        fun onAsrFinish(recogResult: RecogResult)= Unit

        fun onAsrFinishError(errorMessage: String, descMessage: String,
                             recogResult: RecogResult)= Unit
        /*fun onAsrFinishError(errorCode: Int, subErrorCode: Int, errorMessage: String, descMessage: String,
                             recogResult: RecogResult)*/

        /**
         * 长语音识别结束
         */
        //fun onAsrLongFinish()

        //语音合成中会传递
        //fun onAsrVolume(volumePercent: Int, volume: Int)

        //传输语音
        //fun onAsrAudio(data: ByteArray, offset: Int, length: Int)

        fun onAsrExit()= Unit

        //长语音会用
        //fun onAsrOnlineNluResult(nluResult: String)

        //收到这个，离线才起作用（比如没有离线文件或其他原因导致，无法离线）
        fun onOfflineLoaded()= Unit

        //何时用，难道是离线无法成功时触发？（初步认为是卸载离线文件，转在线）
        fun onOfflineUnLoaded()= Unit

        //唤醒成功
        fun onWakeupSuccess() = Unit
    }
}