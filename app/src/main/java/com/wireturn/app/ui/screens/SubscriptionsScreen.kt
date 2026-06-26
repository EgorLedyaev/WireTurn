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
    onRefresh: (String) -> Unit,
    onRefreshAll: () -> Unit,
    onDelete: (String, Boolean) -> Unit,
    onToggle: (String, Boolean) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
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
                    modifier = Modifier.padding(8.dp)
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
                        SectionItem(position = pos) {
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
                                    Text(
                                        stringResource(R.string.subscriptions_last_sync, sub.lastStatus),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row {
                                    TextButton(onClick = { onRefresh(sub.id) }) { Text(stringResource(R.string.subscriptions_refresh)) }
                                    TextButton(onClick = { deleteTarget = sub }) { Text(stringResource(R.string.subscriptions_delete)) }
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
        AddSubscriptionDialog(
            onDismiss = { showAdd = false },
            onConfirm = { n, u, iv -> onAdd(n, u, iv); showAdd = false }
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
private fun AddSubscriptionDialog(onDismiss: () -> Unit, onConfirm: (String, String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf("12") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subscriptions_add)) },
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
            ) { Text(stringResource(R.string.subscriptions_add_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
