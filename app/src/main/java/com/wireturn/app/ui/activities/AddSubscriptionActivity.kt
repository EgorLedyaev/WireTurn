package com.wireturn.app.ui.activities

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.wireturn.app.R
import com.wireturn.app.domain.SubscriptionManager
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * Handles wireturn://addsub?url=<encoded>&name=<encoded> deep links (one-tap onboarding).
 * Always confirms with the user, naming the host, before adding (anti-phishing); only
 * http/https inner URLs are accepted.
 */
class AddSubscriptionActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent?.data
        val inner = data?.getQueryParameter("url")?.trim()
        val name = data?.getQueryParameter("name")?.trim()?.take(64) ?: ""
        val parsed = try { inner?.toUri() } catch (_: Exception) { null }
        val scheme = parsed?.scheme?.lowercase()
        val host = parsed?.host
        val valid = !inner.isNullOrBlank() && (scheme == "http" || scheme == "https") && !host.isNullOrBlank()

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                if (!valid) {
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text(stringResource(R.string.onboarding_invalid_title)) },
                        text = { Text(stringResource(R.string.onboarding_invalid_msg)) },
                        confirmButton = { TextButton(onClick = { finish() }) { Text(stringResource(R.string.ok)) } }
                    )
                } else {
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text(stringResource(R.string.onboarding_confirm_title)) },
                        text = { Text(stringResource(R.string.onboarding_confirm_msg, host!!)) },
                        confirmButton = {
                            TextButton(onClick = {
                                lifecycleScope.launch {
                                    val outcome = viewModel.addSubscriptionAwait(name, inner!!)
                                    val msg = when (outcome) {
                                        SubscriptionManager.AddOutcome.ADDED -> getString(R.string.onboarding_added)
                                        SubscriptionManager.AddOutcome.ALREADY_EXISTS -> getString(R.string.onboarding_exists)
                                        SubscriptionManager.AddOutcome.INVALID -> getString(R.string.onboarding_invalid_msg)
                                    }
                                    Toast.makeText(this@AddSubscriptionActivity, msg, Toast.LENGTH_LONG).show()
                                    finish()
                                }
                            }) { Text(stringResource(R.string.subscriptions_add_confirm)) }
                        },
                        dismissButton = { TextButton(onClick = { finish() }) { Text(stringResource(R.string.cancel)) } }
                    )
                }
            }
        }
    }
}
