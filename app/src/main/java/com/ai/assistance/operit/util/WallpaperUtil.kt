package com.ai.assistance.operit.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import com.ai.assistance.operit.data.model.FeishuConfig
import com.ai.assistance.operit.services.FeishuClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 壁纸管理工具
 *
 * 提供壁纸设置功能，支持从飞书图片设置壁纸
 */
object WallpaperUtil {

    private const val TAG = "WallpaperUtil"

    /**
     * 从飞书图片设置壁纸
     *
     * @param context 应用上下文
     * @param feishuClient 飞书客户端
     * @param config 飞书配置
     * @param imageKey 飞书图片 key
     * @param messageId 可选的消息 ID，用于获取消息资源
     * @param which 壁纸类型：FLAG_SYSTEM（系统壁纸）或 FLAG_LOCK（锁屏壁纸）
     * @return 设置结果
     */
    suspend fun setWallpaperFromFeishu(
        context: Context,
        feishuClient: FeishuClient,
        config: FeishuConfig,
        imageKey: String,
        messageId: String? = null,
        which: Int = WallpaperManager.FLAG_SYSTEM
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "开始从飞书下载图片设置壁纸，imageKey=$imageKey, messageId=$messageId")

            // 1. 从飞书下载图片
            val imageData = feishuClient.downloadImage(config, imageKey, messageId)
            if (imageData == null || imageData.isEmpty()) {
                return@withContext Result.failure(Exception("下载飞书图片失败"))
            }

            AppLogger.d(TAG, "图片下载成功，大小: ${imageData.size} bytes")

            // 2. 保存到临时文件
            val tempFile = File(context.cacheDir, "wallpaper_temp_${System.currentTimeMillis()}.jpg")
            FileOutputStream(tempFile).use { fos ->
                fos.write(imageData)
            }

            AppLogger.d(TAG, "图片已保存到临时文件: ${tempFile.absolutePath}")

            // 3. 设置壁纸
            val result = setWallpaperFromFile(context, tempFile, which)

            // 4. 删除临时文件
            tempFile.delete()

            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "设置壁纸失败", e)
            Result.failure(e)
        }
    }

    /**
     * 从 URL 下载图片并设置壁纸
     *
     * @param context 应用上下文
     * @param imageUrl 图片 URL
     * @param which 壁纸类型
     * @return 设置结果
     */
    suspend fun setWallpaperFromUrl(
        context: Context,
        imageUrl: String,
        which: Int = WallpaperManager.FLAG_SYSTEM
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "开始从 URL 下载图片设置壁纸: $imageUrl")

            // 下载图片
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url(imageUrl)
                .build()

            val imageData = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("下载图片失败: ${response.code}"))
                }
                response.body?.bytes() ?: return@withContext Result.failure(Exception("图片数据为空"))
            }

            AppLogger.d(TAG, "图片下载成功，大小: ${imageData.size} bytes")

            // 保存到临时文件
            val tempFile = File(context.cacheDir, "wallpaper_temp_${System.currentTimeMillis()}.jpg")
            FileOutputStream(tempFile).use { fos ->
                fos.write(imageData)
            }

            // 设置壁纸
            val result = setWallpaperFromFile(context, tempFile, which)

            // 删除临时文件
            tempFile.delete()

            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "设置壁纸失败", e)
            Result.failure(e)
        }
    }

    /**
     * 从本地文件设置壁纸
     *
     * @param context 应用上下文
     * @param imageFile 图片文件
     * @param which 壁纸类型：FLAG_SYSTEM（系统壁纸）或 FLAG_LOCK（锁屏壁纸）
     * @return 设置结果
     */
    fun setWallpaperFromFile(
        context: Context,
        imageFile: File,
        which: Int = WallpaperManager.FLAG_SYSTEM
    ): Result<String> {
        return try {
            val wallpaperManager = WallpaperManager.getInstance(context)

            // 解码图片
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            if (bitmap == null) {
                return Result.failure(Exception("无法解码图片文件"))
            }

            AppLogger.d(TAG, "图片解码成功，尺寸: ${bitmap.width}x${bitmap.height}")

            // 设置壁纸
            // 注意：FLAG_LOCK 需要 Android N (API 24) 以上
            if (which == WallpaperManager.FLAG_LOCK && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                AppLogger.d(TAG, "锁屏壁纸设置成功")
            } else {
                wallpaperManager.setBitmap(bitmap)
                AppLogger.d(TAG, "系统壁纸设置成功")
            }

            // 回收 bitmap
            bitmap.recycle()

            Result.success("壁纸设置成功")
        } catch (e: Exception) {
            AppLogger.e(TAG, "设置壁纸失败", e)
            Result.failure(e)
        }
    }

    /**
     * 从字节数据设置壁纸
     *
     * @param context 应用上下文
     * @param imageData 图片字节数据
     * @param which 壁纸类型
     * @return 设置结果
     */
    suspend fun setWallpaperFromBytes(
        context: Context,
        imageData: ByteArray,
        which: Int = WallpaperManager.FLAG_SYSTEM
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 保存到临时文件
            val tempFile = File(context.cacheDir, "wallpaper_temp_${System.currentTimeMillis()}.jpg")
            FileOutputStream(tempFile).use { fos ->
                fos.write(imageData)
            }

            // 设置壁纸
            val result = setWallpaperFromFile(context, tempFile, which)

            // 删除临时文件
            tempFile.delete()

            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "设置壁纸失败", e)
            Result.failure(e)
        }
    }
}