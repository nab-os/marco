package dev.nabos.marco

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.nabos.marco.core.PairingInvite
import dev.nabos.marco.core.PeerStore
import dev.nabos.marco.core.Perms
import dev.nabos.marco.findable.FindableService
import dev.nabos.marco.ui.AcceptInviteDialog
import dev.nabos.marco.ui.FindScreen
import dev.nabos.marco.ui.HomeScreen
import dev.nabos.marco.ui.ShowInviteScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                Surface(Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FindableService.sync(this)
    }
}

private enum class Screen { HOME, SHOW_INVITE, FIND }

@Composable
private fun App() {
    val ctx = LocalContext.current
    val store = remember { PeerStore.get(ctx) }
    val peers by store.peers.collectAsStateWithLifecycle()
    val findable by store.findable.collectAsStateWithLifecycle()
    val status by FindableService.status.collectAsStateWithLifecycle()

    var screen by remember { mutableStateOf(Screen.HOME) }
    /** [Peer.id] of the phone the Find screen is searching for. */
    var findingId by remember { mutableStateOf<String?>(null) }
    var pendingInvite by remember { mutableStateOf<PairingInvite?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }

    // Bumped on resume / permission results so checks below are re-evaluated
    // after the user comes back from system settings.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        FindableService.sync(ctx)
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        val invite = PeerStore.decodeInvite(text)
        if (invite == null) scanError = "That QR code isn't a Marco pairing code."
        else pendingInvite = invite
    }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    val hasPermissions = remember(refresh) { Perms.hasBle(ctx) }
    val bluetoothOn = remember(refresh, status) { Perms.bluetoothOn(ctx) }
    val hasDndAccess = remember(refresh) { Perms.hasDndAccess(ctx) }
    val ignoresBatteryOpt = remember(refresh) { Perms.ignoresBatteryOptimizations(ctx) }

    val modifier = Modifier.safeDrawingPadding()
    when (screen) {
        Screen.HOME -> {
            HomeScreen(
                modifier = modifier,
                peers = peers,
                findable = findable,
                status = status,
                hasPermissions = hasPermissions,
                bluetoothOn = bluetoothOn,
                hasDndAccess = hasDndAccess,
                ignoresBatteryOpt = ignoresBatteryOpt,
                scanError = scanError,
                onDismissScanError = { scanError = null },
                onRequestPermissions = { permLauncher.launch(Perms.RUNTIME) },
                onShowInvite = { screen = Screen.SHOW_INVITE },
                onScanInvite = {
                    scanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan the code shown on your other phone")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                },
                onFind = {
                    findingId = it.id
                    screen = Screen.FIND
                },
                onFindableChange = {
                    store.setFindable(it)
                    FindableService.sync(ctx)
                },
                onUnpair = {
                    store.removePeer(it.id)
                    FindableService.sync(ctx)
                },
            )
        }
        Screen.SHOW_INVITE -> ShowInviteScreen(
            modifier = modifier,
            myName = store.deviceName,
            onDone = { newPeer ->
                store.savePeer(newPeer)
                FindableService.sync(ctx)
                screen = Screen.HOME
            },
            onCancel = { screen = Screen.HOME },
        )
        Screen.FIND -> {
            val p = peers.find { it.id == findingId }
            if (p == null) LaunchedEffect(Unit) { screen = Screen.HOME }
            else FindScreen(modifier = modifier, peer = p, onBack = { screen = Screen.HOME })
        }
    }

    pendingInvite?.let { invite ->
        AcceptInviteDialog(
            invite = invite,
            onAccept = { newPeer ->
                store.savePeer(newPeer)
                FindableService.sync(ctx)
                pendingInvite = null
            },
            onDismiss = { pendingInvite = null },
        )
    }
}
