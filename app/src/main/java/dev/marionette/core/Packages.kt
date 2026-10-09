package dev.marionette.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

fun Context.isPackageInstalled(pkg: String): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(pkg, 0)
    }
}.isSuccess
