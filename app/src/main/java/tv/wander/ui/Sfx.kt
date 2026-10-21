package tv.wander.ui

import android.content.Context

/** Short UI sounds (SoundPool over res/raw). All calls are safe before init and when audio fails. */
object Sfx {
    fun init(context: Context) {}
    fun focus() {}
    fun select() {}
    fun back() {}
    fun join() {}
    fun success() {}
    fun fail() {}
    fun whoosh() {}
    fun pop() {}
    fun chime() {}
    fun release() {}
}
