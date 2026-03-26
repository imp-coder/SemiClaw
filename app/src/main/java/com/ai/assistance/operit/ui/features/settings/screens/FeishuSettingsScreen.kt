package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.data.model.FeishuConfig
import com.ai.assistance.operit.data.preferences.FeishuPreferences
import com.ai.assistance.operit.services.FeishuServiceManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeishuSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val feishuPreferences = remember { FeishuPreferences.getInstance(context) }
    val scope = rememberCoroutineScope()

    val enabled by feishuPreferences.feishuEnabledFlow.collectAsState(initial = false)
    var enabledSwitch by remember { mutableStateOf(false) }
    var hasChanges by remember { mutableStateOf(false) }
    var showSaveSuccess by remember { mutableStateOf(false) }

    // 初始化值
    LaunchedEffect(enabled) {
        enabledSwitch = enabled
    }

    // 检测变化
    LaunchedEffect(enabledSwitch) {
        hasChanges = enabledSwitch != enabled
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        // 顶部导航栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "飞书集成设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        // 启用开关
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "启用飞书集成",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "开启后将通过 WebSocket 接收飞书消息并自动回复",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabledSwitch,
                    onCheckedChange = { enabledSwitch = it }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 凭证状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "凭证状态",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "已使用内置应用凭证，无需手动配置 App ID 和 App Secret。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "App ID: ${FeishuConfig.DEFAULT_APP_ID.take(12)}***",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 使用说明
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "使用说明",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = """
1. 打开飞书集成开关
2. 点击"保存设置"按钮（服务会立即启动）
3. 确保悬浮窗已开启（用于处理消息和生成AI回复）
4. 在飞书中给机器人发送消息
5. 机器人会自动回复 AI 生成的内容

注意：
• 私聊需要先主动给机器人发过消息，机器人才能回复
• 群聊需要将机器人加入群聊
• 如果没有收到回复，请检查悬浮窗是否开启
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 飞书开放平台配置说明
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "飞书开放平台配置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = """
在飞书开放平台 (open.feishu.cn) 配置：

1. 事件订阅方式：WebSocket 长连接
2. 添加事件：im.message.receive_v1
3. 应用权限配置：
   - im:message:send_as_bot (发送消息)
   - im:message:readonly (读取消息)
   - im:chat:readonly (获取聊天列表)
4. 发布应用版本并申请上线
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 保存按钮
        Button(
            onClick = {
                scope.launch {
                    feishuPreferences.saveFeishuEnabled(enabledSwitch)
                    hasChanges = false
                    showSaveSuccess = true

                    // 立即启动/停止飞书服务
                    val feishuService = FeishuServiceManager.getInstance(context)
                    if (enabledSwitch) {
                        feishuService.start()
                    } else {
                        feishuService.stop()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = hasChanges
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("保存设置")
        }

        // 保存成功提示
        if (showSaveSuccess) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(3000)
                showSaveSuccess = false
            }
            Snackbar(
                modifier = Modifier.padding(top = 16.dp),
                action = {
                    TextButton(onClick = { showSaveSuccess = false }) {
                        Text("确定")
                    }
                }
            ) {
                Text(if (enabledSwitch) "飞书服务已启动，请确保悬浮窗已开启" else "飞书服务已停止")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}