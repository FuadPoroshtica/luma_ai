package app.lightai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.lightai.MainViewModel
import app.lightai.R
import app.lightai.data.Constants
import app.lightai.data.Constants.Action
import app.lightai.data.Constants.AppDrawerFlag
import app.lightai.data.HardwareKey
import app.lightai.data.HardwareKeyPress
import app.lightai.data.Prefs
import app.lightai.ui.compose.CustomScrollView
import app.lightai.ui.compose.SettingsComposable.ContentContainer
import app.lightai.ui.compose.SettingsComposable.SettingsHeader
import app.lightai.ui.compose.SettingsComposable.SimpleTextButton

class HardwareKeyActionFragment : Fragment() {
    companion object {
        const val KEY_ORDINAL = "keyOrdinal"
        const val PRESS = "press"
    }

    private lateinit var prefs: Prefs
    private lateinit var hardwareKey: HardwareKey
    private lateinit var press: HardwareKeyPress

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.getInstance(requireContext())
        val ordinal = arguments?.getInt(KEY_ORDINAL, 0) ?: 0
        hardwareKey = HardwareKey.entries[ordinal.coerceIn(0, HardwareKey.entries.size - 1)]
        val pressName = arguments?.getString(PRESS) ?: HardwareKeyPress.Single.name
        press = runCatching { HardwareKeyPress.valueOf(pressName) }.getOrDefault(HardwareKeyPress.Single)
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
        val title = "${stringResource(hardwareKey.displayNameRes)} • ${stringResource(pressLabelRes(press))}"
        Column {
            SettingsHeader(
                title = title,
                onBack = ::goBack,
            )

            ContentContainer {
                CustomScrollView {
                    for (action in Constants.Action.values()) {
                        val isSelected = prefs.getHardwareAction(hardwareKey, press) == action
                        val buttonText =
                            when {
                                action == Constants.Action.OpenApp && isSelected -> {
                                    val appLabel = prefs.getHardwareApp(hardwareKey, press).appLabel
                                    if (appLabel.isNotEmpty()) {
                                        stringResource(R.string.action_open_app_name, appLabel)
                                    } else {
                                        stringResource(R.string.action_open_app)
                                    }
                                }
                                action == Constants.Action.OpenApp -> stringResource(R.string.action_open_app)
                                else -> action.displayName()
                            }
                        SimpleTextButton(
                            title = buttonText,
                            underline = isSelected,
                            onClick = { handleActionSelection(action) },
                        )
                    }
                }
            }
        }
    }

    private fun handleActionSelection(action: Action) {
        if (action == Action.OpenApp) {
            val viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
            viewModel.getAppList()
            findNavController().navigate(
                R.id.appListFragment,
                bundleOf(
                    "flag" to flagFor(press).toString(),
                    "n" to hardwareKey.ordinal,
                ),
            )
        } else {
            prefs.setHardwareAction(hardwareKey, press, action)
            goBack()
        }
    }
}
