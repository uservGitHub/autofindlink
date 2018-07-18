package book.hill.gxd.voice

import android.graphics.Color
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import org.jetbrains.anko.*
import org.jetbrains.anko.sdk25.coroutines.onClick
import java.lang.StringBuilder

class MainActivity12 : AppCompatActivity(),AnkoLogger {
    override val loggerTag: String
        get() = "_interval"
    lateinit var tvStatus: TextView
    lateinit var tvContent: TextView
    lateinit var recog:SimpleRecognition
    lateinit var offlineInterval: OfflineInterval

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        offlineInterval = OfflineInterval({
            tvStatus.text = "可以输入"
            tvContent.text = "内容是..."
            //模拟耗时
            try {
                Thread.sleep(2000)
                offlineInterval.next()
            }catch(e:Exception){

            }

        },5)
        verticalLayout {
            tvStatus = textView() {
                text = "状态"
                textSize = sp(20).toFloat()
            }.lparams(width = matchParent, height = wrapContent)
            tvContent = textView() {
                text = "内容"
                textSize = sp(20).toFloat()
            }.lparams(width = matchParent, height = wrapContent)
        }
    }

    override fun onResume() {
        super.onResume()
        info { "启动" }
        offlineInterval.next()
    }
    override fun onStop() {
        super.onStop()
        info { "结束" }
        offlineInterval.clear()
    }
}

class MainActivity:AppCompatActivity(){
    private lateinit var recog:SimpleRecognition
    private lateinit var recogText:TextView
    private lateinit var recogName:TextView
    private var isSpeechWorking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        verticalLayout {
            /*button("Start"){
                onClick {
                    clearText()
                    recog.start()
                }
            }*/
            button("Load"){
                onClick {
                    if(!isSpeechWorking) {
                        recog = SimpleRecognition(this@MainActivity).apply {
                            setOnResult { no, text ->
                                recogText.text = "${no}. $text"
                            }
                            setOnName { no, text ->
                                recogName.text = "${recogName.text}${no}. $text\n"
                            }
                        }
                        clearText()
                        recog.starApp()
                        isSpeechWorking = true
                    }
                }
            }
            button("Close"){
                onClick {
                    if (isSpeechWorking) {
                        isSpeechWorking = false
                        clearText()
                        recog.dispose()
                    }
                }
            }
            recogText = textView {
                gravity = Gravity.CENTER
                textSize = sp(10).toFloat()
            }.lparams(wrapContent, wrapContent)
            recogName = textView {
                backgroundColor = Color.GREEN
                textSize = sp(8).toFloat()
            }.lparams(matchParent, matchParent)
        }
    }

    private fun clearText(){
        recogName.text = ""
        recogText.text = ""
    }
    override fun onDestroy() {
        super.onDestroy()
        if(isSpeechWorking) {
            recog.dispose()
        }
    }
}
