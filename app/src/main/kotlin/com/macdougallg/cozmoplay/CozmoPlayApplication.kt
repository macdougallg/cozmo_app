package com.macdougallg.cozmoplay

import android.app.Application
import com.macdougallg.cozmoplay.wifi.CozmoWifiManager
import com.macdougallg.cozmoplay.wifi.ICozmoWifi
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * Application class. Initialises Koin dependency injection.
 *
 * All modules are wired here against interfaces, never concrete classes,
 * per the API Contract (section 7).
 *
 * Production bindings are declared here; test bindings override via
 * KoinTestExtension in test classes.
 */
class CozmoPlayApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@CozmoPlayApplication)
            modules(appModule)
        }
    }

    override fun onTerminate() {
        // Clean up WiFi network callbacks on process death
        // In practice onTerminate() is rarely called — use ViewModel.onCleared() for runtime cleanup
        super.onTerminate()
    }
}

/**
 * Production Koin module.
 *
 * When cozmo-protocol and cozmo-camera implementations are ready,
 * add their bindings here following the same pattern:
 *   single<ICozmoProtocol> { CozmoProtocolEngine() }
 *   single<ICozmoCamera> { CozmoCameraProcessor(get()) }
 */
val appModule = module {
    // WiFi manager — singleton; one network connection per app lifecycle
    single<ICozmoWifi> { CozmoWifiManager(androidContext()) }

    // TODO Agent 1: Uncomment when cozmo-protocol is implemented
    // single<ICozmoProtocol> { CozmoProtocolEngine() }

    // TODO Agent 4: Uncomment when cozmo-camera is implemented
    // single<ICozmoCamera> { CozmoCameraProcessor(get()) }
}
