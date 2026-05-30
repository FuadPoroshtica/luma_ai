package app.lightai.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import app.lightai.R
import app.lightai.databinding.FragmentKioskBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lock-screen-style first surface. Big clock + date + a prominent "Ask Claw"
 * prompt. Tap the background to reveal the home page (apps).
 *
 * Not an actual Android lockscreen — Android won't let us replace SystemUI
 * without root / Device Owner. This is the first thing the user sees after
 * unlocking, giving an LP3-like minimal feel.
 */
class KioskFragment : Fragment() {
    private var _binding: FragmentKioskBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentKioskBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        // "Tap to open" hint → reveal home (only explicit trigger to avoid
        // catching taps meant for Ask Claw)
        binding.kioskHint.setOnClickListener {
            navigateHome()
        }
        // Ask AI tile tap → open prompt overlay
        binding.kioskAskButton.setOnClickListener {
            try {
                val args = Bundle().apply { putString("mode", "text") }
                findNavController().navigate(R.id.aiPromptOverlayFragment, args)
            } catch (_: Exception) {
            }
        }

        // Live-update clock + date while visible
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val now = Date()
                    binding.kioskTime.text = TIME_FMT.format(now)
                    binding.kioskDate.text = DATE_FMT.format(now)
                    delay(TICK_MS)
                }
            }
        }
    }

    private fun navigateHome() {
        try {
            findNavController().navigate(R.id.mainFragment)
        } catch (_: Exception) {
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class SwipeUpListener(
        context: Context,
        private val onSwipeUp: () -> Unit,
    ) : View.OnTouchListener {
        private val detector =
            GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onFling(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        velocityX: Float,
                        velocityY: Float,
                    ): Boolean {
                        if (e1 == null) return false
                        val dy = e2.y - e1.y
                        if (dy < -SWIPE_THRESHOLD_PX && -velocityY > SWIPE_VELOCITY_PX_S) {
                            onSwipeUp()
                            return true
                        }
                        return false
                    }
                },
            )

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(
            v: View,
            event: MotionEvent,
        ): Boolean = detector.onTouchEvent(event)
    }

    companion object {
        private const val TICK_MS = 30_000L
        private const val SWIPE_THRESHOLD_PX = 100f
        private const val SWIPE_VELOCITY_PX_S = 100f
        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val DATE_FMT = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
    }
}
