package com.ai.assistance.operit.core.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import android.util.Xml

/**
 * 集成到主应用的无障碍服务
 * 直接提供UI自动化功能，无需通过AIDL与外部APK通信
 */
class OperitAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "OperitAccessibilitySvc"

        @Volatile
        var isServiceConnected = false
            private set

        @Volatile
        var currentActivityName: String = ""
            internal set

        /**
         * 获取服务实例（用于直接调用）
         */
        @Volatile
        var instance: OperitAccessibilityService? = null
            private set

        /**
         * 检查服务是否可用
         */
        fun isAvailable(): Boolean = isServiceConnected && instance != null
    }

    private val screenshotLock = Any()
    private var lastScreenshotTimestamp: Long = 0L
    private val minScreenshotIntervalMs: Long = 1100L

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected = true
        instance = this
        Log.d(TAG, "无障碍服务已连接，状态更新为 true")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceConnected = false
        instance = null
        currentActivityName = ""
        Log.d(TAG, "无障碍服务已解绑，状态更新为 false")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 监听窗口状态变化以检测Activity变化
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString()
            if (!className.isNullOrEmpty()) {
                currentActivityName = className
                Log.d(TAG, "Activity changed to: $className")
            }
        }
    }

    override fun onInterrupt() {
        isServiceConnected = false
        instance = null
        currentActivityName = ""
        Log.d(TAG, "无障碍服务已中断，状态更新为 false")
    }

    /**
     * 获取UI层次结构XML
     */
    fun getUiHierarchy(): String {
        return captureUiHierarchyAsXml()
    }

    /**
     * 执行点击
     */
    fun performClick(x: Int, y: Int): Boolean {
        Log.d(TAG, "准备在 ($x, $y) 执行点击...")

        val clickPath = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat(), y.toFloat())
        }

        val clickStroke = GestureDescription.StrokeDescription(clickPath, 0L, 50L)
        val gestureDescription = GestureDescription.Builder()
            .addStroke(clickStroke)
            .build()

        return dispatchGesture(gestureDescription, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.i(TAG, "手势已成功完成。")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "手势被取消。")
            }
        }, null)
    }

    /**
     * 执行长按
     */
    fun performLongPress(x: Int, y: Int): Boolean {
        Log.d(TAG, "准备在 ($x, $y) 执行长按...")

        val longPressPath = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat(), y.toFloat())
        }

        val longPressStroke = GestureDescription.StrokeDescription(longPressPath, 0L, 600L)
        val gestureDescription = GestureDescription.Builder()
            .addStroke(longPressStroke)
            .build()

        return dispatchGesture(gestureDescription, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.i(TAG, "长按手势已成功完成。")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "长按手势被取消。")
            }
        }, null)
    }

    /**
     * 执行滑动
     */
    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * 执行全局操作
     */
    fun executeGlobalAction(actionId: Int): Boolean {
        return super.performGlobalAction(actionId)
    }

    /**
     * 查找焦点节点ID
     */
    fun findFocusedNodeId(): String? {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

        if (focusedNode != null) {
            val rect = Rect()
            focusedNode.getBoundsInScreen(rect)
            focusedNode.recycle()
            return rect.toShortString()
        }
        return null
    }

    /**
     * 在节点上设置文本
     */
    fun setTextOnNode(nodeId: String, text: String): Boolean {
        Log.d(TAG, "准备为节点 $nodeId 设置文本: '$text'")

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.w(TAG, "setTextOnNode 失败: rootInActiveWindow is null")
            return false
        }

        val containerNode = findNodeByBounds(rootNode, nodeId)
        rootNode.recycle()

        if (containerNode == null) {
            Log.w(TAG, "setTextOnNode 失败: 无法通过ID '$nodeId' 找到目标容器节点")
            return false
        }

        var targetNode: AccessibilityNodeInfo? = null
        try {
            targetNode = findFirstEditableNode(containerNode)

            if (targetNode == null) {
                Log.w(TAG, "setTextOnNode 失败: 在节点 $nodeId 及其子节点中未找到可编辑的节点。")
                return false
            }

            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val result = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            if (!result) {
                val bounds = Rect()
                targetNode.getBoundsInScreen(bounds)
                Log.w(TAG, "setTextOnNode: performAction(ACTION_SET_TEXT) 在目标节点上返回 false. 节点信息: class=${targetNode.className}, text='${targetNode.text}', bounds=${bounds.toShortString()}")
            }
            return result
        } finally {
            containerNode.recycle()
            targetNode?.recycle()
        }
    }

    /**
     * 截取屏幕截图
     */
    fun takeScreenshot(path: String, format: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "takeScreenshot: API level < R (30), not supported")
            return false
        }

        var resultValue = false
        val normalizedFormat = format.lowercase()

        synchronized(screenshotLock) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastScreenshotTimestamp
            if (elapsed in 0 until minScreenshotIntervalMs) {
                try {
                    Thread.sleep(minScreenshotIntervalMs - elapsed)
                } catch (_: InterruptedException) {
                }
            }
            lastScreenshotTimestamp = System.currentTimeMillis()

            val latch = CountDownLatch(1)

            this.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        hardwareBuffer.close()

                        if (bitmap != null) {
                            try {
                                val file = File(path)
                                val parent = file.parentFile
                                if (parent != null && !parent.exists()) {
                                    parent.mkdirs()
                                }

                                val compressFormat = when (normalizedFormat) {
                                    "png" -> Bitmap.CompressFormat.PNG
                                    "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
                                    else -> Bitmap.CompressFormat.PNG
                                }

                                file.outputStream().use { output ->
                                    val quality = if (compressFormat == Bitmap.CompressFormat.JPEG) 90 else 100
                                    resultValue = bitmap.compress(compressFormat, quality, output)
                                }

                                Log.d(TAG, "截图保存结果: $resultValue, 文件: $path")
                            } catch (e: Exception) {
                                Log.e(TAG, "保存截图失败", e)
                                resultValue = false
                            } finally {
                                bitmap.recycle()
                            }
                        } else {
                            Log.w(TAG, "Bitmap.wrapHardwareBuffer returned null")
                            resultValue = false
                        }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "takeScreenshot onFailure: errorCode=$errorCode")
                        resultValue = false
                        latch.countDown()
                    }
                }
            )

            try {
                latch.await()
            } catch (_: InterruptedException) {
                resultValue = false
            }
        }

        return resultValue
    }

    /**
     * 检查服务是否启用
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        return isServiceConnected
    }

    /**
     * 获取当前Activity名称
     */
    fun getCurrentActivityName(): String {
        return currentActivityName
    }

    private fun findNodeByBounds(root: AccessibilityNodeInfo, boundsString: String): AccessibilityNodeInfo? {
        val rect = Rect()
        root.getBoundsInScreen(rect)
        if (rect.toShortString() == boundsString) {
            return AccessibilityNodeInfo.obtain(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                val found = findNodeByBounds(child, boundsString)
                child.recycle()
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val editableNode = findFirstEditableNode(child)
                child.recycle()
                if (editableNode != null) {
                    return editableNode
                }
            }
        }
        return null
    }

    private fun captureUiHierarchyAsXml(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val serializer = Xml.newSerializer()
        val writer = StringWriter()
        try {
            serializer.setOutput(writer)
            serializer.startDocument("UTF-8", true)
            serializeNodeToXml(rootNode, serializer)
            serializer.endDocument()
            return writer.toString()
        } catch (e: Exception) {
            Log.e(TAG, "生成UI XML时出错", e)
            return ""
        } finally {
            // serializeNodeToXml 已经递归回收了包括 rootNode 在内的所有节点
        }
    }

    private fun serializeNodeToXml(node: AccessibilityNodeInfo?, serializer: XmlSerializer) {
        if (node == null) return

        serializer.startTag(null, "node")

        // 添加属性
        serializer.attribute(null, "class", node.className?.toString() ?: "")
        serializer.attribute(null, "package", node.packageName?.toString() ?: "")
        serializer.attribute(null, "content-desc", node.contentDescription?.toString() ?: "")
        serializer.attribute(null, "text", node.text?.toString() ?: "")
        serializer.attribute(null, "resource-id", node.viewIdResourceName ?: "")

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        serializer.attribute(null, "bounds", bounds.toShortString() ?: "")
        serializer.attribute(null, "clickable", node.isClickable.toString())
        serializer.attribute(null, "focused", node.isFocused.toString())

        for (i in 0 until node.childCount) {
            serializeNodeToXml(node.getChild(i), serializer)
        }

        serializer.endTag(null, "node")
        node.recycle()
    }
}