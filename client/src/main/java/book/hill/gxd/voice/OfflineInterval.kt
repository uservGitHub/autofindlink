package book.hill.gxd.voice

import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.observers.DisposableObserver
import io.reactivex.observers.DisposableSingleObserver
import io.reactivex.schedulers.Schedulers
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.info
import java.util.concurrent.TimeUnit

/**
 * 离线定时器：
 * 用户可获得焦点的情况下，每隔IntervalSpan调用一次
 * 实际过程的执行时间(响应时间)必须小于IntervalSpan
 *
 * 修改成递归调用更好
 */
class OfflineInterval(val tick:()->Unit, val intervalSecond:Long):AnkoLogger {
    override val loggerTag: String
        get() = "_interval"
    private val mCompositeDisposable: CompositeDisposable
    private var mIndex = 0
    var startMS: Long
        private set

    init {
        mCompositeDisposable = CompositeDisposable()
        startMS = System.currentTimeMillis()
    }

    fun next() {
        mIndex++
        info { "MS=${System.currentTimeMillis() - startMS}\tAdd Index $mIndex" }
        mCompositeDisposable.add(
                Observable.timer(intervalSecond, TimeUnit.SECONDS)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                {},
                                {},
                                {
                                    info { "MS=${System.currentTimeMillis() - startMS}\tExc Index $mIndex" }
                                    tick.invoke()
                                }
                        )
        )
    }

    fun clear() {
        mCompositeDisposable.clear()
    }
}