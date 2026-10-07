package com.github.nrfr.manager

import android.Manifest
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PersistableBundle
import android.system.Os
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.io.InputStream
import java.lang.Process as JavaProcess

private val LOGICAL_SIM_SLOT_PATTERN = Regex("""^\s*Logical SIM slot (\d+): subId=(-?\d+)""")
private val PHONE_ID_PATTERN = Regex("""^Phone Id = (\d+)""")

private const val ARG_CALLER_PID = "caller_pid"
private const val ARG_SUB_ID = "sub_id"
private const val ARG_CONFIG = "config"
private const val ARG_RESET = "reset"

// ActivityManager 特權 Instrumentation 標記
private const val INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS = 1
private const val INSTR_FLAG_NO_RESTART = 4

object PrivilegedCarrierConfigRunner {
    init {
        HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.addHiddenApiExemptions("I")
    }

    /**
     * 當系統的 getSubId 回傳無效 ID (-1) 時，透過 dumpsys isub 解析指定槽位的 active subId
     */
    fun getSubIdForSlot(slotIndex: Int): Int? {
        val output = runCatching {
            runShellCommand(arrayOf("dumpsys", "isub"))
        }.getOrNull() ?: return null

        return output.lineSequence().mapNotNull { line ->
            val match = LOGICAL_SIM_SLOT_PATTERN.find(line) ?: return@mapNotNull null
            val slot = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val subId = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            if (slot == slotIndex && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                subId
            } else {
                null
            }
        }.firstOrNull()
    }

    /**
     * 高版本系統 (如 Android 16) 透過 API 可能無法直接讀到已生效的 override config，
     * 透過 dumpsys carrier_config 解析 mOverrideConfigs
     */
    fun getOverrideConfigsFromDumpsys(): Map<Int, Map<String, String>> {
        val output = runCatching {
            runShellCommand(arrayOf("dumpsys", "carrier_config"))
        }.getOrNull() ?: return emptyMap()

        val result = mutableMapOf<Int, Map<String, String>>()
        var currentPhoneId: Int? = null
        var readingOverrideConfig = false
        val rawValues = mutableMapOf<String, String>()

        fun flushCurrentConfig() {
            val phoneId = currentPhoneId ?: return
            val config = mutableMapOf<String, String>()
            rawValues["sim_country_iso_override_string"]?.takeIf { it.isNotBlank() }?.let {
                config["国家码"] = it
            }
            if (rawValues["carrier_name_override_bool"] == "true") {
                rawValues["carrier_name_string"]?.takeIf { it.isNotBlank() }?.let {
                    config["运营商名称"] = it
                }
            }
            if (config.isNotEmpty()) {
                result[phoneId] = config
            }
            rawValues.clear()
        }

        output.lineSequence().forEach { line ->
            PHONE_ID_PATTERN.find(line)?.let { match ->
                flushCurrentConfig()
                currentPhoneId = match.groupValues[1].toIntOrNull()
                readingOverrideConfig = false
                return@forEach
            }

            val trimmed = line.trim()
            if (trimmed.startsWith("mOverrideConfigs")) {
                readingOverrideConfig = !trimmed.endsWith("null")
                if (!readingOverrideConfig) {
                    rawValues.clear()
                }
                return@forEach
            }

            if (!readingOverrideConfig) {
                return@forEach
            }

            if (trimmed.startsWith("m") && trimmed.endsWith(":")) {
                readingOverrideConfig = false
                return@forEach
            }

            val separatorIndex = trimmed.indexOf(" = ")
            if (separatorIndex > 0) {
                rawValues[trimmed.substring(0, separatorIndex)] = trimmed.substring(separatorIndex + 3)
            }
        }

        flushCurrentConfig()
        return result
    }

    /**
     * 透過 Shizuku 以特權 Instrumentation 方式委派 Shell 權限寫入配置，避免直接呼叫被拒
     */
    fun overrideConfig(context: Context, subId: Int, bundle: PersistableBundle?) {
        val args = Bundle().apply {
            putInt(ARG_CALLER_PID, android.os.Process.myPid())
            putInt(ARG_SUB_ID, subId)
            putBoolean(ARG_RESET, bundle == null)
            if (bundle != null) {
                putParcelable(ARG_CONFIG, bundle)
            }
        }

        val activityManager = getIActivityManager()
            ?: throw IllegalStateException("無法取得 ActivityManager 服務")
        val component = ComponentName(context, PrivilegedCarrierConfigInstrumentation::class.java)
        val flags = INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS or INSTR_FLAG_NO_RESTART

        val uiConnClass = Class.forName("android.app.UiAutomationConnection")
        val uiConn = uiConnClass.getConstructor().newInstance()

        val startInstrMethod = activityManager.javaClass.methods.first { it.name == "startInstrumentation" }
        startInstrMethod.invoke(
            activityManager,
            component,
            null,
            flags,
            args,
            null,
            uiConn,
            0,
            null
        )
    }

    internal fun getIActivityManager(): Any? {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val activity = getServiceMethod.invoke(null, Context.ACTIVITY_SERVICE) as? IBinder
                ?: return null
            val stubClass = Class.forName("android.app.IActivityManager\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            asInterfaceMethod.invoke(null, ShizukuBinderWrapper(activity))
        } catch (_: Throwable) {
            null
        }
    }

    private fun runShellCommand(command: Array<String>): String {
        val process = newShizukuProcess(command)
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = readAsync(process.inputStream, stdout)
        val stderrThread = readAsync(process.errorStream, stderr)
        val exitCode = process.waitFor()
        stdoutThread.join()
        stderrThread.join()

        if (exitCode != 0) {
            throw IllegalStateException(stderr.toString().ifBlank { "${command.joinToString(" ")} failed: $exitCode" })
        }
        return stdout.toString()
    }

    private fun newShizukuProcess(command: Array<String>): JavaProcess {
        val newProcess = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        newProcess.isAccessible = true
        return newProcess.invoke(null, command, null, null) as JavaProcess
    }

    private fun readAsync(inputStream: InputStream, output: StringBuilder): Thread {
        return Thread {
            inputStream.bufferedReader().use { reader ->
                output.append(reader.readText())
            }
        }.apply { start() }
    }
}

class PrivilegedCarrierConfigInstrumentation : Instrumentation() {
    init {
        HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.addHiddenApiExemptions("I")
    }

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)

        if (arguments.getInt(ARG_CALLER_PID, 0) != android.os.Process.myPid()) {
            finish(0, Bundle())
            return
        }

        val activityManager = PrivilegedCarrierConfigRunner.getIActivityManager()

        val subId = arguments.getInt(ARG_SUB_ID)
        val bundle = getPersistableBundle(arguments)

        var delegatedByIActivityManager = false
        try {
            // 優先嘗試透過 activityManager 委派 Shell 權限
            if (activityManager != null) {
                try {
                    val delegateMethod = activityManager.javaClass.methods.firstOrNull {
                        it.name == "startDelegateShellPermissionIdentity"
                    }
                    if (delegateMethod != null) {
                        delegateMethod.invoke(activityManager, Os.getuid(), null)
                        delegatedByIActivityManager = true
                    }
                } catch (_: Throwable) {
                }
            }

            if (!delegatedByIActivityManager) {
                // 回退：嘗試 UiAutomation adoptShellPermissionIdentity
                try {
                    uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MODIFY_PHONE_STATE)
                } catch (_: Throwable) {
                }
            }

            // Android 16 (API 36) 的廠商 ROM 往往只接受臨時覆蓋 (persistent = false)
            val persistent = Build.VERSION.SDK_INT < 36
            overrideCarrierConfig(subId, bundle, persistent)
        } catch (e: Throwable) {
            // 持久化被拒絕時，降級重試非持久覆蓋
            try {
                overrideCarrierConfig(subId, bundle, persistent = false)
            } catch (_: Throwable) {
            }
        } finally {
            if (delegatedByIActivityManager && activityManager != null) {
                try {
                    val stopDelegateMethod = activityManager.javaClass.methods.firstOrNull {
                        it.name == "stopDelegateShellPermissionIdentity"
                    }
                    stopDelegateMethod?.invoke(activityManager)
                } catch (_: Throwable) {
                }
            } else {
                runCatching { uiAutomation.dropShellPermissionIdentity() }
            }
            finish(0, Bundle())
        }
    }

    private fun overrideCarrierConfig(
        subId: Int,
        bundle: PersistableBundle?,
        persistent: Boolean
    ) {
        val manager = targetContext.getSystemService(CarrierConfigManager::class.java)
        val method = manager.javaClass.getMethod(
            "overrideConfig",
            Int::class.javaPrimitiveType,
            PersistableBundle::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.invoke(manager, subId, bundle, persistent)
    }

    private fun getPersistableBundle(arguments: Bundle): PersistableBundle? {
        if (arguments.getBoolean(ARG_RESET)) {
            return null
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments.getParcelable(ARG_CONFIG, PersistableBundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments.getParcelable<PersistableBundle>(ARG_CONFIG)
        }
    }
}
