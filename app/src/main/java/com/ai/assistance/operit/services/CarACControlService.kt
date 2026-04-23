package com.ai.assistance.operit.services

import android.content.Context
import com.ai.assistance.operit.core.services.OperitAccessibilityService
import com.ai.assistance.operit.core.tools.ToolProgressNotifier
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*

/**
 * 车载空调控制服务
 *
 * 通过飞书指令控制车载空调：
 * - 打开空调界面（使用AccessibilityService点击底部温度区域）
 * - 设置空调温度（使用car_service命令，无需root）
 * - 切换温度显示单位（摄氏/华氏）
 */
class CarACControlService private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CarACControl"

        // 底部导航栏温度区域位置比例（基于屏幕尺寸计算，兼容不同分辨率）
        // 左侧温度数字区域中心 X ≈ screenWidth * 0.252 (基于647/2560)
        // Y坐标 ≈ screenHeight - 25 (底部栏中心偏上)
        private const val BOTTOM_TEMP_CENTER_RATIO_X = 0.252f     // 温度数字中心X比例
        private const val BOTTOM_BAR_CENTER_OFFSET = 25           // 底部栏中心偏移(px)
        private const val BOTTOM_BAR_HEIGHT = 48                  // 底部导航栏高度(px)

        // 动态查找按钮的 resource-id（优先使用）
        // 注意：完整ID格式为 com.android.systemui:id/xxx
        private const val AC_MASTER_SWITCH_ID = "ac_master_switch"     // 空调主开关
        private const val AC_BUTTON_ID = "ac_button"                    // AC制冷按钮
        private const val HVAC_INCREASE_ID = "hvac_increase_button"     // 升温按钮
        private const val HVAC_DECREASE_ID = "hvac_decrease_button"     // 降温按钮
        private const val HVAC_PANEL_ID = "hvac_panel"                  // 空调面板容器

        // 车辆属性ID
        private const val HVAC_TEMP_PROPERTY_ID = "0x15600503"          // 空调温度设置
        private const val HVAC_TEMP_DISPLAY_UNITS_ID = "0x1140050e"   // 温度显示单位

        // 温度单位值
        private const val TEMP_UNIT_CELSIUS = 48      // 0x30 摄氏度
        private const val TEMP_UNIT_FAHRENHEIT = 49   // 0x31 华氏度

        // 温度范围（摄氏度）
        private const val MIN_TEMP_C = 16.0
        private const val MAX_TEMP_C = 32.0

        @Volatile
        private var INSTANCE: CarACControlService? = null

        fun getInstance(context: Context): CarACControlService {
            return INSTANCE ?: synchronized(this) {
                val instance = CarACControlService(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        /**
         * 华氏温度转摄氏温度
         * 公式: C = (F - 32) * 5/9
         */
        fun fahrenheitToCelsius(fahrenheit: Double): Double {
            return (fahrenheit - 32) * 5 / 9
        }

        /**
         * 摄氏温度转华氏温度
         * 公式: F = C * 9/5 + 32
         */
        fun celsiusToFahrenheit(celsius: Double): Double {
            return celsius * 9 / 5 + 32
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 关闭空调面板（点击面板外区域或底部温度区域）
     */
    suspend fun closeACPanel(): Boolean {
        AppLogger.d(TAG, "正在关闭空调面板...")
        ToolProgressNotifier.notifyInProgress(context, "car_ac", "🔄 正在关闭空调面板...")

        return try {
            val accessibilityService = OperitAccessibilityService.instance
            if (accessibilityService == null) {
                AppLogger.e(TAG, "AccessibilityService不可用")
                ToolProgressNotifier.notifyError(context, "car_ac", "❌ 无障碍服务未连接")
                return false
            }

            // 直接点击屏幕顶部（面板外区域）关闭Overlay
            // 注意：不检测面板状态，因为无法检测SystemUI节点
            // 点击面板外区域是安全的：面板打开时会关闭，面板关闭时无副作用
            AppLogger.d(TAG, "点击屏幕顶部区域关闭HvacPanel（无论面板当前状态）")
            accessibilityService.performClickAndWait(1280, 50, 500)

            delay(300)  // 等待Overlay关闭动画

            AppLogger.d(TAG, "空调面板关闭操作已完成")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "关闭空调面板异常", e)
            false
        }
    }

    /**
     * 打开空调面板（HvacPanel Overlay）
     * 流程：先关闭面板（确保状态一致） -> 点击底部温度区域打开面板
     */
    suspend fun openACPanel(): Boolean {
        AppLogger.d(TAG, "正在打开空调面板...")
        ToolProgressNotifier.notifyInProgress(context, "car_ac", "🔄 正在打开空调面板...")

        return try {
            val accessibilityService = OperitAccessibilityService.instance
            if (accessibilityService == null) {
                AppLogger.e(TAG, "AccessibilityService不可用")
                ToolProgressNotifier.notifyError(context, "car_ac", "❌ 无障碍服务未连接")
                return false
            }

            // 先关闭面板（确保状态一致，无论面板当前是否打开）
            closeACPanel()
            delay(300)  // 等待关闭动画完成

            // 动态计算底部温度数字中心坐标
            val metrics = context.resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeightWithNavBar = metrics.heightPixels + BOTTOM_BAR_HEIGHT

            val tempCenterX = (screenWidth * BOTTOM_TEMP_CENTER_RATIO_X).toInt()
            val tempCenterY = screenHeightWithNavBar - BOTTOM_BAR_CENTER_OFFSET

            AppLogger.d(TAG, "点击底部温度数字打开HvacPanel: ($tempCenterX, $tempCenterY)")
            accessibilityService.performClickAndWait(tempCenterX, tempCenterY, 1000)

            delay(500)  // 等待Overlay动画
            AppLogger.d(TAG, "空调面板已打开")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "打开空调面板异常", e)
            ToolProgressNotifier.notifyError(context, "car_ac", "❌ 打开空调面板异常: ${e.message}")
            false
        }
    }

    /**
     * 确保温度显示单位为摄氏度
     * 在设置温度前调用，确保界面显示和设置值一致
     */
    suspend fun ensureCelsiusUnit(): Boolean {
        AppLogger.d(TAG, "确保温度显示单位为摄氏度...")

        return try {
            val command = "cmd car_service set-property-value $HVAC_TEMP_DISPLAY_UNITS_ID 0 $TEMP_UNIT_CELSIUS"
            val result = executeShellCommand(command)

            if (result.isSuccess) {
                AppLogger.d(TAG, "温度单位已设置为摄氏度")
                delay(200)  // 等待单位切换生效
                true
            } else {
                AppLogger.e(TAG, "设置摄氏度单位失败: ${result.errorMessage}")
                false
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "设置摄氏度单位异常", e)
            false
        }
    }

    /**
     * 设置空调温度（摄氏度）
     *
     * @param tempC 摄氏温度 (16-32)
     * @param zone 区域编号，默认为1（驾驶员区域）
     */
    suspend fun setTemperature(tempC: Double, zone: Int = 1): Boolean {
        // 校验温度范围
        val clampedTemp = tempC.coerceIn(MIN_TEMP_C, MAX_TEMP_C)

        if (clampedTemp != tempC) {
            AppLogger.w(TAG, "温度 $tempC 超出范围，已调整为 $clampedTemp")
            ToolProgressNotifier.notifyInProgress(
                context, "car_ac",
                "⚠️ 温度已调整为有效范围: ${clampedTemp}°C"
            )
        }

        AppLogger.d(TAG, "设置温度: ${clampedTemp}°C")
        ToolProgressNotifier.notifyInProgress(
            context, "car_ac",
            "🔄 正在设置温度: ${clampedTemp}°C"
        )

        return try {
            // 使用 car_service 设置温度属性
            val command = "cmd car_service set-property-value $HVAC_TEMP_PROPERTY_ID $zone $clampedTemp"
            val result = executeShellCommand(command)

            if (result.isSuccess) {
                AppLogger.d(TAG, "温度设置成功")
                ToolProgressNotifier.notifySuccess(
                    context, "car_ac",
                    "✅ 温度已设置为 ${clampedTemp}°C"
                )
                true
            } else {
                AppLogger.e(TAG, "设置温度失败: ${result.errorMessage}")
                ToolProgressNotifier.notifyError(context, "car_ac", "❌ 设置温度失败")
                false
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "设置温度异常", e)
            ToolProgressNotifier.notifyError(context, "car_ac", "❌ 设置温度异常: ${e.message}")
            false
        }
    }

    /**
     * 打开空调并设置温度（完整流程）
     * 自动切换到摄氏度显示单位
     *
     * @param tempC 目标摄氏温度
     */
    suspend fun openACAndSetTemp(tempC: Double): Boolean {
        ToolProgressNotifier.notifyStart(context, "car_ac", "🚗 正在控制车载空调...")

        // 1. 先确保温度单位为摄氏度
        ToolProgressNotifier.notifyInProgress(context, "car_ac", "🔄 确保温度单位为摄氏度...")
        val unitSet = ensureCelsiusUnit()
        if (!unitSet) {
            AppLogger.w(TAG, "设置摄氏度单位失败，继续尝试设置温度")
        }

        // 2. 打开空调面板
        val panelOpened = openACPanel()
        if (!panelOpened) {
            return false
        }

        // 3. 点击空调主开关确保空调已开启
        val acEnabled = turnOnAC()
        if (!acEnabled) {
            AppLogger.w(TAG, "空调开关操作失败，继续尝试设置温度")
        }

        // 4. 设置温度
        val tempSet = setTemperature(tempC)

        return tempSet
    }

    /**
     * 在空调面板内点击空调主开关，确保空调已开启
     */
    suspend fun turnOnAC(): Boolean {
        AppLogger.d(TAG, "正在开启空调...")
        ToolProgressNotifier.notifyInProgress(context, "car_ac", "🔄 正在开启空调...")

        return try {
            val accessibilityService = OperitAccessibilityService.instance
            if (accessibilityService == null) {
                AppLogger.e(TAG, "AccessibilityService不可用")
                return false
            }

            // 在面板内查找并点击空调主开关
            AppLogger.d(TAG, "点击空调主开关: $AC_MASTER_SWITCH_ID")
            val clickSuccess = accessibilityService.findAndClickByResourceId(AC_MASTER_SWITCH_ID, 1000)

            if (clickSuccess) {
                delay(500)  // 等待空调状态更新
                AppLogger.d(TAG, "空调已开启")
                true
            } else {
                AppLogger.w(TAG, "未能找到空调主开关按钮")
                false
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "开启空调异常", e)
            false
        }
    }

    /**
     * 仅打开空调界面
     */
    suspend fun justOpenAC(): Boolean {
        ToolProgressNotifier.notifyStart(context, "car_ac", "🚗 正在打开空调...")
        val opened = openACPanel()
        if (opened) {
            ToolProgressNotifier.notifySuccess(context, "car_ac", "✅ 空调界面已打开")
        }
        return opened
    }

    /**
     * 设置温度显示单位
     *
     * @param useCelsius true=摄氏度, false=华氏度
     */
    suspend fun setTemperatureUnit(useCelsius: Boolean): Boolean {
        val unitValue = if (useCelsius) TEMP_UNIT_CELSIUS else TEMP_UNIT_FAHRENHEIT
        val unitName = if (useCelsius) "摄氏度(°C)" else "华氏度(°F)"

        AppLogger.d(TAG, "设置温度显示单位: $unitName")
        ToolProgressNotifier.notifyInProgress(context, "car_ac", "🔄 正在设置温度单位为$unitName...")

        return try {
            val command = "cmd car_service set-property-value $HVAC_TEMP_DISPLAY_UNITS_ID 0 $unitValue"
            val result = executeShellCommand(command)

            if (result.isSuccess) {
                AppLogger.d(TAG, "温度单位设置成功")
                ToolProgressNotifier.notifySuccess(context, "car_ac", "✅ 温度显示单位已设置为$unitName")
                true
            } else {
                AppLogger.e(TAG, "设置温度单位失败: ${result.errorMessage}")
                ToolProgressNotifier.notifyError(context, "car_ac", "❌ 设置温度单位失败")
                false
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "设置温度单位异常", e)
            ToolProgressNotifier.notifyError(context, "car_ac", "❌ 设置温度单位异常: ${e.message}")
            false
        }
    }

    /**
     * 获取当前温度显示单位
     */
    suspend fun getTemperatureUnit(): Boolean? {
        return try {
            val command = "cmd car_service get-property-value $HVAC_TEMP_DISPLAY_UNITS_ID"
            val result = executeShellCommand(command)

            if (result.isSuccess) {
                // 解析输出，查找 CELSIUS 或 FAHRENHEIT
                val isCelsius = result.output.contains("CELSIUS")
                AppLogger.d(TAG, "当前温度单位: ${if (isCelsius) "摄氏度" else "华氏度"}")
                isCelsius
            } else {
                AppLogger.e(TAG, "获取温度单位失败: ${result.errorMessage}")
                null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取温度单位异常", e)
            null
        }
    }

    /**
     * 执行 shell 命令（不需要root权限，car_service命令普通用户可执行）
     */
    private fun executeShellCommand(command: String): ShellResult {
        return try {
            // 直接执行命令，不需要su（car_service命令可被普通应用执行）
            val process = Runtime.getRuntime().exec(command)
            val exitCode = process.waitFor()

            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()

            AppLogger.d(TAG, "执行命令: $command, exitCode=$exitCode, output=$output, error=$error")

            ShellResult(
                isSuccess = exitCode == 0,
                output = output,
                errorMessage = error
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "执行命令异常: $command", e)
            ShellResult(
                isSuccess = false,
                output = "",
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }

    data class ShellResult(
        val isSuccess: Boolean,
        val output: String,
        val errorMessage: String
    )
}