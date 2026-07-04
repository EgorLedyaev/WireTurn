package com.wireturn.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.data.Subscription
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    subscriptions: List<Subscription>,
    onBack: () -> Unit,
    onAdd: (String, String, Int) -> Unit,
    onEdit: (String, String, String, Int) -> Unit,
    onRefresh: (String) -> Unit,
    onRefreshAll: () -> Unit,
    onDelete: (String, Boolean) -> Unit,
    onToggle: (String, Boolean) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Subscription?>(null) }
    var deleteTarget by remember { mutableStateOf<Subscription?>(null) }
    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.subscriptions_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = onRefreshAll) {
                        Icon(painterResource(R.drawable.refresh_24px), contentDescription = stringResource(R.string.subscriptions_refresh_all))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp)
        ) {
            SectionGroup {
                SectionItem(position = ItemPosition.Single, onClick = { showAdd = true }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(painterResource(R.drawable.add_24px), contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(R.string.subscriptions_add), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (subscriptions.isEmpty()) {
                Text(
                    stringResource(R.string.subscriptions_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp)
                )
            } else {
                SectionGroup(title = stringResource(R.string.subscriptions_title)) {
                    subscriptions.forEachIndexed { i, sub ->
                        val pos = when {
                            subscriptions.size == 1 -> ItemPosition.Single
                            i == 0 -> ItemPosition.Top
                            i == subscriptions.lastIndex -> ItemPosition.Bottom
                            else -> ItemPosition.Middle
                        }
                        SectionItem(position = pos, onClick = { editTarget = sub }) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            sub.name.ifBlank { sub.url },
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            sub.url,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Switch(checked = sub.enabled, onCheckedChange = { onToggle(sub.id, it) })
                                }
                                if (sub.lastStatus.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    val isError = sub.lastStatus == "fetch_failed" || sub.lastStatus == "parse_error"
                                    val statusLabel = when (sub.lastStatus) {
                                        "ok" -> stringResource(R.string.subscriptions_status_ok, sub.lastProfileCount)
                                        "fetch_failed" -> stringResource(R.string.subscriptions_status_fetch_failed)
                                        "parse_error" -> stringResource(R.string.subscriptions_status_parse_error)
                                        else -> sub.lastStatus // legacy raw value
                                    }
                                    val age = if (sub.lastUpdated > 0)
                                        android.text.format.DateUtils.getRelativeTimeSpanString(sub.lastUpdated).toString()
                                    else ""
                                    Text(
                                        if (age.isNotBlank()) "$statusLabel · $age" else statusLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row {
                                    TextButton(onClick = { onRefresh(sub.id) }) { Text(stringResource(R.string.subscriptions_refresh)) }
                                    TextButton(onClick = { editTarget = sub }) { Text(stringResource(R.string.subscriptions_edit)) }
                                    TextButton(onClick = { deleteTarget = sub }) { Text(stringResource(R.string.subscriptions_delete), color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAdd) {
        SubscriptionEditorDialog(
            title = stringResource(R.string.subscriptions_add),
            confirmLabel = stringResource(R.string.subscriptions_add_confirm),
            onDismiss = { showAdd = false },
            onConfirm = { n, u, iv -> onAdd(n, u, iv); showAdd = false }
        )
    }

    editTarget?.let { tgt ->
        SubscriptionEditorDialog(
            title = stringResource(R.string.subscriptions_edit_title),
            confirmLabel = stringResource(R.string.subscriptions_save),
            initialName = tgt.name,
            initialUrl = tgt.url,
            initialInterval = tgt.intervalHours,
            onDismiss = { editTarget = null },
            onConfirm = { n, u, iv -> onEdit(tgt.id, n, u, iv); editTarget = null }
        )
    }

    deleteTarget?.let { tgt ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.subscriptions_delete_title)) },
            text = { Text(stringResource(R.string.subscriptions_delete_msg, tgt.name.ifBlank { tgt.url })) },
            confirmButton = {
                TextButton(onClick = { onDelete(tgt.id, true); deleteTarget = null }) { Text(stringResource(R.string.subscriptions_delete_with_profiles)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onDelete(tgt.id, false); deleteTarget = null }) { Text(stringResource(R.string.subscriptions_keep_profiles)) }
                    TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
                }
            }
        )
    }
}

@Composable
private fun SubscriptionEditorDialog(
    title: String,
    confirmLabel: String,
    initialName: String = "",
    initialUrl: String = "",
    initialInterval: Int = 12,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var interval by remember { mutableStateOf(initialInterval.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.subscriptions_name_hint)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text(stringResource(R.string.subscriptions_url_hint)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = interval,
                    onValueChange = { v -> interval = v.filter { it.isDigit() }.take(4) },
                    label = { Text(stringResource(R.string.subscriptions_interval_hint)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = { onConfirm(name.trim(), url.trim(), interval.toIntOrNull() ?: 12) }
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
