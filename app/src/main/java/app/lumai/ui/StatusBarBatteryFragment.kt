package app.lumai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import app.lumai.R
import app.lumai.data.Constants
import app.lumai.data.Prefs
import app.lumai.data.StatusBarSectionType
import app.lumai.ui.compose.SettingsComposable.ContentContainer
import app.lumai.ui.compose.SettingsComposable.PrefsToggleTextButton
import app.lumai.ui.compose.SettingsComposable.SelectorButton
import app.lumai.ui.compose.SettingsComposable.SettingsHeader
import app.lumai.ui.compose.SettingsItemSpacing

class StatusBarBatteryFragment : Fragment() {
    private lateinit var prefs: Prefs
    private val actionState = mutableStateOf(Constants.Action.Disabled)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.getInstance(requireContext())
    }

    override fun onResume() {
        super.onResume()
        actionState.value = prefs.getSectionAction(StatusBarSectionType.BATTERY)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = composeView(onSwipeBack = ::goBack) { Screen() }

    @Composable
    fun Screen() {
        Column {
            SettingsHeader(
                title = stringResource(R.string.status_bar_battery),
                onBack = ::goBack,
            )

            ContentContainer {
                Column(verticalArrangement = Arrangement.spacedBy(SettingsItemSpacing)) {
                    PrefsToggleTextButton(
                        title = stringResource(R.string.status_bar_battery_percentage),
                        initialValue = prefs.batteryPercentage,
                        onValueChange = { prefs.batteryPercentage = it },
                    )
                    PrefsToggleTextButton(
                        title = stringResource(R.string.status_bar_battery_icon),
                        initialValue = prefs.batteryIcon,
                        onValueChange = { prefs.batteryIcon = it },
                    )
                    SelectorButton(
                        label = stringResource(R.string.status_bar_on_press),
                        value = actionDisplayValue(actionState.value, prefs, StatusBarSectionType.BATTERY),
                        onClick = {
                            findNavController().navigate(
                                R.id.action_statusBarBatteryFragment_to_gestureActionFragment,
                                bundleOf(GestureActionFragment.SECTION_TYPE to StatusBarSectionType.BATTERY.name),
                            )
                        },
                    )
                }
            }
        }
    }
}
