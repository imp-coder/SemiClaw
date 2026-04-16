package com.ai.assistance.operit.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.ai.assistance.operit.data.model.FeishuConfig
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.services.FeishuClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * 壁纸管理工具
 *
 * 提供壁纸设置功能，支持从飞书图片设置壁纸，支持AI图片编辑
 */
object WallpaperUtil {

    private const val TAG = "WallpaperUtil"

    // DashScope 图片编辑 API 端点
    private const val DASHSCOPE_IMAGE_EDIT_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
    private const val QWEN_IMAGE_MODEL = "qwen-image-2.0-pro"

    // HTTP 客户端（用于图片编辑 API）
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

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
     * 从飞书图片编辑后设置壁纸
     *
     * @param context 应用上下文
     * @param feishuClient 飞书客户端
     * @param config 飞书配置
     * @param imageKey 飞书图片 key
     * @param messageId 可选的消息 ID，用于获取消息资源
     * @param editPrompt 编辑提示词（例如："去掉图中的汽车"）
     * @param which 壁纸类型：FLAG_SYSTEM（系统壁纸）或 FLAG_LOCK（锁屏壁纸）
     * @return 设置结果
     */
    suspend fun editAndSetWallpaperFromFeishu(
        context: Context,
        feishuClient: FeishuClient,
        config: FeishuConfig,
        imageKey: String,
        messageId: String? = null,
        editPrompt: String,
        which: Int = WallpaperManager.FLAG_SYSTEM
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "开始从飞书下载图片进行编辑，imageKey=$imageKey, editPrompt=$editPrompt")

            // 1. 从飞书下载图片
            val imageData = feishuClient.downloadImage(config, imageKey, messageId)
            if (imageData == null || imageData.isEmpty()) {
                return@withContext Result.failure(Exception("下载飞书图片失败"))
            }

            AppLogger.d(TAG, "图片下载成功，大小: ${imageData.size} bytes")

            // 2. 使用 qwen-image-2.0 编辑图片
            val editResult = editImageWithQwen(imageData, editPrompt, "image/jpeg")
            if (editResult.isFailure) {
                return@withContext Result.failure(editResult.exceptionOrNull() ?: Exception("图片编辑失败"))
            }

            val editedImageData = editResult.getOrThrow()
            AppLogger.d(TAG, "图片编辑成功，编辑后大小: ${editedImageData.size} bytes")

            // 3. 保存编辑后的图片到临时文件
            val editedFile = File(context.cacheDir, "wallpaper_edited_${System.currentTimeMillis()}.png")
            FileOutputStream(editedFile).use { fos ->
                fos.write(editedImageData)
            }

            AppLogger.d(TAG, "编辑后的图片已保存: ${editedFile.absolutePath}")

            // 4. 设置壁纸
            val result = setWallpaperFromFile(context, editedFile, which)

            // 5. 删除临时文件
            editedFile.delete()

            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "编辑并设置壁纸失败", e)
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

    /**
     * 使用 qwen-image-2.0 编辑图片
     *
     * @param imageData 图片字节数据
     * @param editPrompt 编辑提示词（例如："去掉图中右边的人"）
     * @param mimeType 图片 MIME 类型
     * @return 编辑后的图片字节数据
     */
    suspend fun editImageWithQwen(
        imageData: ByteArray,
        editPrompt: String,
        mimeType: String = "image/jpeg"
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "开始使用 qwen-image-2.0 编辑图片，提示词: $editPrompt")

            // 获取 DashScope API Key
            val apiKey = ApiPreferences.DEFAULT_API_KEY
            if (apiKey.isBlank()) {
                return@withContext Result.failure(Exception("DashScope API Key 未配置"))
            }

            // 将图片转换为 Base64
            val base64Image = Base64.encodeToString(imageData, Base64.NO_WRAP)
            val dataUrl = "data:$mimeType;base64,$base64Image"

            // 构建请求 JSON
            val requestJson = JSONObject().apply {
                put("model", QWEN_IMAGE_MODEL)
                put("input", JSONObject().apply {
                    put("messages", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", org.json.JSONArray().apply {
                                // 图片
                                put(JSONObject().apply {
                                    put("image", dataUrl)
                                })
                                // 编辑提示词
                                put(JSONObject().apply {
                                    put("text", editPrompt)
                                })
                            })
                        })
                    })
                })
                put("parameters", JSONObject().apply {
                    put("n", 1)
                    put("watermark", false)
                })
            }

            AppLogger.d(TAG, "图片编辑请求已构建，图片大小: ${imageData.size} bytes")

            // 发送请求
            val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(DASHSCOPE_IMAGE_EDIT_ENDPOINT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                AppLogger.e(TAG, "图片编辑 API 调用失败: ${response.code} - $errorBody")
                return@withContext Result.failure(Exception("图片编辑失败: HTTP ${response.code} - $errorBody"))
            }

            val responseBody = response.body?.string()
            if (responseBody == null) {
                return@withContext Result.failure(Exception("图片编辑响应为空"))
            }

            AppLogger.d(TAG, "图片编辑 API 响应: $responseBody")

            // 解析响应获取编辑后的图片 URL
            val responseJson = JSONObject(responseBody)
            val output = responseJson.optJSONObject("output")
            if (output == null) {
                return@withContext Result.failure(Exception("响应缺少 output 字段"))
            }

            val choices = output.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return@withContext Result.failure(Exception("响应缺少 choices 字段"))
            }

            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.optJSONObject("message")
            if (message == null) {
                return@withContext Result.failure(Exception("响应缺少 message 字段"))
            }

            val content = message.optJSONArray("content")
            if (content == null || content.length() == 0) {
                return@withContext Result.failure(Exception("响应缺少 content 字段"))
            }

            val firstContent = content.getJSONObject(0)
            val editedImageUrl = firstContent.optString("image", null)
            if (editedImageUrl == null) {
                return@withContext Result.failure(Exception("响应缺少编辑后的图片 URL"))
            }

            AppLogger.d(TAG, "编辑后的图片 URL: $editedImageUrl")

            // 下载编辑后的图片
            val downloadRequest = Request.Builder()
                .url(editedImageUrl)
                .build()

            val downloadResponse = httpClient.newCall(downloadRequest).execute()
            if (!downloadResponse.isSuccessful) {
                return@withContext Result.failure(Exception("下载编辑后的图片失败: HTTP ${downloadResponse.code}"))
            }

            val editedImageData = downloadResponse.body?.bytes()
            if (editedImageData == null) {
                return@withContext Result.failure(Exception("编辑后的图片数据为空"))
            }

            AppLogger.d(TAG, "图片编辑成功，编辑后图片大小: ${editedImageData.size} bytes")
            Result.success(editedImageData)

        } catch (e: Exception) {
            AppLogger.e(TAG, "图片编辑失败", e)
            Result.failure(e)
        }
    }

    /**
     * 从本地文件编辑并设置壁纸
     *
     * @param context 应用上下文
     * @param imageFile 图片文件
     * @param editPrompt 编辑提示词
     * @param which 壁纸类型
     * @return 设置结果
     */
    suspend fun editAndSetWallpaperFromFile(
        context: Context,
        imageFile: File,
        editPrompt: String,
        which: Int = WallpaperManager.FLAG_SYSTEM
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 读取图片数据
            val imageData = imageFile.readBytes()
            if (imageData.isEmpty()) {
                return@withContext Result.failure(Exception("图片文件为空"))
            }

            // 根据文件扩展名确定 MIME 类型
            val mimeType = when (imageFile.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                else -> "image/jpeg"
            }

            AppLogger.d(TAG, "读取图片文件: ${imageFile.absolutePath}, MIME: $mimeType, 大小: ${imageData.size} bytes")

            // 编辑图片
            val editResult = editImageWithQwen(imageData, editPrompt, mimeType)
            if (editResult.isFailure) {
                return@withContext Result.failure(editResult.exceptionOrNull() ?: Exception("图片编辑失败"))
            }

            val editedImageData = editResult.getOrThrow()

            // 保存编辑后的图片到临时文件
            val editedFile = File(context.cacheDir, "wallpaper_edited_${System.currentTimeMillis()}.png")
            FileOutputStream(editedFile).use { fos ->
                fos.write(editedImageData)
            }

            AppLogger.d(TAG, "编辑后的图片已保存: ${editedFile.absolutePath}")

            // 设置壁纸
            val result = setWallpaperFromFile(context, editedFile, which)

            // 删除临时文件
            editedFile.delete()

            result

        } catch (e: Exception) {
            AppLogger.e(TAG, "编辑并设置壁纸失败", e)
            Result.failure(e)
        }
    }

    /**
     * 从 URL 编辑并设置壁纸
     *
     * @param context 应用上下文
     * @param imageUrl 图片 URL
     * @param editPrompt 编辑提示词
     * @param which 壁纸类型
     * @return 设置结果
     */
    suspend fun editAndSetWallpaperFromUrl(
        context: Context,
        imageUrl: String,
        editPrompt: String,
        which: Int = WallpaperManager.FLAG_SYSTEM
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "从 URL 下载图片: $imageUrl")

            // 下载图片
            val downloadClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val downloadRequest = Request.Builder()
                .url(imageUrl)
                .build()

            val downloadResponse = downloadClient.newCall(downloadRequest).execute()
            if (!downloadResponse.isSuccessful) {
                return@withContext Result.failure(Exception("下载图片失败: HTTP ${downloadResponse.code}"))
            }

            val imageData = downloadResponse.body?.bytes()
            if (imageData == null || imageData.isEmpty()) {
                return@withContext Result.failure(Exception("图片数据为空"))
            }

            AppLogger.d(TAG, "图片下载成功，大小: ${imageData.size} bytes")

            // 根据 URL 判断 MIME 类型
            val mimeType = when {
                imageUrl.contains(".png", ignoreCase = true) -> "image/png"
                imageUrl.contains(".webp", ignoreCase = true) -> "image/webp"
                imageUrl.contains(".gif", ignoreCase = true) -> "image/gif"
                else -> "image/jpeg"
            }

            // 编辑图片
            val editResult = editImageWithQwen(imageData, editPrompt, mimeType)
            if (editResult.isFailure) {
                return@withContext Result.failure(editResult.exceptionOrNull() ?: Exception("图片编辑失败"))
            }

            val editedImageData = editResult.getOrThrow()

            // 保存编辑后的图片到临时文件
            val editedFile = File(context.cacheDir, "wallpaper_edited_${System.currentTimeMillis()}.png")
            FileOutputStream(editedFile).use { fos ->
                fos.write(editedImageData)
            }

            AppLogger.d(TAG, "编辑后的图片已保存: ${editedFile.absolutePath}")

            // 设置壁纸
            val result = setWallpaperFromFile(context, editedFile, which)

            // 删除临时文件
            editedFile.delete()

            result

        } catch (e: Exception) {
            AppLogger.e(TAG, "从 URL 编辑并设置壁纸失败", e)
            Result.failure(e)
        }
    }
}