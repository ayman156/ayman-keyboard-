package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.data.ClipboardDatabase
import com.example.data.ClipboardItem
import com.example.data.ClipboardRepository
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var database: ClipboardDatabase
    private lateinit var repository: ClipboardRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        database = ClipboardDatabase.getDatabase(this)
        repository = ClipboardRepository(database.clipboardDao())

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    DashboardScreen(
                        repository = repository,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun DashboardScreen(repository: ClipboardRepository, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // State for checking if IME is enabled and active
    var isEnabled by remember { mutableStateOf(false) }
    var isSelected by remember { mutableStateOf(false) }

    // Settings loaded from SharedPref
    val prefs = remember { context.getSharedPreferences("amil_keyboard_prefs", Context.MODE_PRIVATE) }
    var hapticState by remember { mutableStateOf(prefs.getBoolean("haptic_feedback", true)) }
    var selectedThemeIdx by remember { mutableStateOf(prefs.getInt("selected_theme", 0)) }
    var keyHeightState by remember { mutableStateOf(prefs.getInt("key_height", 52)) }

    // Live list of clipboard history
    val clipboardHistory = remember { mutableStateListOf<ClipboardItem>() }
    val scope = rememberCoroutineScope()

    // Query helper to check IME states
    fun reloadImeStatus() {
        isEnabled = isKeyboardEnabled(context)
        isSelected = isKeyboardSelected(context)
    }

    // Refresh history
    fun reloadHistory() {
        scope.launch {
            repository.allItems.collect { items ->
                clipboardHistory.clear()
                clipboardHistory.addAll(items)
            }
        }
    }

    LaunchedEffect(Unit) {
        reloadImeStatus()
        reloadHistory()
    }

    // Polling isKeyboardSelected when returning from system pages
    DisposableEffect(Unit) {
        reloadImeStatus()
        onDispose {}
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // App header title
        item {
            AppHeader()
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Setup Steps
        item {
            SetupStepsCard(
                isEnabled = isEnabled,
                isSelected = isSelected,
                onEnableClick = {
                    val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                    context.startActivity(intent)
                    Toast.makeText(context, "الرجاء تحديد 'كيبورد عملي' لتفعيله", Toast.LENGTH_LONG).show()
                },
                onSelectClick = {
                    val imeManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imeManager.showInputMethodPicker()
                },
                onRefresh = { reloadImeStatus() }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Sandbox Input Test Box
        item {
            InteractiveScratchpad()
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Customization & Settings Panel
        item {
            CustomizationCard(
                hapticState = hapticState,
                onHapticChange = { newValue ->
                    hapticState = newValue
                    prefs.edit().putBoolean("haptic_feedback", newValue).apply()
                },
                selectedThemeIdx = selectedThemeIdx,
                onThemeSelected = { idx ->
                    selectedThemeIdx = idx
                    prefs.edit().putInt("selected_theme", idx).apply()
                },
                keyHeight = keyHeightState,
                onKeyHeightChange = { h ->
                    keyHeightState = h
                    prefs.edit().putInt("key_height", h).apply()
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Clipboard Manager
        item {
            Text(
                text = "قائمة الحافظة الذكية والعبارات 📋",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Right,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
        }

        if (clipboardHistory.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "لا توجد نصوص منسوخة حالياً في الحافظة.\nابدأ بنسخ أي نصوص لتلقيها هنا بشكل تلقائي، أو قم بإضافتها يدوياً باستخدام الكيبورد.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    )
                }
            }
        } else {
            items(clipboardHistory, key = { it.id }) { item ->
                DashboardClipboardItemRow(
                    item = item,
                    onDelete = {
                        scope.launch(Dispatchers.IO) {
                            repository.delete(item)
                        }
                    },
                    onTogglePin = {
                        scope.launch(Dispatchers.IO) {
                            repository.updatePinnedState(item.id, !item.isPinned)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun AppHeader() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Keyboard,
                contentDescription = "شعار التطبيق",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "كيبورد عملي",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        Text(
            text = "لوحة المفاتيح الذكية متكاملة واللصق الذكي بمحاكاة الكتابة",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun SetupStepsCard(
    isEnabled: Boolean,
    isSelected: Boolean,
    onEnableClick: () -> Unit,
    onSelectClick: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onRefresh) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "تحديث الحالة", tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = "خطوات التفعيل السهلة",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Step 1
            StepItem(
                number = "١",
                title = "تفعيل الكيبورد من إعدادات النظام",
                description = "قم بالسماح لـ 'كيبورد عملي' بالظهور كلوحة إدخال مسموح بها.",
                isCompleted = isEnabled,
                buttonText = "اضغط للتفعيل ⚙️",
                onClick = onEnableClick
            )

            Divider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // Step 2
            StepItem(
                number = "٢",
                title = "اختيار الكيبورد كلوحة افتراضية",
                description = "اختر 'كيبورد عملي' ليكون لوحة مفاتيحك النشطة الآن.",
                isCompleted = isSelected,
                buttonText = "اضغط للاختيار 🌐",
                onClick = onSelectClick,
                enabled = isEnabled
            )

            if (isEnabled && isSelected) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFE8F5E9))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "كيبورد عملي مفعل وجاهز تماماً للكتابة! 🎉",
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun StepItem(
    number: String,
    title: String,
    description: String,
    isCompleted: Boolean,
    buttonText: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (isCompleted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Right
            )
            Text(
                text = description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Right,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onClick,
                enabled = enabled && !isCompleted,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCompleted) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.primary,
                    disabledContainerColor = if (isCompleted) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(
                    text = if (isCompleted) "تم التفعيل بنجاح ✓" else buttonText,
                    fontSize = 12.sp,
                    color = if (isCompleted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    if (isCompleted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary.copy(
                        alpha = 0.15f
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isCompleted) "✓" else number,
                color = if (isCompleted) Color.White else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun InteractiveScratchpad() {
    var textState by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = "منطقة تجربة الكيبورد ✍️",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "جرب الكتابة أو الحافظة واللصق الذكي مباشرة هنا بالأسفل:",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = textState,
                onValueChange = { textState = it },
                label = { Text("اكتب هنا للتجربة...", textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth()) },
                placeholder = { Text("انقر لتفعيل الكيبورد وفحص الميزات...", textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth()) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

@Composable
fun CustomizationCard(
    hapticState: Boolean,
    onHapticChange: (Boolean) -> Unit,
    selectedThemeIdx: Int,
    onThemeSelected: (Int) -> Unit,
    keyHeight: Int,
    onKeyHeightChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = "تخصيص الخصائص والمظهر 🎨",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Haptic Feedback Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = hapticState,
                    onCheckedChange = onHapticChange,
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "الاهتزاز عند لمس الأزرار (Haptic)",
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "تفعيل الاهتزاز الخفيف لملاحظة الضغطات tactile feedback",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Right
                    )
                }
            }

            Divider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // Key Heights Options
            Text(
                text = "ارتفاع الكيبورد وحجم الأزرار",
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val heights = listOf(
                    45 to "قصير مدمج",
                    52 to "عادي متوازن",
                    62 to "كبير مريح"
                )
                heights.forEach { (h, label) ->
                    val isSel = keyHeight == h
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .clickable { onKeyHeightChange(h) }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // Designer Themes Selector
            Text(
                text = "اختر مظهر الكيبورد",
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            val themeOptions = listOf(
                "الكوني المظلم 🌌" to Color(0xFF1E1F22),
                "الكلاسيكي المضيء ☀️" to Color(0xFFF2F3F5),
                "الزمردي الفاخر 💚" to Color(0xFF10B981),
                "الكهرمان الدافئ 🧡" to Color(0xFFF59E0B)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                themeOptions.forEachIndexed { idx, (title, colorHex) ->
                    val isSel = selectedThemeIdx == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onThemeSelected(idx) }
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(colorHex)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = title.substringBefore(" "),
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardClipboardItemRow(
    item: ClipboardItem,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isPinned) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Delete button
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "حذف النص", tint = Color.Red.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }

            // Clipboard content
            Text(
                text = item.text,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Right,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )

            // Pin button
            IconButton(onClick = onTogglePin, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "تثبيت النص",
                    tint = if (item.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// System helpers
fun isKeyboardEnabled(context: Context): Boolean {
    val imeManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val enabledList = imeManager.enabledInputMethodList
    return enabledList.any { it.packageName == context.packageName }
}

fun isKeyboardSelected(context: Context): Boolean {
    val currentImeId = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD
    )
    return currentImeId != null && currentImeId.contains(context.packageName)
}
