package com.kieronquinn.app.pcs.repositories

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import com.kieronquinn.app.pcs.repositories.DeviceConfigPropertiesRepository.DeviceConfigEntry
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File

/**
 *  Uses libsu to get/override/clear DeviceConfig and SystemProperties entries. Due to the backend
 *  using `Settings.Config`, it's not possible to use libsu's RootService (which would be much
 *  faster), so we use batched calls to `device_config` instead to read/write config, and to write
 *  properties. Reading properties is not restricted and uses the regular hidden method.
 */
interface DeviceConfigPropertiesRepository {

    companion object {
        const val DEBUG_PROPERTY_NAME = "persist.pcs.debug"
        const val PSI_ENABLE_APPS_PROPERTY_NAME = "persist.psi.enable_apps"
        const val PSI_FORCE_ACCOUNT_PRESENCE_PROPERTY_NAME = "persist.psi.force_account_presence"
        const val PSI_FORCE_ACCOUNT_TYPE_PROPERTY_NAME = "persist.psi.force_account_type"
        const val PSI_FORCE_ADMIN_ALLOWANCE_PROPERTY_NAME = "persist.psi.force_admin_allowance"
        const val PSI_CLIENT_GROUP_OVERRIDE_PROPERTY_NAME = "persist.psi.client_group_override"
        const val AS_SHOW_NOW_PLAYING_NOTIFICATION = "persist.as.show_now_playing_notification"
        const val AS_FORCE_GSA = "persist.as.force_gsa"
        const val PHONE_ENABLED = "persist.phone.pcs_enabled"
        const val TTS_ENABLED = "persist.tts.pcs_enabled"
        const val AGENT_ENABLED = "persist.agent.pcs_enabled"
    }

    /**
     *  Returns whether root access is available
     */
    suspend fun isAvailable(): Boolean

    /**
     *  Gets device config entries for a given namespace. Since the command output dumps everything
     *  as strings, only string values are returned.
     */
    suspend fun getConfig(namespace: String): List<DeviceConfigEntry>

    /**
     *  Overrides a list of device config entries. This is batched into a single Shell input for
     *  optimisation.
     */
    suspend fun overrideConfig(entries: List<DeviceConfigEntry>)

    /**
     *  Clears all device config entries. The [DeviceConfigEntry.value] field is not used here.
     *  This is batched into a single Shell input for optimisation.
     */
    suspend fun clearConfigOverrides(entries: List<DeviceConfigEntry>)

    /**
     *  Sets a system property as root to a given value
     */
    suspend fun setProperty(name: String, value: String)

    /**
     *  Force stop a package using root
     */
    suspend fun forceStopPackage(packageName: String)

    /**
     *  Deletes MDD-related shared prefs for a package to force a redownload of manifests
     */
    suspend fun clearMdd(packageName: String)

    data class DeviceConfigEntry(
        val namespace: String,
        val flag: String,
        val value: String? = null
    ) {

        fun getBytes(): ByteArray {
            return Base64.decode(value, Base64.NO_WRAP)
        }

        fun getLong(): Long? {
            return value?.toLongOrNull()
        }

    }

}

class DeviceConfigPropertiesRepositoryImpl(context: Context) : DeviceConfigPropertiesRepository {

    companion object {
        private const val LIST = "device_config list"
        private const val OVERRIDE = "device_config override"
        private const val CLEAR = "device_config clear_override"
        private const val SPLIT = "="
    }

    private var _shell: Shell? = null
    private val packageManager = context.packageManager

    private fun runShizukuCommands(commands: List<String>, outLines: ArrayList<String>? = null): Boolean {
        if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false

                return try {
                    val joinedCommands = commands.joinToString("\n")
                    val process = Shizuku.newProcess(arrayOf("sh", "-c", joinedCommands), null, null)

                    outLines?.let { list ->
                        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                list.add(line!!)
                            }
                        }
                    }

                    process.waitFor()
                    process.exitValue() == 0
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
    }

    private val shell
        get() = _shell ?: run {
            Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).build().also {
                _shell = it
            }
        }

    override suspend fun isAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            val out = ArrayList<String>()
            val success = runShizukuCommands(listOf("whoami"), out)
            success && out.firstOrNull()?.trim() == "shell"
        }
    }

    override suspend fun getConfig(namespace: String): List<DeviceConfigEntry> {
        return withContext(Dispatchers.IO) {
            val out = ArrayList<String>()
            runShizukuCommands(listOf("$LIST $namespace"), out)
            out.parseNamespaceOutput(namespace)
        }
    }

    override suspend fun overrideConfig(entries: List<DeviceConfigEntry>) {
        if (entries.isEmpty()) return
            withContext(Dispatchers.IO) {
                val commands = entries.map { "$OVERRIDE ${it.namespace} ${it.flag} ${it.value}" }
                runShizukuCommands(commands)
            }
    }

    override suspend fun clearConfigOverrides(entries: List<DeviceConfigEntry>) {
        if (entries.isEmpty()) return
            withContext(Dispatchers.IO) {
                val commands = entries.map { "$CLEAR ${it.namespace} ${it.flag}" }
                runShizukuCommands(commands)
            }
    }

    override suspend fun setProperty(name: String, value: String) {
        withContext(Dispatchers.IO) {
            runShizukuCommands(listOf("setprop $name $value"))
        }
    }

    override suspend fun forceStopPackage(packageName: String) {
        withContext(Dispatchers.IO) {
            runShizukuCommands(listOf("am force-stop $packageName"))
        }
    }

    override suspend fun clearMdd(packageName: String) {
        withContext(Dispatchers.IO) {
            val sharedPrefsDir = try {
                val dataDir = packageManager.getApplicationInfo(packageName, 0).dataDir
                File(dataDir, "shared_prefs")
            } catch (e: PackageManager.NameNotFoundException) {
                return@withContext
            }
            val commands = listOf(
                "rm ${sharedPrefsDir.absolutePath}/gms_icing_mdd_*.xml",
                "am force-stop $packageName"
            )
            runShizukuCommands(commands)
        }
    }

    private fun ArrayList<String>.parseNamespaceOutput(namespace: String): List<DeviceConfigEntry> {
        return mapNotNull {
            if (!it.contains(SPLIT)) return@mapNotNull null
                it.split(SPLIT).let { pair ->
                    val flag = pair[0]
                    val value = pair.drop(1).joinToString(SPLIT)
                    DeviceConfigEntry(namespace, flag, value)
                }
        }
    }
}
