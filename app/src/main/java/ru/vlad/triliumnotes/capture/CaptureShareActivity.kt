package ru.vlad.triliumnotes.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.MainActivity
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.ui.NotesTheme
import java.util.UUID

/** Exported boundary: copies only explicitly granted public-provider data; it never loads a session. */
class CaptureShareActivity : ComponentActivity() {
    private lateinit var captureId: String
    private var failed by mutableStateOf(false)
    private var receiving = false
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.context(newBase))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureId = savedInstanceState?.getString(STATE_ID)?.takeIf { CaptureInbox.validId(it) } ?: UUID.randomUUID().toString()
        setContent {
            NotesTheme {
                BackHandler { discardAndClose() }
                Surface {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        if (failed) {
                            Text(AppLanguage.get(this@CaptureShareActivity, R.string.capture_receive_error), style = MaterialTheme.typography.bodyLarge)
                            Button(onClick = ::receive) { Text(AppLanguage.get(this@CaptureShareActivity, R.string.capture_retry)) }
                            Button(onClick = ::discardAndClose) { Text(AppLanguage.get(this@CaptureShareActivity, R.string.capture_close)) }
                        } else {
                            CircularProgressIndicator()
                            Text(AppLanguage.get(this@CaptureShareActivity, R.string.capture_receiving))
                        }
                    }
                }
            }
        }
        receive()
    }
    private fun receive() {
        if (receiving) return
        receiving = true
        failed = false
        // The activity stays alive until the durable copy commits, retaining the transient URI grants.
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { CaptureInbox(applicationContext).receive(intent, captureId) }
                startActivity(Intent(this@CaptureShareActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            } catch (_: Exception) { failed = true }
            finally { receiving = false }
        }
    }
    private fun discardAndClose() {
        if (receiving) return
        receiving = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { CaptureInbox(applicationContext).discard(captureId) }
                finish()
            } catch (_: Exception) { failed = true }
            finally { receiving = false }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_ID, captureId)
        super.onSaveInstanceState(outState)
    }
    private companion object { const val STATE_ID = "private.capture.id" }
}
