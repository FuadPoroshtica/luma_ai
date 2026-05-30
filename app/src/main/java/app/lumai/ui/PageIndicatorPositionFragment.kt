package app.lumai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import app.lumai.R
import app.lumai.data.Prefs
import app.lumai.ui.compose.CustomScrollView
import app.lumai.ui.compose.SettingsComposable.ContentContainer
import app.lumai.ui.compose.SettingsComposable.SettingsHeader
import app.lumai.ui.compose.SettingsComposable.SimpleTextButton

class PageIndicatorPositionFragment : Fragment() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.getInstance(requireContext())
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
                title = stringResource(R.string.pages_page_indicator_position),
                onBack = ::goBack,
            )
            ContentContainer {
                CustomScrollView {
                    SimpleTextButton(
                        title = stringResource(R.string.position_left),
                        underline = prefs.pageIndicatorPosition == Prefs.PageIndicatorPosition.Left,
                        onClick = { selectPosition(Prefs.PageIndicatorPosition.Left) },
                    )
                    SimpleTextButton(
                        title = stringResource(R.string.position_right),
                        underline = prefs.pageIndicatorPosition == Prefs.PageIndicatorPosition.Right,
                        onClick = { selectPosition(Prefs.PageIndicatorPosition.Right) },
                    )
                    SimpleTextButton(
                        title = stringResource(R.string.position_hidden),
                        underline = prefs.pageIndicatorPosition == Prefs.PageIndicatorPosition.Hidden,
                        onClick = { selectPosition(Prefs.PageIndicatorPosition.Hidden) },
                    )
                }
            }
        }
    }

    private fun selectPosition(position: Prefs.PageIndicatorPosition) {
        prefs.pageIndicatorPosition = position
        goBack()
    }
}
