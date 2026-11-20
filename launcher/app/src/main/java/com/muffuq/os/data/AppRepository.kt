package com.muffuq.os.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.text.Collator

data class AppEntry(
    val component: ComponentName,
    val label: String,
    val isLeanback: Boolean,
    val isSystem: Boolean,
) {
    val packageName: String get() = component.packageName
}

class AppRepository(context: Context) {
    private val context = context.applicationContext
    private val pm = context.packageManager

    /**
     * Every launchable app except ourselves, sorted by label. TV (leanback) activities win over phone ones.
     * Phone-only apps are listed only when the user installed them (sideloads); the phone-only entries that
     * ship with the firmware are vendor plumbing (clock, file picker, disclaimer screens) nobody wants on a TV.
     */
    fun loadApps(): List<AppEntry> {
        val byPackage = LinkedHashMap<String, AppEntry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (info in query(Intent(Intent.ACTION_MAIN).addCategory(category))) {
                val activity = info.activityInfo
                val pkg = activity.packageName
                if (pkg == context.packageName || pkg in byPackage) continue
                val isLeanback = category == Intent.CATEGORY_LEANBACK_LAUNCHER
                val isSystem = activity.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                if (!isLeanback && isSystem) continue
                byPackage[pkg] = AppEntry(
                    component = ComponentName(pkg, activity.name),
                    label = info.loadLabel(pm).toString().trim(),
                    isLeanback = isLeanback,
                    isSystem = isSystem,
                )
            }
        }
        val collator = Collator.getInstance(context.resources.configuration.locales[0])
        return byPackage.values.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /**
     * Emits whenever the app list may be stale: the affected package name for install/uninstall/enable/disable,
     * or null when the system language changes (every label is stale then).
     */
    fun changes(): Flow<String?> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(intent.data?.schemeSpecificPart)
            }
        }
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, packages, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_LOCALE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        awaitClose { context.unregisterReceiver(receiver) }
    }

    private fun query(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
}
