package com.macdougallg.cozmoplay

import android.app.Application
import com.macdougallg.cozmoplay.camera.CozmoCameraProcessor
import com.macdougallg.cozmoplay.camera.CameraViewModel
import com.macdougallg.cozmoplay.camera.ICozmoCamera
import com.macdougallg.cozmoplay.protocol.CozmoProtocolEngine
import com.macdougallg.cozmoplay.protocol.ICozmoProtocol
import com.macdougallg.cozmoplay.ui.screens.animations.AnimationsViewModel
import com.macdougallg.cozmoplay.ui.screens.connect.ConnectViewModel
import com.macdougallg.cozmoplay.ui.screens.cubes.CubesViewModel
import com.macdougallg.cozmoplay.ui.screens.drive.DriveViewModel
import com.macdougallg.cozmoplay.ui.screens.explore.ExploreViewModel
import com.macdougallg.cozmoplay.ui.screens.home.HomeViewModel
import com.macdougallg.cozmoplay.ui.screens.onboarding.OnboardingViewModel
import com.macdougallg.cozmoplay.ui.screens.settings.SettingsViewModel
import com.macdougallg.cozmoplay.wifi.CozmoWifiManager
import com.macdougallg.cozmoplay.wifi.ICozmoWifi
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

class CozmoPlayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@CozmoPlayApplication)
            modules(appModule)
        }
    }
}

val appModule = module {
    // Singletons
    single<ICozmoWifi>     { CozmoWifiManager(androidContext()) }
    single<ICozmoProtocol> { CozmoProtocolEngine() }
    single<ICozmoCamera>   { CozmoCameraProcessor(get()) }

    // ViewModels
    viewModel { ConnectViewModel(get()) }
    viewModel { CameraViewModel(get()) }
    viewModel { HomeViewModel(get(), get()) }
    viewModel { DriveViewModel(get()) }
    viewModel { AnimationsViewModel(get()) }
    viewModel { ExploreViewModel(get()) }
    viewModel { CubesViewModel(get()) }
    viewModel { OnboardingViewModel(androidContext()) }
    viewModel { SettingsViewModel(androidContext()) }
}
