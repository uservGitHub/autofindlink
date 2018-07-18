package book.hill.gxd.voice

import android.app.Application

class AppAplication:Application(){
    override fun onCreate() {
        super.onCreate()
        OfflineRecognition.init(this)
    }
}