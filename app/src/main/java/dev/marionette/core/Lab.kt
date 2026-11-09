package dev.marionette.core

import dev.marionette.BuildConfig

object Lab {
    val ON: Boolean = BuildConfig.DEBUG || BuildConfig.LAB
}
