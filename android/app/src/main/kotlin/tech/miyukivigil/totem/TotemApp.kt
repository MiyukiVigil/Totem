package tech.miyukivigil.totem

import android.app.Application
import android.content.pm.ApplicationInfo
import tech.miyukivigil.totem.core.BrandIcons
import tech.miyukivigil.totem.device.ActivityStore
import tech.miyukivigil.totem.device.DeviceStore
import tech.miyukivigil.totem.push.Notifications
import tech.miyukivigil.totem.vault.Vault
import java.io.File

class TotemApp : Application() {
    val vault: Vault by lazy { Vault(filesDir) }
    val devices: DeviceStore by lazy { DeviceStore(filesDir) }
    val activity: ActivityStore by lazy { ActivityStore(filesDir) }
    /** Bundled brand logos (a few MB of JSON: first use takes a moment, so not on the main thread). */
    val brandIcons: BrandIcons by lazy { BrandIcons(assets.open("brand-icons.json").use { it.readBytes().decodeToString() }) }

    val isDebugBuild: Boolean get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }

    /**
     * Debug-build switches for testing over adb, created with
     * `adb shell run-as tech.miyukivigil.totem touch files/<name>`. Release builds ignore them.
     * (Anyone who can run `run-as` on a debug build can already read the app's files.)
     */
    fun debugFlag(name: String): Boolean = isDebugBuild && File(filesDir, name).exists()
}
