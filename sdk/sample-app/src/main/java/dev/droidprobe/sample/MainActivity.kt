package dev.droidprobe.sample

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply { setPadding(48, 48, 48, 48) }
        setContentView(label)
        scope.launch { Checkout.confirmed.collect { label.text = if (it) "Confirmed" else "Pending" } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
