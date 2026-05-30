package app.lightai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.Navigation
import app.lightai.data.Constants
import app.lightai.data.Prefs
import app.lightai.databinding.ActivityMainBinding
import app.lightai.helper.HomeCleanupHelper
import app.lightai.helper.hideStatusBar
import app.lightai.helper.showStatusBar
import app.lightai.helper.showToast
import app.lightai.style.DisplayDefaults.withDisplayDefaults

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var navController: NavController
    private lateinit var viewModel: MainViewModel
    private lateinit var binding: ActivityMainBinding

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withDisplayDefaults())
    }

    override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
        super.applyOverrideConfiguration(overrideConfiguration.withDisplayDefaults(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs.getInstance(this)
        val themeMode =
            when (prefs.themeMode) {
                Prefs.ThemeMode.Dark -> AppCompatDelegate.MODE_NIGHT_YES
                Prefs.ThemeMode.Light -> AppCompatDelegate.MODE_NIGHT_NO
                Prefs.ThemeMode.Automatic -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        AppCompatDelegate.setDefaultNightMode(themeMode)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        navController = Navigation.findNavController(this, R.id.nav_host_fragment)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // The nav graph's XML startDestination is kioskFragment. If the user
        // has the kiosk disabled, jump straight to home instead.
        if (!prefs.kioskScreenEnabled && navController.currentDestination?.id == R.id.kioskFragment) {
            try {
                navController.navigate(R.id.mainFragment)
            } catch (_: Exception) {
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateSystemStatusBarVisibility(destination.id)
        }
        updateSystemStatusBarVisibility(navController.currentDestination?.id)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (navController.currentDestination?.id != R.id.mainFragment) {
                        navController.popBackStack()
                    }
                }
            },
        )

        initObservers(viewModel)
        viewModel.getAppList()
        setupOrientation()

        window.addFlags(FLAG_LAYOUT_NO_LIMITS)

        HomeCleanupHelper.setOnAppListCleanupCallback { viewModel.getAppList() }

        handlePinShortcutRequest(intent)
    }

    override fun onDestroy() {
        HomeCleanupHelper.setOnAppListCleanupCallback(null)
        super.onDestroy()
    }

    override fun onStop() {
        if (!isChangingConfigurations) backToHomeScreen()
        super.onStop()
    }

    override fun onUserLeaveHint() {
        backToHomeScreen()
        super.onUserLeaveHint()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePinShortcutRequest(intent)
        detectHomeDoubleTap()
        backToHomeScreen()
    }

    // Fallback for HOME double-tap detection: PhoneWindowManager often intercepts
    // KEYCODE_HOME before any accessibility service sees it, but the system still
    // dispatches the HOME intent to us. Two HOME intents within 250ms = double-tap.
    private var lastHomeIntentAt: Long = 0L

    private fun detectHomeDoubleTap() {
        val now = System.currentTimeMillis()
        val delta = now - lastHomeIntentAt
        lastHomeIntentAt = now
        if (delta in 1..HOME_DOUBLE_TAP_WINDOW_MS) {
            // Treat as double-press HOME — fire the user's configured Double action.
            val prefs = Prefs.getInstance(this)
            val action =
                prefs.getHardwareAction(
                    app.lightai.data.HardwareKey.HOME,
                    app.lightai.data.HardwareKeyPress.Double,
                )
            if (action != Constants.Action.Disabled) {
                lastHomeIntentAt = 0L
                app.lightai.helper.HardwareKeyBus.emit(
                    app.lightai.helper.HardwareKeyEvent(
                        app.lightai.data.HardwareKey.HOME,
                        app.lightai.data.HardwareKeyPress.Double,
                    ),
                )
            }
        }
    }

    companion object {
        private const val HOME_DOUBLE_TAP_WINDOW_MS = 250L
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recreate()
    }

    private fun initObservers(viewModel: MainViewModel) {
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun setupOrientation() {
        if (prefs.autoRotateEnabled) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
            return
        }

        // In Android 8.0, windowIsTranslucent cannot be used with screenOrientation=portrait
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun backToHomeScreen() {
        val target = if (prefs.kioskScreenEnabled) R.id.kioskFragment else R.id.mainFragment
        if (navController.currentDestination?.id != target) {
            navController.popBackStack(target, false)
        }
    }

    private fun handlePinShortcutRequest(intent: Intent?) {
        if (intent == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (intent.action != Constants.REQUEST_CONFIRM_PIN_SHORTCUT) return

        val launcherApps = getSystemService(LauncherApps::class.java) ?: return
        val request =
            try {
                launcherApps.getPinItemRequest(intent)
            } catch (_: Exception) {
                return
            } ?: return

        if (!request.isValid) return
        if (request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) return

        val shortcutInfo = request.shortcutInfo ?: return
        val shortcutPackage = shortcutInfo.`package` ?: return
        val shortcutId = shortcutInfo.id ?: return

        val label =
            shortcutInfo.shortLabel?.toString()
                ?: shortcutInfo.longLabel?.toString()
                ?: "Shortcut"

        val accepted =
            try {
                request.accept()
            } catch (_: Exception) {
                false
            }

        if (!accepted) {
            showToast(this, getString(R.string.toast_unable_to_add_shortcut))
            return
        }

        prefs.addPinnedShortcut(shortcutPackage, shortcutId, label)

        showToast(this, getString(R.string.toast_added_to_app_drawer))
    }

    private fun updateSystemStatusBarVisibility(destinationId: Int?) {
        val shouldShowSystemStatusBar =
            prefs.statusBarMode == Prefs.StatusBarMode.AndroidStatusBar &&
                destinationId == R.id.mainFragment

        if (shouldShowSystemStatusBar) {
            showStatusBar(this)
        } else {
            hideStatusBar(this)
        }
    }
}
