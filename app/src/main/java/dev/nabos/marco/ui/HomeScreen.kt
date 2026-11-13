package dev.nabos.marco.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nabos.marco.core.Peer
import dev.nabos.marco.findable.FindableService
import dev.nabos.marco.findable.FindableStatus

@Composable
fun HomeScreen(
    modifier: Modifier,
    peers: List<Peer>,
    findable: Boolean,
    status: FindableStatus,
    hasPermissions: Boolean,
    bluetoothOn: Boolean,
    hasDndAccess: Boolean,
    ignoresBatteryOpt: Boolean,
    scanError: String?,
    onDismissScanError: () -> Unit,
    onRequestPermissions: () -> Unit,
    onShowInvite: () -> Unit,
    onScanInvite: () -> Unit,
    onFind: (Peer) -> Unit,
    onFindableChange: (Boolean) -> Unit,
    onUnpair: (Peer) -> Unit,
) {
    val ctx = LocalContext.current
    var confirmUnpair by remember { mutableStateOf<Peer?>(null) }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Marco", fontSize = 34.sp, fontWeight = FontWeight.Bold)

        if (status.ringing) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("This phone is ringing", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Button(onClick = { FindableService.stopRinging(ctx) }) { Text("Stop") }
                }
            }
        }

        if (!hasPermissions) {
            InfoCard(
                title = "Bluetooth access needed",
                body = "Marco uses Bluetooth to find your other phone and to be found. Nothing leaves your phones.",
                action = "Allow" to onRequestPermissions,
            )
        } else if (!bluetoothOn) {
            InfoCard(
                title = "Bluetooth is off",
                body = "Turn Bluetooth on so your phones can see each other.",
                action = "Open settings" to { ctx.startSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            )
        }

        if (peers.isEmpty()) {
            Unpaired(onShowInvite, onScanInvite, enabled = hasPermissions)
        } else {
            Paired(
                peers = peers,
                findable = findable,
                status = status,
                hasPermissions = hasPermissions,
                hasDndAccess = hasDndAccess,
                ignoresBatteryOpt = ignoresBatteryOpt,
                onFind = onFind,
                onFindableChange = onFindableChange,
                onUnpair = { confirmUnpair = it },
                onShowInvite = onShowInvite,
                onScanInvite = onScanInvite,
            )
        }
    }

    confirmUnpair?.let { peer ->
        AlertDialog(
            onDismissRequest = { confirmUnpair = null },
            title = { Text("Unpair ${peer.name}?") },
            text = { Text("You'll need to scan a pairing code again. Unpair on both phones.") },
            confirmButton = {
                TextButton(onClick = { confirmUnpair = null; onUnpair(peer) }) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = null }) { Text("Cancel") } },
        )
    }
    if (scanError != null) {
        AlertDialog(
            onDismissRequest = onDismissScanError,
            title = { Text("Can't pair") },
            text = { Text(scanError) },
            confirmButton = { TextButton(onClick = onDismissScanError) { Text("OK") } },
        )
    }
}

@Composable
private fun Unpaired(onShowInvite: () -> Unit, onScanInvite: () -> Unit, enabled: Boolean) {
    Text(
        "Pair each of your phones once. Install Marco on them, then show the code on one " +
            "and scan it with the other. You can pair more phones later.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(onClick = onShowInvite, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Show pairing code")
    }
    OutlinedButton(onClick = onScanInvite, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Scan pairing code")
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun Paired(
    peers: List<Peer>,
    findable: Boolean,
    status: FindableStatus,
    hasPermissions: Boolean,
    hasDndAccess: Boolean,
    ignoresBatteryOpt: Boolean,
    onFind: (Peer) -> Unit,
    onFindableChange: (Boolean) -> Unit,
    onUnpair: (Peer) -> Unit,
    onShowInvite: () -> Unit,
    onScanInvite: () -> Unit,
) {
    val ctx = LocalContext.current

    for (peer in peers) {
        Button(
            onClick = { onFind(peer) },
            enabled = hasPermissions,
            modifier = Modifier.fillMaxWidth().height(if (peers.size == 1) 96.dp else 72.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("Find ${peer.name}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("This phone is findable", fontWeight = FontWeight.SemiBold)
                    Text(findableLine(findable, status), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = findable, onCheckedChange = onFindableChange, enabled = hasPermissions)
            }
            if (status.running) {
                TextButton(onClick = { FindableService.testRing(ctx) }) { Text("Test ring (5 s)") }
            }
        }
    }

    if (!hasDndAccess) {
        InfoCard(
            title = "Ring through Do Not Disturb",
            body = "Marco already rings in silent mode. Allow Do Not Disturb access so it also rings when DND blocks alarms.",
            action = "Allow" to { ctx.startSettings(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
        )
    }
    if (findable && !ignoresBatteryOpt) {
        InfoCard(
            title = "Stay findable in the background",
            body = "Some phones kill background apps aggressively. Exempting Marco keeps it findable. It uses very little battery.",
            action = "Allow" to {
                ctx.startSettings(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                )
            },
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Paired phones", fontWeight = FontWeight.SemiBold)
            for (peer in peers) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(peer.name)
                        Text(
                            "Safety code ${peer.safetyCode}. It should match on both phones.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { onUnpair(peer) }) {
                        Text("Unpair", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Pair another phone", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onShowInvite, enabled = hasPermissions, modifier = Modifier.weight(1f)) {
                    Text("Show code")
                }
                OutlinedButton(onClick = onScanInvite, enabled = hasPermissions, modifier = Modifier.weight(1f)) {
                    Text("Scan code")
                }
            }
        }
    }
}

private fun findableLine(findable: Boolean, s: FindableStatus): String = when {
    !findable -> "Off. Your paired phones can't ring this one."
    s.error != null -> s.error
    s.connectedFinders > 0 -> "A paired phone is looking for this one right now"
    s.advertising -> "On. Broadcasting a private, rotating ID."
    s.running -> "Starting…"
    else -> "Not running"
}

@Composable
fun InfoCard(title: String, body: String, action: Pair<String, () -> Unit>?) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            action?.let { (label, onClick) ->
                TextButton(onClick = onClick, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(label) }
            }
        }
    }
}

/** Some OEM builds lack a given settings screen; don't crash over it. */
private fun android.content.Context.startSettings(intent: Intent) {
    runCatching { startActivity(intent) }
}
