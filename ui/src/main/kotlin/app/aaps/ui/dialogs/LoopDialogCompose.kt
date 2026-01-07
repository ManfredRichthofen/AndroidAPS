package app.aaps.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentManager
import app.aaps.core.data.model.RM
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.ConfigBuilder
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.Translator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.extensions.runOnUiThread
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.ui.R
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.delay
import javax.inject.Inject

class LoopDialogCompose : DaggerDialogFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var ctx: Context
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var loop: Loop
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var configBuilder: ConfigBuilder
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var config: Config
    @Inject lateinit var translator: Translator

    private var queryingProtection = false
    private var showOkCancel: Boolean = true
    private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)

    val disposable = CompositeDisposable()

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putBoolean("showOkCancel", showOkCancel)
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): android.view.View {
        (savedInstanceState ?: arguments)?.let { bundle ->
            showOkCancel = bundle.getBoolean("showOkCancel", true)
        }
        
        return ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme {
                    LoopDialogContent(
                        showOkCancel = showOkCancel,
                        onDismiss = { dismiss() },
                        onAction = { action -> handleAction(action) }
                    )
                }
            }
        }
    }

    @Composable
    private fun LoopDialogContent(
        showOkCancel: Boolean,
        onDismiss: () -> Unit,
        onAction: (LoopAction) -> Unit
    ) {
        var dialogState by remember { mutableStateOf(getDialogState()) }

        LaunchedEffect(Unit) {
            while (true) {
                delay(15000L)
                dialogState = getDialogState()
            }
        }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_running_mode),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = dialogState.runningModeText,
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Reasons/Limitations
                    if (dialogState.showReasons) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp)
                        ) {
                            Text(
                                text = stringResource(app.aaps.core.ui.R.string.limitations),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = dialogState.reasonsText,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Loop Section
                    if (dialogState.showLoopSection) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(app.aaps.core.ui.R.string.loop),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (dialogState.showClosedLoop) {
                                LoopButton(
                                    text = stringResource(app.aaps.core.ui.R.string.closedloop),
                                    iconRes = R.drawable.ic_loop_closed,
                                    onClick = {
                                        if (showOkCancel) {
                                            showConfirmation(
                                                rh.gs(app.aaps.core.ui.R.string.closedloop)
                                            ) { onAction(LoopAction.ClosedLoop) }
                                        } else {
                                            onAction(LoopAction.ClosedLoop)
                                        }
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (dialogState.showLgsLoop) {
                                LoopButton(
                                    text = stringResource(app.aaps.core.ui.R.string.lowglucosesuspend),
                                    iconRes = R.drawable.ic_loop_lgs,
                                    onClick = {
                                        if (showOkCancel) {
                                            showConfirmation(
                                                rh.gs(app.aaps.core.ui.R.string.lowglucosesuspend)
                                            ) { onAction(LoopAction.LgsLoop) }
                                        } else {
                                            onAction(LoopAction.LgsLoop)
                                        }
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (dialogState.showOpenLoop) {
                                LoopButton(
                                    text = stringResource(app.aaps.core.ui.R.string.openloop),
                                    iconRes = R.drawable.ic_loop_open,
                                    onClick = {
                                        if (showOkCancel) {
                                            showConfirmation(
                                                rh.gs(app.aaps.core.ui.R.string.openloop)
                                            ) { onAction(LoopAction.OpenLoop) }
                                        } else {
                                            onAction(LoopAction.OpenLoop)
                                        }
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (dialogState.showDisable) {
                                LoopButton(
                                    text = stringResource(app.aaps.core.ui.R.string.disableloop),
                                    iconRes = R.drawable.ic_loop_disabled,
                                    onClick = {
                                        if (showOkCancel) {
                                            showConfirmation(
                                                rh.gs(app.aaps.core.ui.R.string.disableloop)
                                            ) { onAction(LoopAction.DisableLoop) }
                                        } else {
                                            onAction(LoopAction.DisableLoop)
                                        }
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    // Suspend Section
                    if (dialogState.showSuspendSection) {
                        Text(
                            text = dialogState.suspendHeaderText,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        )
                        
                        if (dialogState.showResume) {
                            LoopButton(
                                text = stringResource(app.aaps.core.ui.R.string.resumeloop),
                                iconRes = R.drawable.ic_loop_resume,
                                onClick = {
                                    if (showOkCancel) {
                                        showConfirmation(
                                            rh.gs(R.string.resume)
                                        ) { onAction(LoopAction.Resume) }
                                    } else {
                                        onAction(LoopAction.Resume)
                                    }
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        
                        if (dialogState.showSuspendButtons) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                LoopButton(
                                    text = stringResource(R.string.duration1h),
                                    iconRes = R.drawable.ic_loop_paused,
                                    onClick = {
                                        if (showOkCancel) {
                                            showConfirmation(
                                                rh.gs(R.string.suspendloopfor1h)
                                            ) { onAction(LoopAction.Suspend1h) }
                                        } else {
                                            onAction(LoopAction.Suspend1h)
                                        }
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                LoopButton(
                                    text = stringResource(R.string.duration2h),
                                    iconRes = R.drawable.ic_loop_paused,
                                    onClick = {
                                        if (showOkCancel) {
                                            showConfirmation(
                                                rh.gs(R.string.suspendloopfor2h)
                                            ) { onAction(LoopAction.Suspend2h) }
                                        } else {
                                            onAction(LoopAction.Suspend2h)
                                        }
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                LoopButton(
                                    text = stringResource(R.string.duration3h),
                                    iconRes = R.drawable.ic_loop_paused,
                                    onClick = {
                                        if (showOkCancel) {
                                            showConfirmation(
                                                rh.gs(R.string.suspendloopfor3h)
                                            ) { onAction(LoopAction.Suspend3h) }
                                        } else {
                                            onAction(LoopAction.Suspend3h)
                                        }
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                LoopButton(
                                    text = stringResource(R.string.duration10h),
                                    iconRes = R.drawable.ic_loop_paused,
                                    onClick = {
                                        if (showOkCancel) {
                                            showConfirmation(
                                                rh.gs(R.string.suspendloopfor10h)
                                            ) { onAction(LoopAction.Suspend10h) }
                                        } else {
                                            onAction(LoopAction.Suspend10h)
                                        }
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    // Pump Section
                    if (dialogState.showPumpSection) {
                        if (dialogState.showPumpHeader) {
                            Text(
                                text = dialogState.pumpHeaderText,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(horizontal = 10.dp)
                            )
                        }
                        
                        if (dialogState.showReconnect) {
                            LoopButton(
                                text = stringResource(R.string.reconnect),
                                iconRes = R.drawable.ic_loop_reconnect,
                                onClick = {
                                    if (showOkCancel) {
                                        showConfirmation(
                                            rh.gs(R.string.reconnect)
                                        ) { onAction(LoopAction.Reconnect) }
                                    } else {
                                        onAction(LoopAction.Reconnect)
                                    }
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        
                        if (dialogState.showDisconnectButtons) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (dialogState.showDisconnect15m) {
                                    LoopButton(
                                        text = stringResource(R.string.duration15m),
                                        iconRes = R.drawable.ic_loop_disconnected,
                                        onClick = {
                                            if (showOkCancel) {
                                                showConfirmation(
                                                    rh.gs(R.string.disconnectpumpfor15m)
                                                ) { onAction(LoopAction.Disconnect15m) }
                                            } else {
                                                onAction(LoopAction.Disconnect15m)
                                            }
                                            onDismiss()
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (dialogState.showDisconnect30m) {
                                    LoopButton(
                                        text = stringResource(R.string.duration30m),
                                        iconRes = R.drawable.ic_loop_disconnected,
                                        onClick = {
                                            if (showOkCancel) {
                                                showConfirmation(
                                                    rh.gs(R.string.disconnectpumpfor30m)
                                                ) { onAction(LoopAction.Disconnect30m) }
                                            } else {
                                                onAction(LoopAction.Disconnect30m)
                                            }
                                            onDismiss()
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                LoopButton(
                                    text = stringResource(R.string.duration1h),
                                    iconRes = R.drawable.ic_loop_disconnected,
                                    onClick = {
                                        if (showOkCancel) {
                                            showConfirmation(
                                                rh.gs(R.string.disconnectpumpfor1h)
                                            ) { onAction(LoopAction.Disconnect1h) }
                                        } else {
                                            onAction(LoopAction.Disconnect1h)
                                        }
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                LoopButton(
                                    text = stringResource(R.string.duration2h),
                                    iconRes = R.drawable.ic_loop_disconnected,
                                    onClick = {
                                        if (showOkCancel) {
                                            showConfirmation(
                                                rh.gs(R.string.disconnectpumpfor2h)
                                            ) { onAction(LoopAction.Disconnect2h) }
                                        } else {
                                            onAction(LoopAction.Disconnect2h)
                                        }
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                LoopButton(
                                    text = stringResource(R.string.duration3h),
                                    iconRes = R.drawable.ic_loop_disconnected,
                                    onClick = {
                                        if (showOkCancel) {
                                            showConfirmation(
                                                rh.gs(R.string.disconnectpumpfor3h)
                                            ) { onAction(LoopAction.Disconnect3h) }
                                        } else {
                                            onAction(LoopAction.Disconnect3h)
                                        }
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    // Cancel Button
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(stringResource(app.aaps.core.ui.R.string.cancel))
                    }
                }
            }
        }
    }

    @Composable
    private fun LoopButton(
        text: String,
        iconRes: Int,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Button(
            onClick = onClick,
            modifier = modifier.padding(horizontal = 4.dp),
            colors = ButtonDefaults.buttonColors()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    private fun getDialogState(): DialogState {
        val pumpDescription: PumpDescription = activePlugin.activePump.pumpDescription
        val runningModeRecord = loop.runningModeRecord
        val runningMode = runningModeRecord.mode
        val allowedModes = loop.allowedNextModes()

        return DialogState(
            runningModeText = translator.translate(runningMode),
            showReasons = runningModeRecord.reasons?.isNotEmpty() == true,
            reasonsText = runningModeRecord.reasons ?: "",
            showLoopSection = allowedModes.contains(RM.Mode.DISABLED_LOOP) ||
                    allowedModes.contains(RM.Mode.OPEN_LOOP) ||
                    allowedModes.contains(RM.Mode.CLOSED_LOOP) ||
                    allowedModes.contains(RM.Mode.CLOSED_LOOP_LGS),
            showSuspendSection = allowedModes.contains(RM.Mode.SUSPENDED_BY_USER) ||
                    (allowedModes.contains(RM.Mode.RESUME) && runningMode == RM.Mode.SUSPENDED_BY_USER),
            showPumpSection = allowedModes.contains(RM.Mode.DISCONNECTED_PUMP) ||
                    (allowedModes.contains(RM.Mode.RESUME) && runningMode == RM.Mode.DISCONNECTED_PUMP),
            showDisconnectButtons = allowedModes.contains(RM.Mode.DISCONNECTED_PUMP) && config.APS,
            showPumpHeader = config.APS,
            showReconnect = allowedModes.contains(RM.Mode.RESUME) && runningMode == RM.Mode.DISCONNECTED_PUMP,
            showSuspendButtons = allowedModes.contains(RM.Mode.SUSPENDED_BY_USER),
            showResume = allowedModes.contains(RM.Mode.RESUME) && runningMode == RM.Mode.SUSPENDED_BY_USER,
            showDisable = allowedModes.contains(RM.Mode.DISABLED_LOOP),
            showClosedLoop = allowedModes.contains(RM.Mode.CLOSED_LOOP),
            showLgsLoop = allowedModes.contains(RM.Mode.CLOSED_LOOP_LGS),
            showOpenLoop = allowedModes.contains(RM.Mode.OPEN_LOOP),
            showDisconnect15m = pumpDescription.tempDurationStep15mAllowed,
            showDisconnect30m = pumpDescription.tempDurationStep30mAllowed,
            suspendHeaderText = if (runningMode == RM.Mode.SUSPENDED_BY_USER)
                rh.gs(app.aaps.core.ui.R.string.resumeloop)
            else
                rh.gs(app.aaps.core.ui.R.string.suspendloop),
            pumpHeaderText = if (runningMode == RM.Mode.DISCONNECTED_PUMP)
                rh.gs(R.string.reconnect)
            else
                rh.gs(R.string.disconnectpump)
        )
    }

    private fun showConfirmation(description: String, action: () -> Unit) {
        activity?.let { activity ->
            OKDialog.showConfirmation(
                activity,
                rh.gs(app.aaps.core.ui.R.string.confirm),
                description,
                Runnable { action() }
            )
        }
    }

    private fun handleAction(action: LoopAction) {
        val profile = profileFunction.getProfile() ?: return
        when (action) {
            LoopAction.ClosedLoop -> {
                loop.handleRunningModeChange(
                    newRM = RM.Mode.CLOSED_LOOP,
                    action = Action.CLOSED_LOOP_MODE,
                    source = Sources.LoopDialog,
                    profile = profile
                )
            }
            LoopAction.LgsLoop -> {
                loop.handleRunningModeChange(
                    newRM = RM.Mode.CLOSED_LOOP_LGS,
                    action = Action.LGS_LOOP_MODE,
                    source = Sources.LoopDialog,
                    profile = profile
                )
            }
            LoopAction.OpenLoop -> {
                loop.handleRunningModeChange(
                    newRM = RM.Mode.OPEN_LOOP,
                    action = Action.OPEN_LOOP_MODE,
                    source = Sources.LoopDialog,
                    profile = profile
                )
            }
            LoopAction.DisableLoop -> {
                loop.handleRunningModeChange(
                    newRM = RM.Mode.DISABLED_LOOP,
                    durationInMinutes = Int.MAX_VALUE,
                    action = Action.LOOP_DISABLED,
                    source = Sources.LoopDialog,
                    profile = profile
                )
            }
            LoopAction.Resume -> {
                loop.handleRunningModeChange(
                    newRM = RM.Mode.RESUME,
                    action = Action.RESUME,
                    source = Sources.LoopDialog,
                    profile = profile
                )
                preferences.put(BooleanNonKey.ObjectivesReconnectUsed, true)
            }
            LoopAction.Reconnect -> {
                loop.handleRunningModeChange(
                    newRM = RM.Mode.RESUME,
                    action = Action.RECONNECT,
                    source = Sources.LoopDialog,
                    profile = profile
                )
                preferences.put(BooleanNonKey.ObjectivesReconnectUsed, true)
            }
            LoopAction.Suspend1h -> {
                loop.handleRunningModeChange(
                    newRM = RM.Mode.SUSPENDED_BY_USER,
                    durationInMinutes = T.hours(1).mins().toInt(),
                    action = Action.SUSPEND,
                    source = Sources.LoopDialog,
                    profile = profile
                )
            }
            LoopAction.Suspend2h -> {
                loop.handleRunningModeChange(
                    newRM = RM.Mode.SUSPENDED_BY_USER,
                    durationInMinutes = T.hours(2).mins().toInt(),
                    action = Action.SUSPEND,
                    source = Sources.LoopDialog,
                    profile = profile
                )
            }
            LoopAction.Suspend3h -> {
                loop.handleRunningModeChange(
                    newRM = RM.Mode.SUSPENDED_BY_USER,
                    durationInMinutes = T.hours(3).mins().toInt(),
                    action = Action.SUSPEND,
                    source = Sources.LoopDialog,
                    profile = profile
                )
            }
            LoopAction.Suspend10h -> {
                loop.handleRunningModeChange(
                    newRM = RM.Mode.SUSPENDED_BY_USER,
                    durationInMinutes = T.hours(10).mins().toInt(),
                    action = Action.SUSPEND,
                    source = Sources.LoopDialog,
                    profile = profile
                )
            }
            LoopAction.Disconnect15m -> {
                loop.handleRunningModeChange(
                    newRM = RM.Mode.DISCONNECTED_PUMP,
                    durationInMinutes = 15,
                    action = Action.DISCONNECT,
                    source = Sources.LoopDialog,
                    profile = profile
                )
            }
            LoopAction.Disconnect30m -> {
                loop.handleRunningModeChange(
                    newRM = RM.Mode.DISCONNECTED_PUMP,
                    durationInMinutes = 30,
                    action = Action.DISCONNECT,
                    source = Sources.LoopDialog,
                    profile = profile
                )
            }
            LoopAction.Disconnect1h -> {
                loop.handleRunningModeChange(
                    newRM = RM.Mode.DISCONNECTED_PUMP,
                    durationInMinutes = 60,
                    action = Action.DISCONNECT,
                    source = Sources.LoopDialog,
                    profile = profile
                )
                preferences.put(BooleanNonKey.ObjectivesDisconnectUsed, true)
            }
            LoopAction.Disconnect2h -> {
                loop.handleRunningModeChange(
                    newRM = RM.Mode.DISCONNECTED_PUMP,
                    durationInMinutes = 120,
                    action = Action.DISCONNECT,
                    source = Sources.LoopDialog,
                    profile = profile
                )
            }
            LoopAction.Disconnect3h -> {
                loop.handleRunningModeChange(
                    newRM = RM.Mode.DISCONNECTED_PUMP,
                    durationInMinutes = 180,
                    action = Action.DISCONNECT,
                    source = Sources.LoopDialog,
                    profile = profile
                )
            }
        }
    }

    override fun show(manager: FragmentManager, tag: String?) {
        try {
            manager.beginTransaction().let {
                it.add(this, tag)
                it.commitAllowingStateLoss()
            }
        } catch (e: IllegalStateException) {
            aapsLogger.debug(e.localizedMessage ?: e.toString())
        }
    }

    override fun onResume() {
        super.onResume()
        if (!queryingProtection) {
            queryingProtection = true
            activity?.let { activity ->
                val cancelFail = {
                    queryingProtection = false
                    aapsLogger.debug(LTag.APS, "Dialog canceled on resume protection: ${this.javaClass.simpleName}")
                    ToastUtils.warnToast(ctx, R.string.dialog_canceled)
                    dismiss()
                }
                protectionCheck.queryProtection(
                    activity,
                    ProtectionCheck.Protection.BOLUS,
                    { queryingProtection = false },
                    cancelFail,
                    cancelFail
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
        disposable.clear()
    }

    data class DialogState(
        val runningModeText: String,
        val showReasons: Boolean,
        val reasonsText: String,
        val showLoopSection: Boolean,
        val showSuspendSection: Boolean,
        val showPumpSection: Boolean,
        val showDisconnectButtons: Boolean,
        val showPumpHeader: Boolean,
        val showReconnect: Boolean,
        val showSuspendButtons: Boolean,
        val showResume: Boolean,
        val showDisable: Boolean,
        val showClosedLoop: Boolean,
        val showLgsLoop: Boolean,
        val showOpenLoop: Boolean,
        val showDisconnect15m: Boolean,
        val showDisconnect30m: Boolean,
        val suspendHeaderText: String,
        val pumpHeaderText: String
    )

    enum class LoopAction {
        ClosedLoop,
        LgsLoop,
        OpenLoop,
        DisableLoop,
        Resume,
        Reconnect,
        Suspend1h,
        Suspend2h,
        Suspend3h,
        Suspend10h,
        Disconnect15m,
        Disconnect30m,
        Disconnect1h,
        Disconnect2h,
        Disconnect3h
    }
}
