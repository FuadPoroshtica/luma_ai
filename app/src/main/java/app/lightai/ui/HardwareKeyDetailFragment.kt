package app.lightai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.lightai.MainViewModel
import app.lightai.R
import app.lightai.data.Constants
import app.lightai.data.Constants.AppDrawerFlag
import app.lightai.data.HardwareKey
import app.lightai.data.HardwareKeyPress
import app.lightai.data.Prefs
import app.lightai.ui.compose.CustomScrollView
import app.lightai.ui.compose.SettingsComposable.ContentContainer
import app.lightai.ui.compose.SettingsComposable.MessageText
import app.lightai.ui.compose.SettingsComposable.SelectorButton
import app.lightai.ui.compose.SettingsComposable.SettingsHeader

class HardwareKeyDetailFragment : Fragment() {
    companion object {
        const val KEY_ORDINAL = "keyOrdinal"
    }

    private lateinit var prefs: Prefs
    private lateinit var hardwareKey: HardwareKey

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.getInstance(requireContext())
        val ordinal = arguments?.getInt(KEY_ORDINAL, 0) ?: 0
        val safeOrdinal = ordinal.coerceIn(0, HardwareKey.entries.size - 1)
        hardwareKey = HardwareKey.entries[safeOrdinal]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = composeView(onSwipeBack = ::goBack) { Screen() }

    private fun flagFor(press: HardwareKeyPress): AppDrawerFlag =
        when (press) {
            HardwareKeyPress.Single -> AppDrawerFlag.SetHardwareSingle
            HardwareKeyPress.Double -> AppDrawerFlag.SetHardwareDouble
            HardwareKeyPress.Long -> AppDrawerFlag.SetHardwareLong
        }

    private fun pressLabelRes(press: HardwareKeyPress): Int =
        when (press) {
            HardwareKeyPress.Single -> R.string.hwkey_single_press
            HardwareKeyPress.Double -> R.string.hwkey_double_press
            HardwareKeyPress.Long -> R.string.hwkey_long_press
        }

    @Composable
    fun Screen() {
        Column {
            SettingsHeader(
                title = stringResource(hardwareKey.displayNameRes),
                onBack = ::goBack,
            )

            ContentContainer {
                CustomScrollView(verticalArrangement = Arrangement.spacedBy(33.5.dp)) {
                    PressRow(HardwareKeyPress.Single)
                    PressRow(HardwareKeyPress.Double)
                    PressRow(HardwareKeyPress.Long)
                }
            }
        }
    }

    @Composable
    private fun PressRow(press: HardwareKeyPress) {
        val label = stringResource(pressLabelRes(press))
        val disabled = press == HardwareKeyPress.Long && !hardwareKey.supportsLong

        if (disabled) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SelectorButton(
                    label = label,
                    value = stringResource(R.string.action_disabled),
                    onClick = {},
                )
                MessageText(
                    text = stringResource(R.string.hwkey_home_long_unavailable),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                )
            }
            return
        }

        val action = prefs.getHardwareAction(hardwareKey, press)
        val value =
            when (action) {
                Constants.Action.OpenApp -> {
                    val appLabel = prefs.getHardwareApp(hardwareKey, press).appLabel
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
                    action.displayName()
                }
            }

        SelectorButton(
            label = label,
            value = value,
            onClick = {
                findNavController().navigate(
                    R.id.hardwareKeyActionFragment,
                    bundleOf(
                        HardwareKeyActionFragment.KEY_ORDINAL to hardwareKey.ordinal,
                        HardwareKeyActionFragment.PRESS to press.name,
                    ),
                )
            },
        )
    }
}
