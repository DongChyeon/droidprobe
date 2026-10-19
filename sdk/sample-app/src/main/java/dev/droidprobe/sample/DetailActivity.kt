package dev.droidprobe.sample

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

/** Hosts one fragment so the lifecycle commands have a fragment tree to show. */
class DetailActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val fragment = DetailFragment().apply {
                arguments = Bundle().apply { putString(DetailFragment.ARG_ORDER_ID, intent.getStringExtra(EXTRA_ORDER_ID) ?: "order-1") }
            }
            supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment, DetailFragment.TAG).commit()
        }
    }

    companion object {
        const val EXTRA_ORDER_ID = "orderId"
    }
}

class DetailFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        TextView(inflater.context).apply {
            setPadding(48, 48, 48, 48)
            text = "Order ${requireArguments().getString(ARG_ORDER_ID)}"
        }

    companion object {
        const val TAG = "detail"
        const val ARG_ORDER_ID = "orderId"
    }
}
