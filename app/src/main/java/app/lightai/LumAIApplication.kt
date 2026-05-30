package app.lightai

import android.app.Application
import android.content.pm.LauncherApps
import android.os.UserHandle
import app.lightai.helper.HomeCleanupHelper

class LightAIApplication : Application() {
    private val launcherAppsCallback =
        object : LauncherApps.Callback() {
            override fun onPackageRemoved(
                packageName: String,
                user: UserHandle,
            ) {
                HomeCleanupHelper.cleanupRemovedPackage(this@LightAIApplication, packageName, user)
            }

            override fun onPackageAdded(
                packageName: String,
                user: UserHandle,
            ) {}

            override fun onPackageChanged(
                packageName: String,
                user: UserHandle,
            ) {}

            override fun onPackagesAvailable(
                packageNames: Array<String>,
                user: UserHandle,
                replacing: Boolean,
            ) {}

            override fun onPackagesUnavailable(
                packageNames: Array<String>,
                user: UserHandle,
                replacing: Boolean,
            ) {}
        }

    override fun onCreate() {
        super.onCreate()
        getSystemService(LauncherApps::class.java).registerCallback(launcherAppsCallback)
    }
}
