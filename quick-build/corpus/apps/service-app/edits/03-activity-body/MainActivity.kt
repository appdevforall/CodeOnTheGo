package org.appdevforall.cotg.corpus.serviceapp

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
	private lateinit var tickView: TextView
	private var binder: TickBinderService.LocalBinder? = null
	private var bound = false

	private val tickConnection =
		object : ServiceConnection {
			override fun onServiceConnected(name: ComponentName, service: IBinder) {
				binder = service as TickBinderService.LocalBinder
				tickView.text = getString(R.string.tick_prefix_label) + " " + binder?.describeTick()
			}

			override fun onServiceDisconnected(name: ComponentName) {
				binder = null
			}
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val root =
			LinearLayout(this).apply {
				orientation = LinearLayout.VERTICAL
				gravity = Gravity.CENTER_HORIZONTAL
				setPadding(32, 64, 32, 32)
			}

		root.addView(TextView(this).apply { text = getString(R.string.main_title_label) })

		val startButton =
			Button(this).apply {
				text = getString(R.string.start_counter_label)
				setOnClickListener {
					startForegroundService(Intent(this@MainActivity, CounterService::class.java))
				}
			}
		root.addView(startButton)

		val stopButton =
			Button(this).apply {
				text = getString(R.string.stop_counter_label)
				setOnClickListener {
					stopService(Intent(this@MainActivity, CounterService::class.java))
				}
			}
		root.addView(stopButton)

		val bindButton =
			Button(this).apply {
				text = getString(R.string.bind_ticker_label)
				setOnClickListener {
					val intent = Intent(this@MainActivity, TickBinderService::class.java)
					bound = bindService(intent, tickConnection, Context.BIND_AUTO_CREATE)
				}
			}
		root.addView(bindButton)

		tickView = TextView(this).apply { text = getString(R.string.no_tick_yet_label) + " QB_ACT_BODY_MARKER_V2" }
		root.addView(tickView)

		setContentView(root)
	}

	override fun onDestroy() {
		if (bound) {
			unbindService(tickConnection)
			bound = false
		}
		super.onDestroy()
	}
}
