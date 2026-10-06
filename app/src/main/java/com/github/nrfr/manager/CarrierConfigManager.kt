package com.github.nrfr.manager

import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyFrameworkInitializer
import android.telephony.TelephonyManager
import com.android.internal.telephony.ICarrierConfigLoader
import com.github.nrfr.model.SimCardInfo
import rikka.shizuku.ShizukuBinderWrapper

object CarrierConfigManager {
    fun getSimCards(context: Context): List<SimCardInfo> {
        val simCards = mutableListOf<SimCardInfo>()

        // 1. 優先嘗試讀取系統活躍的 SubscriptionInfo
        val activeSubscriptions = try {
            val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            subscriptionManager?.activeSubscriptionInfoList
                ?.filter { it.subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
                ?.sortedBy { it.simSlotIndex }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        // 用於 dumpsys carrier_config 延遲解析快取，避免重複呼叫 shell 導致卡頓
        var dumpsysConfigsCache: Map<Int, Map<String, String>>? = null
        fun getDumpsysConfigForSlot(slot: Int): Map<String, String>? {
            if (dumpsysConfigsCache == null) {
                dumpsysConfigsCache = PrivilegedCarrierConfigRunner.getOverrideConfigsFromDumpsys()
            }
            return dumpsysConfigsCache?.get(slot)
        }

        if (activeSubscriptions.isNotEmpty()) {
            activeSubscriptions.forEach { info ->
                val slot = info.simSlotIndex + 1
                val subId = info.subscriptionId
                val carrierName = info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                    ?: getCarrierNameBySubId(context, subId)
                val config = getCurrentConfig(subId) { getDumpsysConfigForSlot(info.simSlotIndex) }
                simCards.add(SimCardInfo(slot, subId, carrierName, config))
            }
            return simCards
        }

        // 2. 回退：針對 Slot 0 與 Slot 1 解析 subId（含 dumpsys isub 回退）
        val subId0 = getSubIdForSlot(0)
        val subId1 = getSubIdForSlot(1)

        if (subId0 != null) {
            val config0 = getCurrentConfig(subId0) { getDumpsysConfigForSlot(0) }
            simCards.add(SimCardInfo(1, subId0, getCarrierNameBySubId(context, subId0), config0))
        }
        if (subId1 != null) {
            val config1 = getCurrentConfig(subId1) { getDumpsysConfigForSlot(1) }
            simCards.add(SimCardInfo(2, subId1, getCarrierNameBySubId(context, subId1), config1))
        }

        return simCards
    }

    /**
     * 解析指定卡槽的 subId。若系統 API 回傳 -1 (INVALID_SUBSCRIPTION_ID)，
     * 則透過 Shizuku dumpsys isub 進行回退解析。
     */
    private fun getSubIdForSlot(slotIndex: Int): Int? {
        val directSubId = runCatching {
            SubscriptionManager.getSubId(slotIndex)
                ?.firstOrNull { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        }.getOrNull()

        return directSubId ?: PrivilegedCarrierConfigRunner.getSubIdForSlot(slotIndex)
    }

    private inline fun getCurrentConfig(
        subId: Int,
        dumpsysFallback: () -> Map<String, String>?
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // 1. 優先透過系統 ICarrierConfigLoader 讀取（原生 API 極快）
        try {
            val carrierConfigLoader = ICarrierConfigLoader.Stub.asInterface(
                ShizukuBinderWrapper(
                    TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .carrierConfigServiceRegisterer
                        .get()
                )
            )
            val config = carrierConfigLoader.getConfigForSubId(subId, "com.github.nrfr")
            if (config != null) {
                config.getString(CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { result["国家码"] = it }

                if (config.getBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, false)) {
                    config.getString(CarrierConfigManager.KEY_CARRIER_NAME_STRING)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { result["运营商名称"] = it }
                }
            }
        } catch (_: Exception) {
        }

        // 2. 若 API 未讀取到覆蓋項目，回退讀取 dumpsys carrier_config 中的 mOverrideConfigs
        if (result.isEmpty()) {
            val dumpsysConfig = dumpsysFallback()
            if (!dumpsysConfig.isNullOrEmpty()) {
                return dumpsysConfig
            }
        }

        return result
    }

    private fun getCarrierNameBySubId(context: Context, subId: Int): String {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return ""

        return try {
            telephonyManager.createForSubscriptionId(subId).networkOperatorName
                .takeIf { it.isNotBlank() }
                ?: telephonyManager.networkOperatorName
        } catch (_: Exception) {
            telephonyManager.networkOperatorName
        }
    }

    fun setCarrierConfig(
        context: Context,
        subId: Int,
        countryCode: String?,
        carrierName: String? = null
    ): Boolean {
        val bundle = PersistableBundle()

        // 设置国家码
        if (!countryCode.isNullOrEmpty() && countryCode.length == 2) {
            bundle.putString(
                CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING,
                countryCode.lowercase()
            )
        }

        // 设置运营商名称
        if (!carrierName.isNullOrEmpty()) {
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, true)
            bundle.putString(CarrierConfigManager.KEY_CARRIER_NAME_STRING, carrierName)
        }

        return overrideCarrierConfig(context, subId, bundle)
    }

    fun resetCarrierConfig(context: Context, subId: Int): Boolean {
        return overrideCarrierConfig(context, subId, null)
    }

    private fun overrideCarrierConfig(
        context: Context,
        subId: Int,
        bundle: PersistableBundle?
    ): Boolean {
        val carrierConfigLoader = ICarrierConfigLoader.Stub.asInterface(
            ShizukuBinderWrapper(
                TelephonyFrameworkInitializer
                    .getTelephonyServiceManager()
                    .carrierConfigServiceRegisterer
                    .get()
            )
        )
        return try {
            // Android 16 (API 36) 的廠商 ROM 上僅接受臨時覆蓋 (persistent = false)
            val persistent = Build.VERSION.SDK_INT < 36
            carrierConfigLoader.overrideConfig(subId, bundle, persistent)
            false
        } catch (e: SecurityException) {
            // 當系統拒絕 Shell 直接呼叫時（如 realme UI 7.0 / ColorOS / HyperOS 等），
            // 降級透過特權 Instrumentation 委派 Shell 權限寫入
            if (e.message?.contains("cannot be invoked by shell") == true || Build.VERSION.SDK_INT >= 35) {
                PrivilegedCarrierConfigRunner.overrideConfig(context, subId, bundle)
                true
            } else {
                throw e
            }
        }
    }
}
