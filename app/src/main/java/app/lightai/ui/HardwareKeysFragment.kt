package app.lightai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import app.lightai.R
import app.lightai.data.Constants
import app.lightai.data.HardwareKey
import app.lightai.data.HardwareKeyPress
import app.lightai.data.Prefs
import app.lightai.ui.compose.CustomScrollView
import app.lightai.ui.compose.SettingsComposable.ContentContainer
import app.lightai.ui.compose.SettingsComposable.SelectorButton
import app.lightai.ui.compose.SettingsComposable.SettingsHeader

class HardwareKeysFragment : Fragment() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.getInstance(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = composeView(onSwipeBack = ::goBack) { HardwareKeysScreen() }

    @Composable
    fun HardwareKeysScreen() {
        Column {
            SettingsHeader(
                title = stringResource(R.string.settings_hardware_keys),
                onBack = ::goBack,
            )

            ContentContainer {
                CustomScrollView(verticalArrangement = Arrangement.spacedBy(33.5.dp)) {
                    for (key in HardwareKey.entries) {
                        HardwareKeyButton(key)
                    }
                }
            }
        }
    }

    @Composable
    private fun HardwareKeyButton(key: HardwareKey) {
        val label = stringResource(key.displayNameRes)
        val singleAction = prefs.getHardwareAction(key, HardwareKeyPress.Single)
        val value =
            when (singleAction) {
                Constants.Action.OpenApp -> {
                    val appLabel = prefs.getHardwareApp(key, HardwareKeyPress.Single).appLabel
                    if (appLabel.isNotEmpty()) {
                        stringResource(R.string.action_open_app_name, appLabel)
                    } else {
                        stringResource(R.string.action_open_app)
                    }
                }

                Constants.Action.Disabled -> {
                    stringResource(R.string.action_disabled)
                }

                else -> {
                    singleAction.displayName()
                }
            }
        SelectorButton(
            label = label,
            value = value,
            onClick = {
                findNavController().navigate(
                    R.id.hardwareKeyDetailFragment,
                    bundleOf(HardwareKeyDetailFragment.KEY_ORDINAL to key.ordinal),
                )
            },
        )
    }
}
