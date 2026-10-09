package com.pravahax.portalx.net

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat

/** v0.7.1: the installed app's version, read from the package manager so library modules need no app BuildConfig. */
object AppInfo {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"
    fun versionCode(context: Context): Long =
        runCatching { PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)) }.getOrDefault(0L)
}
