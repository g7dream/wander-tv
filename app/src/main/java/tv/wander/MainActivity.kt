package tv.wander

import android.app.Activity
import android.os.Bundle
import tv.wander.ui.Fonts

/** Single activity. Stacks Home, Browse, overlays and Lumo, routes remote keys and phone commands by mode. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
    }
}
