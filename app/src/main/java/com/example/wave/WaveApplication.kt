package com.example.wave

import android.app.Application
import com.example.wave.di.AppContainer

/** Holds the AppContainer so fake state (scenario, demo toggles) survives rotation. */
class WaveApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
