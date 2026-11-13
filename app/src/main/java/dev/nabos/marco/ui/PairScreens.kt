package dev.nabos.marco.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeEncoder
import dev.nabos.marco.core.PairingInvite
import dev.nabos.marco.core.Peer
import dev.nabos.marco.core.PeerStore
import dev.nabos.marco.core.Protocol

/** Inviter side: generate the shared key and show it as a QR code. */
@Composable
fun ShowInviteScreen(
    modifier: Modifier,
    myName: String,
    onDone: (Peer) -> Unit,
    onCancel: () -> Unit,
) {
    val key = remember { Protocol.randomBytes(Protocol.KEY_LEN) }
    val qr: Bitmap = remember(key) {
        val content = PeerStore.encodeInvite(PairingInvite(key, myName))
        BarcodeEncoder().encodeBitmap(
            content, BarcodeFormat.QR_CODE, 720, 720,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1),
        )
    }
    var peerName by remember { mutableStateOf("") }
    val safety = remember(key) { Peer("", key).safetyCode }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pairing code", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "On your other phone, open Marco → Scan pairing code. Keep this code private: " +
                "anyone who scans it can ring this phone.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = "Pairing QR code",
                filterQuality = FilterQuality.None, // keep modules crisp
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        }
        Text("Safety code: $safety", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = peerName,
            onValueChange = { peerName = it.take(40) },
            label = { Text("Name for the other phone") },
            placeholder = { Text("e.g. Sam's Galaxy") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Name the other phone so you can tell your paired phones apart, then tap Done once it shows the same safety code.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(52.dp)) { Text("Cancel") }
            Button(
                onClick = { onDone(Peer(peerName.trim(), key)) },
                enabled = peerName.isNotBlank(),
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Done") }
        }
    }
}

/** Scanner side: confirm the invite and name the other phone. */
@Composable
fun AcceptInviteDialog(invite: PairingInvite, onAccept: (Peer) -> Unit, onDismiss: () -> Unit) {
    var name by remember(invite) { mutableStateOf(invite.inviterName) }
    val safety = remember(invite) { Peer("", invite.key).safetyCode }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with this phone?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Safety code: $safety\nCheck it matches the code on the other phone.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name for the other phone") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAccept(Peer(name.trim().ifBlank { "Other phone" }, invite.key)) }) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
