package app.lightai.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.lightai.MainViewModel
import app.lightai.R
import app.lightai.data.Constants.AppDrawerFlag
import app.lightai.data.Prefs
import app.lightai.ui.compose.CustomScrollView
import app.lightai.ui.compose.SettingsComposable.ContentContainer
import app.lightai.ui.compose.SettingsComposable.PrefsToggleTextButton
import app.lightai.ui.compose.SettingsComposable.SelectorButton
import app.lightai.ui.compose.SettingsComposable.SettingsHeader
import app.lightai.ui.compose.SettingsComposable.SimpleTextButton

class SettingsFragment : Fragment() {
    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.getInstance(requireContext())
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = composeView(onSwipeBack = ::goBack) { Settings() }

    @Composable
    private fun Settings() {
        val versionName = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: ""
        Column {
            SettingsHeader(
                title = stringResource(R.string.settings_title, versionName),
                onBack = ::goBack,
            )

            ContentContainer {
                CustomScrollView(verticalArrangement = Arrangement.spacedBy(33.5.dp)) {
                    if (!isAccessibilityEnabled()) {
                        SimpleTextButton(
                            title = stringResource(R.string.settings_a11y_off_banner),
                        ) {
                            startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }
                    PrefsToggleTextButton(
                        title = stringResource(R.string.settings_auto_rotate),
                        initialValue = prefs.autoRotateEnabled,
                        onValueChange = {
                            prefs.autoRotateEnabled = it
                            requireActivity().recreate()
                        },
                    )
                    PrefsToggleTextButton(
                        title = stringResource(R.string.settings_large_buttons),
                        initialValue = prefs.largeButtonMode,
                        onValueChange = {
                            prefs.largeButtonMode = it
                        },
                    )
                    PrefsToggleTextButton(
                        title = stringResource(R.string.settings_kiosk_enabled),
                        initialValue = prefs.kioskScreenEnabled,
                        onValueChange = {
                            prefs.kioskScreenEnabled = it
                            requireActivity().recreate()
                        },
                    )
                    PrefsToggleTextButton(
                        title = stringResource(R.string.settings_tool_row),
                        initialValue = prefs.toolRowEnabled,
                        onValueChange = {
                            prefs.toolRowEnabled = it
                        },
                    )
                    SimpleTextButton(
                        title = pairingLabel(),
                    ) {
                        findNavController().navigate(R.id.pairingFragment)
                    }
                    if (app.lightai.data.SecurePrefs.getInstance(requireContext()).gatewayConnectConfig != null) {
                        SimpleTextButton(
                            title = gatewayConnectionLabel(),
                        ) {
                            val client = app.lightai.helper.GatewayClient.shared()
                            val cfg = app.lightai.data.SecurePrefs.getInstance(requireContext()).gatewayConnectConfig
                            if (cfg != null && client.status.value !=
                                app.lightai.helper.GatewayClient.Status.Connected
                            ) {
                                client.connect(requireContext(), cfg)
                            } else {
                                disconnectGateway()
                            }
                        }
                        SimpleTextButton(
                            title = stringResource(R.string.settings_unpair_gateway),
                        ) {
                            val securePrefs = app.lightai.data.SecurePrefs.getInstance(requireContext())
                            if (securePrefs.gatewayConnectConfig != null) {
                                securePrefs.clearGatewaySetupCode()
                                disconnectGateway()
                                requireActivity().recreate()
                            }
                        }
                    }
                    SelectorButton(
                        label = stringResource(R.string.settings_invert_colours),
                        value =
                            when (prefs.themeMode) {
                                Prefs.ThemeMode.Dark -> stringResource(R.string.settings_theme_dark)
                                Prefs.ThemeMode.Light -> stringResource(R.string.settings_theme_light)
                                Prefs.ThemeMode.Automatic -> stringResource(R.string.settings_theme_automatic)
                            },
                        onClick = { findNavController().navigate(R.id.action_settingsFragment_to_themeModeFragment) },
                    )
                    SimpleTextButton(
                        stringResource(R.string.settings_pages),
                    ) { findNavController().navigate(R.id.action_settingsFragment_to_pagesFragment) }
                    SimpleTextButton(
                        stringResource(R.string.settings_status_bar),
                    ) { findNavController().navigate(R.id.action_settingsFragment_to_statusBarFragment) }
                    SimpleTextButton(stringResource(R.string.settings_gestures)) {
                        findNavController().navigate(R.id.action_settingsFragment_to_gesturesFragment)
                    }
                    SimpleTextButton(stringResource(R.string.settings_hardware_keys)) {
                        findNavController().navigate(R.id.action_settingsFragment_to_hardwareKeysFragment)
                    }
                    SimpleTextButton(
                        stringResource(R.string.settings_notifications),
                    ) { findNavController().navigate(R.id.action_settingsFragment_to_notificationsFragment) }
                    SimpleTextButton(stringResource(R.string.settings_haptics)) {
                        findNavController().navigate(R.id.action_settingsFragment_to_hapticsFragment)
                    }
                    SimpleTextButton(stringResource(R.string.settings_hidden_apps)) { showHiddenApps() }
                    SimpleTextButton(stringResource(R.string.settings_default_launcher)) { openDefaultLauncherSettings() }
                }
            }
        }
    }

    private fun openDefaultLauncherSettings() {
        try {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
        }
    }

    private fun disconnectGateway() {
        app.lightai.helper.GatewayClient.shared().disconnect()
    }

    private fun showHiddenApps() {
        viewModel.getHiddenApps()
        findNavController().navigate(
            R.id.appListFragment,
            bundleOf("flag" to AppDrawerFlag.HiddenApps.toString()),
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            requireContext().contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.contains("app.lightai/")
    }

    @androidx.compose.runtime.Composable
    private fun gatewayConnectionLabel(): String {
        val status =
            app.lightai.helper.GatewayClient.shared().status.collectAsState().value
        val err = app.lightai.helper.GatewayClient.shared().lastError.collectAsState().value
        return when (status) {
            app.lightai.helper.GatewayClient.Status.Idle -> stringResource(R.string.gateway_connect_test)
            app.lightai.helper.GatewayClient.Status.Connecting -> stringResource(R.string.gateway_status_connecting)
            app.lightai.helper.GatewayClient.Status.ChallengeReceived -> stringResource(R.string.gateway_status_handshake)
            app.lightai.helper.GatewayClient.Status.Connected -> stringResource(R.string.gateway_status_connected)
            app.lightai.helper.GatewayClient.Status.Disconnected -> stringResource(R.string.gateway_connect_test)
            app.lightai.helper.GatewayClient.Status.Error ->
                stringResource(R.string.gateway_status_error, err.orEmpty())
        }
    }

    @androidx.compose.runtime.Composable
    private fun pairingLabel(): String {
        val secure = app.lightai.data.SecurePrefs.getInstance(requireContext())
        val cfg = secure.gatewayConnectConfig
        return if (cfg != null) {
            stringResource(R.string.settings_gateway_paired, cfg.host)
        } else {
            stringResource(R.string.settings_gateway_unpaired)
        }
    }
}
