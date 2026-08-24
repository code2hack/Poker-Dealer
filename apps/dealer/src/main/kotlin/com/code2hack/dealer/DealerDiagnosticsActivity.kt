package com.code2hack.dealer

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Extraction-only diagnostics shell. Full Dealer UI/UX is intentionally deferred. */
internal class DealerDiagnosticsActivity : Activity() {
    private var service: DealerCoreService? = null
    private var bound = false
    private lateinit var hostId: EditText
    private lateinit var threadId: EditText
    private lateinit var draft: EditText
    private lateinit var diagnostics: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val render = object : Runnable {
        override fun run() {
            renderState()
            handler.postDelayed(this, 500)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? DealerCoreService.LocalBinder)?.service
            bound = service != null
            renderState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            renderState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContentView(buildDiagnosticsView())
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, DealerCoreService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        handler.post(render)
    }

    override fun onStop() {
        handler.removeCallbacks(render)
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }

    private fun buildDiagnosticsView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        content.addView(TextView(this).apply {
            text = "Dealer diagnostics\nLegacy extraction shell — not final UI"
            textSize = 20f
        })
        hostId = EditText(this).apply { hint = "Codex host ID" }
        threadId = EditText(this).apply { hint = "Thread ID" }
        draft = EditText(this).apply {
            hint = "Draft text"
            minLines = 3
        }
        diagnostics = TextView(this).apply {
            setTextIsSelectable(true)
            text = "Service not connected"
        }
        content.addView(hostId)
        content.addView(threadId)
        content.addView(draft)
        content.addView(button("Enable host") { service?.setHostEnabled(host(), true) })
        content.addView(button("Disable host") { service?.setHostEnabled(host(), false) })
        content.addView(button("Refresh threads") { service?.refreshHost(host()) })
        content.addView(button("Start embedded tailnet") { service?.startEmbeddedTailnet() })
        content.addView(button("Stop embedded tailnet") { service?.stopEmbeddedTailnet() })
        content.addView(button("Attach + take control") {
            service?.attachAndTakeControl(host(), thread())
        })
        content.addView(button("Send / Steer") {
            service?.submitDraft(host(), thread(), draft.text.toString())
        })
        content.addView(button("Interrupt") { service?.interrupt(host(), thread()) })
        content.addView(diagnostics)
        return ScrollView(this).apply { addView(content) }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun host(): String = hostId.text.toString().trim()
    private fun thread(): String = threadId.text.toString().trim()

    private fun renderState() {
        val connected = service ?: run {
            diagnostics.text = "Service not connected"
            return
        }
        val state = connected.state.value
        diagnostics.text = buildString {
            appendLine(connected.operation.value)
            state.error?.let { appendLine("Error: $it") }
            appendLine()
            val tailnet = connected.tailnetStatus.value
            append("Embedded tailnet: ").append(tailnet.state)
            tailnet.path?.let { append(" / ").append(it) }
            tailnet.relay?.let { append(" / DERP ").append(it) }
            appendLine()
            tailnet.loginUrl?.let { appendLine("  Login: $it") }
            tailnet.error?.let { appendLine("  Error: $it") }
            tailnet.health.forEach { appendLine("  Health: $it") }
            appendLine("Hosts:")
            if (state.hostSessions.isEmpty()) appendLine("  (none active)")
            state.hostSessions.toSortedMap().forEach { (id, session) ->
                append("  ").append(id).append(": ").append(session.status)
                session.phase?.let { append(" / ").append(it) }
                session.route?.let { append(" / ").append(it) }
                session.error?.let { append(" — ").append(it) }
                appendLine()
            }
            appendLine("Threads:")
            if (state.threads.isEmpty()) appendLine("  (none discovered/restored)")
            state.threads.values
                .sortedWith(compareBy({ it.locator.hostId }, { it.locator.threadId }))
                .forEach { row ->
                    append("  ").append(row.locator.hostId).append("/").append(row.locator.threadId)
                    append(" — ").append(row.workState ?: "UNKNOWN")
                    if (row.attached) append(" attached")
                    if (state.threadAttachments.hasDealerClaim(row.locator)) append(" control=Dealer")
                    appendLine()
                }
            appendLine("Retained cards: ${state.cards.size}")
            appendLine("Blocking requests: ${state.blockingRequestThreads.size}")
        }
    }
}
