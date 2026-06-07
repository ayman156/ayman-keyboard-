package com.example.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.data.ClipboardDatabase
import com.example.data.ClipboardItem
import com.example.data.ClipboardRepository
import com.example.keyboard.layout.LayoutLoader
import com.example.keyboard.model.KeyType
import com.example.keyboard.model.KeyboardKey
import com.example.keyboard.model.KeyboardLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class AmilInputMethodService : InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val TAG = "AmilKeyboard"

    // Lifecycle, VM, and SavedState components for Compose support
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = savedStateStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    // Scopes for async actions (Room, simulated typing)
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var simulatedTypingJob: Job? = null

    // Database & Repository
    private lateinit var database: ClipboardDatabase
    private lateinit var repository: ClipboardRepository

    // Keyboard configurations
    private lateinit var layoutEn: KeyboardLayout
    private lateinit var layoutAr: KeyboardLayout
    private lateinit var layoutSymbols: KeyboardLayout

    // UI States
    private val _currentLayout = MutableStateFlow<KeyboardLayout?>(null)
    val currentLayout: StateFlow<KeyboardLayout?> = _currentLayout.asStateFlow()

    private val _clipboardItems = MutableStateFlow<List<ClipboardItem>>(emptyList())
    val clipboardItems: StateFlow<List<ClipboardItem>> = _clipboardItems.asStateFlow()

    // Preferences & Control States
    var selectedThemeIdx = mutableStateOf(0) // 0: Dark Cosmic, 1: Light Classic, 2: Emerald Green, 3: Warm Amber
    var keyHeightDp = mutableStateOf(52) // Default key height
    var hapticFeedbackEnabled = mutableStateOf(true)
    var isShifted = mutableStateOf(false)
    var isRecording = mutableStateOf(false)
    var recordingResultText = mutableStateOf("")

    // Current screen mode: KEYBOARD, CLIPBOARD, EMOJI
    enum class KeyboardScreenMode { KEYBOARD, CLIPBOARD, EMOJI }
    var currentScreenMode = mutableStateOf(KeyboardScreenMode.KEYBOARD)

    // Current word being typed (for suggestions)
    var currentTypingWord = mutableStateOf("")

    // Speech Recognizer
    private var speechRecognizer: SpeechRecognizer? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Initialize DB
        database = ClipboardDatabase.getDatabase(this)
        repository = ClipboardRepository(database.clipboardDao())

        // Load dynamic layouts from JSON assets
        layoutEn = LayoutLoader.loadLayout(this, com.example.R.raw.keyboard_en) ?: throw IllegalStateException("Failed to load layout_en")
        layoutAr = LayoutLoader.loadLayout(this, com.example.R.raw.keyboard_ar) ?: throw IllegalStateException("Failed to load layout_ar")
        layoutSymbols = LayoutLoader.loadLayout(this, com.example.R.raw.keyboard_symbols) ?: throw IllegalStateException("Failed to load layout_symbols")

        _currentLayout.value = layoutAr // Default to Arabic

        // Observe Clipboard History Flow
        serviceScope.launch {
            repository.allItems.collect { items ->
                _clipboardItems.value = items
            }
        }

        // Register clipboard listener to automatically save newly copied text
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!clipText.isNullOrBlank()) {
                serviceScope.launch(Dispatchers.IO) {
                    repository.insert(clipText)
                }
            }
        }
        clipboard.addPrimaryClipChangedListener(clipboardListener)

        // Load user settings from Shared Preferences
        loadSettings()
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        val composeView = ComposeView(this)
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            KeyboardRoot()
        }

        return composeView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        currentTypingWord.value = ""

        // Check if clipboard has new content to capture upon focus
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                serviceScope.launch(Dispatchers.IO) {
                    // Try to insert if not matching the last clip
                    repository.insert(text)
                }
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        stopVoiceRecognition()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        savedStateStore.clear()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboardListener != null) {
            clipboard.removePrimaryClipChangedListener(clipboardListener)
        }
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    // Load configs
    private fun loadSettings() {
        val prefs = getSharedPreferences("amil_keyboard_prefs", Context.MODE_PRIVATE)
        selectedThemeIdx.value = prefs.getInt("selected_theme", 0)
        keyHeightDp.value = prefs.getInt("key_height", 52)
        hapticFeedbackEnabled.value = prefs.getBoolean("haptic_feedback", true)
    }

    // Save single config setting from keyboard if dynamically modified
    fun saveSettings() {
        val prefs = getSharedPreferences("amil_keyboard_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt("selected_theme", selectedThemeIdx.value)
            putInt("key_height", keyHeightDp.value)
            putBoolean("haptic_feedback", hapticFeedbackEnabled.value)
            apply()
        }
    }

    // Handle tactile feedback / vibrate
    fun triggerHapticFeedback() {
        if (!hapticFeedbackEnabled.value) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(15)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate exception", e)
        }
    }

    // Actions for Key Presses
    fun handleKeyPress(key: KeyboardKey) {
        triggerHapticFeedback()
        val ic = currentInputConnection ?: return

        when (key.type) {
            KeyType.CHARACTER -> {
                var text = key.label
                if (currentLayout.value?.languageCode == "en") {
                    text = if (isShifted.value) text.uppercase(Locale.ENGLISH) else text.lowercase(Locale.ENGLISH)
                }
                ic.commitText(text, 1)
                currentTypingWord.value += text
            }
            KeyType.SPACE -> {
                ic.commitText(" ", 1)
                currentTypingWord.value = ""
            }
            KeyType.DELETE -> {
                // Delete one character
                val selected = ic.getSelectedText(0)
                if (selected.isNullOrEmpty()) {
                    ic.deleteSurroundingText(1, 0)
                } else {
                    ic.commitText("", 1)
                }
                if (currentTypingWord.value.isNotEmpty()) {
                    currentTypingWord.value = currentTypingWord.value.dropLast(1)
                }
            }
            KeyType.SHIFT -> {
                if (currentLayout.value?.languageCode == "symbols") {
                    // Toggle back to alphabet (Arabic default or English last)
                    _currentLayout.value = layoutAr
                } else {
                    isShifted.value = !isShifted.value
                }
            }
            KeyType.LANGUAGE_SWITCH -> {
                when (_currentLayout.value?.languageCode) {
                    "ar" -> {
                        _currentLayout.value = layoutEn
                        isShifted.value = false
                    }
                    "en" -> {
                        _currentLayout.value = layoutAr
                    }
                    "symbols" -> {
                        _currentLayout.value = layoutAr
                    }
                    else -> {
                        _currentLayout.value = layoutAr
                    }
                }
                currentTypingWord.value = ""
            }
            KeyType.CLIPBOARD -> {
                currentScreenMode.value = if (currentScreenMode.value == KeyboardScreenMode.CLIPBOARD) {
                    KeyboardScreenMode.KEYBOARD
                } else {
                    KeyboardScreenMode.CLIPBOARD
                }
            }
            KeyType.VOICE -> {
                if (isRecording.value) {
                    stopVoiceRecognition()
                } else {
                    startVoiceRecognition()
                }
            }
            KeyType.EMOJI -> {
                currentScreenMode.value = if (currentScreenMode.value == KeyboardScreenMode.EMOJI) {
                    KeyboardScreenMode.KEYBOARD
                } else {
                    KeyboardScreenMode.EMOJI
                }
            }
            KeyType.ENTER -> {
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
                currentTypingWord.value = ""
            }
            else -> {}
        }
    }

    // Toggle symbols keyboard block
    fun toggleSymbolsKeyboard() {
        triggerHapticFeedback()
        if (_currentLayout.value?.languageCode == "symbols") {
            _currentLayout.value = layoutAr
        } else {
            _currentLayout.value = layoutSymbols
        }
        currentTypingWord.value = ""
    }

    // Paste standard clipboard text instantly
    fun pasteClipboardItemDirectly(text: String) {
        val ic = currentInputConnection ?: return
        triggerHapticFeedback()
        ic.commitText(text, 1)
        currentScreenMode.value = KeyboardScreenMode.KEYBOARD
        currentTypingWord.value = ""
    }

    // Auto-Typer typing paste: Commit text char-by-char with simulated human delay
    fun pasteClipboardItemSimulated(text: String) {
        val ic = currentInputConnection ?: return
        triggerHapticFeedback()
        currentScreenMode.value = KeyboardScreenMode.KEYBOARD
        currentTypingWord.value = ""

        // Cancel previous typing job if running
        simulatedTypingJob?.cancel()
        simulatedTypingJob = serviceScope.launch(Dispatchers.Main) {
            for (char in text) {
                ic.commitText(char.toString(), 1)
                delay(70L) // Beautiful 70ms natural delay
            }
        }
    }

    fun deleteClipItem(item: ClipboardItem) {
        serviceScope.launch(Dispatchers.IO) {
            repository.delete(item)
        }
    }

    fun toggleClipItemPin(item: ClipboardItem) {
        serviceScope.launch(Dispatchers.IO) {
            repository.updatePinnedState(item.id, !item.isPinned)
        }
    }

    // Voice Typing (Speech to Text) Native SpeechRecognizer
    private fun startVoiceRecognition() {
        triggerHapticFeedback()
        val speech = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = speech

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (_currentLayout.value?.languageCode == "ar") "ar-SA" else "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isRecording.value = true
                recordingResultText.value = ""
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Log.e(TAG, "SpeechRecognizer error: $error")
                stopVoiceRecognition()
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val resultText = matches[0]
                    currentInputConnection?.commitText(resultText + " ", 1)
                }
                stopVoiceRecognition()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    recordingResultText.value = matches[0]
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            speech.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting speech listening", e)
            stopVoiceRecognition()
        }
    }

    private fun stopVoiceRecognition() {
        isRecording.value = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // Jetpack Compose Design Components
    @Composable
    fun KeyboardRoot() {
        val themeIdx by remember { selectedThemeIdx }
        val currentMode by remember { currentScreenMode }

        // M3 customized palettes
        val colors = getThemeColors(themeIdx)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Predictive & Clipboard toolbar
                UtilityBar(colors)

                when (currentMode) {
                    KeyboardScreenMode.KEYBOARD -> {
                        MainKeysGrid(colors)
                    }
                    KeyboardScreenMode.CLIPBOARD -> {
                        ClipboardDashboard(colors)
                    }
                    KeyboardScreenMode.EMOJI -> {
                        EmojiGrid(colors)
                    }
                }
            }

            // Speech recording overlay
            if (isRecording.value) {
                SpeechRecordingOverlay(colors)
            }
        }
    }

    @Composable
    fun UtilityBar(colors: ThemePalette) {
        val currentMode by remember { currentScreenMode }
        val typingWord by remember { currentTypingWord }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(colors.toolbarBackground)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Quick Mode switch widgets
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        triggerHapticFeedback()
                        currentScreenMode.value = if (currentMode == KeyboardScreenMode.CLIPBOARD) {
                            KeyboardScreenMode.KEYBOARD
                        } else {
                            KeyboardScreenMode.CLIPBOARD
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = "الحافظة",
                        tint = if (currentMode == KeyboardScreenMode.CLIPBOARD) colors.accent else colors.textOnSecondary
                    )
                }

                IconButton(
                    onClick = {
                        triggerHapticFeedback()
                        currentScreenMode.value = if (currentMode == KeyboardScreenMode.EMOJI) {
                            KeyboardScreenMode.KEYBOARD
                        } else {
                            KeyboardScreenMode.EMOJI
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.EmojiEmotions,
                        contentDescription = "الملصقات",
                        tint = if (currentMode == KeyboardScreenMode.EMOJI) colors.accent else colors.textOnSecondary
                    )
                }

                IconButton(
                    onClick = {
                        triggerHapticFeedback()
                        if (isRecording.value) stopVoiceRecognition() else startVoiceRecognition()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "الإدخال الصوتي",
                        tint = if (isRecording.value) colors.accent else colors.textOnSecondary
                    )
                }
            }

            // Word suggestions centered
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                val suggestions = getWordSuggestions(typingWord)
                if (suggestions.isEmpty()) {
                    // Show helpful custom Arabic phrases when there is no typing
                    listOf("السلام عليكم", "بسم الله الرحمن الرحيم", "شكراً لك", "جزاك الله خيراً", "إن شاء الله").forEach { phrase ->
                        SuggestionChip(text = phrase, colors = colors) {
                            triggerHapticFeedback()
                            currentInputConnection?.commitText(phrase + " ", 1)
                            currentTypingWord.value = ""
                        }
                    }
                } else {
                    suggestions.forEach { suggestion ->
                        SuggestionChip(text = suggestion, colors = colors) {
                            triggerHapticFeedback()
                            val ic = currentInputConnection
                            if (ic != null) {
                                // Replace current word with selection
                                ic.deleteSurroundingText(typingWord.length, 0)
                                ic.commitText(suggestion + " ", 1)
                            }
                            currentTypingWord.value = ""
                        }
                    }
                }
            }

            // Settings click logic to cycle theme in real time!
            IconButton(
                onClick = {
                    triggerHapticFeedback()
                    selectedThemeIdx.value = (selectedThemeIdx.value + 1) % 4
                    saveSettings()
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = "تغيير المظهر",
                    tint = colors.accent
                )
            }
        }
    }

    @Composable
    fun SuggestionChip(text: String, colors: ThemePalette, onClick: () -> Unit) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .clickable { onClick() },
            shape = RoundedCornerShape(16.dp),
            color = colors.keyBackground.copy(alpha = 0.6f)
        ) {
            Text(
                text = text,
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }

    @Composable
    fun MainKeysGrid(colors: ThemePalette) {
        val layout by currentLayout.collectAsState()
        val keyHeight by remember { keyHeightDp }

        if (layout != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp, top = 2.dp)
            ) {
                layout?.rows?.forEachIndexed { rowIndex, rowKeys ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.5f.pxToDp()), // Consistent dynamic gap scaling
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        rowKeys.forEach { key ->
                            val isArabicLayout = layout?.languageCode == "ar"
                            val keyWeight = getCustomKeyWeight(key)

                            Box(
                                modifier = Modifier
                                    .weight(keyWeight)
                                    .height(keyHeight.dp)
                                    .padding(horizontal = 1.5.dp)
                            ) {
                                KeyboardButton(key = key, colors = colors)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getCustomKeyWeight(key: KeyboardKey): Float {
        return when (key.type) {
            KeyType.SPACE -> 4.5f
            KeyType.SHIFT -> 1.5f
            KeyType.DELETE -> 1.5f
            KeyType.ENTER -> 1.6f
            KeyType.LANGUAGE_SWITCH -> 1.2f
            KeyType.CLIPBOARD, KeyType.VOICE, KeyType.EMOJI -> 1.1f
            else -> 1f
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun KeyboardButton(key: KeyboardKey, colors: ThemePalette) {
        val finalLabel = if (key.type == KeyType.CHARACTER && currentLayout.value?.languageCode == "en") {
            if (isShifted.value) key.label.uppercase(Locale.ENGLISH) else key.label.lowercase(Locale.ENGLISH)
        } else if (key.type == KeyType.SPACE) {
            currentLayout.value?.languageName ?: "space"
        } else {
            key.label
        }

        val buttonColor = when (key.type) {
            KeyType.CHARACTER -> colors.keyBackground
            KeyType.SPACE -> colors.keyBackground
            KeyType.SHIFT -> if (isShifted.value) colors.accent else colors.specialKeyBackground
            KeyType.DELETE -> colors.specialKeyBackground
            KeyType.LANGUAGE_SWITCH, KeyType.CLIPBOARD, KeyType.VOICE, KeyType.EMOJI -> colors.specialKeyBackground
            KeyType.ENTER -> colors.accent
            else -> colors.keyBackground
        }

        val textTint = when (key.type) {
            KeyType.SHIFT -> if (isShifted.value) colors.textOnAccent else colors.textPrimary
            KeyType.ENTER -> colors.textOnAccent
            else -> colors.textPrimary
        }

        Card(
            shape = RoundedCornerShape(7.dp),
            colors = CardDefaults.cardColors(containerColor = buttonColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick = { handleKeyPress(key) },
                    onLongClick = {
                        if (key.type == KeyType.SHIFT) {
                            toggleSymbolsKeyboard()
                        } else if (key.type == KeyType.DELETE) {
                            // Clear all typing on long press delete
                            triggerHapticFeedback()
                            currentInputConnection?.commitText("", 1)
                            currentTypingWord.value = ""
                        } else {
                            handleKeyPress(key)
                        }
                    }
                )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // Key contents
                if (key.type == KeyType.DELETE) {
                    Icon(imageVector = Icons.Default.Backspace, contentDescription = "مسح", tint = textTint, modifier = Modifier.size(20.dp))
                } else if (key.type == KeyType.VOICE) {
                    Icon(imageVector = Icons.Default.Mic, contentDescription = "صوت", tint = textTint, modifier = Modifier.size(19.dp))
                } else if (key.type == KeyType.CLIPBOARD) {
                    Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "حافظة", tint = textTint, modifier = Modifier.size(19.dp))
                } else if (key.type == KeyType.EMOJI) {
                    Icon(imageVector = Icons.Default.EmojiEmotions, contentDescription = "ايموجي", tint = textTint, modifier = Modifier.size(19.dp))
                } else {
                    Text(
                        text = finalLabel,
                        color = textTint,
                        fontSize = if (finalLabel.length > 2) 14.sp else 19.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    @Composable
    fun ClipboardDashboard(colors: ThemePalette) {
        val history by clipboardItems.collectAsState()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(colors.background)
        ) {
            // Header for clipboard
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "الحافظة الذكية (اضغط للصق العادي | اضغط مطولًا للكتابة الذكية ⚡)",
                    color = colors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Button(
                    onClick = {
                        triggerHapticFeedback()
                        currentScreenMode.value = KeyboardScreenMode.KEYBOARD
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("إغلاق", color = colors.textOnAccent, fontSize = 11.sp)
                }
            }

            if (history.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "الحافظة فارغة حالياً. النصوص المنسوخة ستظهر هنا تلقائياً.",
                        color = colors.textOnSecondary.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                ) {
                    items(history, key = { it.id }) { item ->
                        ClipboardItemCard(item = item, colors = colors)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun ClipboardItemCard(item: ClipboardItem, colors: ThemePalette) {
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (item.isPinned) colors.specialKeyBackground else colors.keyBackground
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .combinedClickable(
                    onClick = { pasteClipboardItemDirectly(item.text) },
                    onLongClick = { pasteClipboardItemSimulated(item.text) }
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Pin button
                IconButton(
                    onClick = {
                        triggerHapticFeedback()
                        toggleClipItemPin(item)
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "تثبيت",
                        tint = if (item.isPinned) colors.accent else colors.textOnSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Clipped text preview
                Text(
                    text = item.text,
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )

                // Paste simulated trigger and delete indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { pasteClipboardItemSimulated(item.text) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = "كتابة ذكية",
                            tint = colors.accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            triggerHapticFeedback()
                            deleteClipItem(item)
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "حذف",
                            tint = Color.Red.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun EmojiGrid(colors: ThemePalette) {
        val emojis = listOf(
            "😊", "😂", "🥰", "😍", "👍", "🔥", "🎉", "❤️", "🌹", "🤔",
            "😂", "👌", "👏", "🙌", "😭", "🙏", "🙄", "🥳", "✨", "🌟",
            "💯", "💼", "🤝", "💡", "📱", "💻", "🚀", "🇸🇦", "🇵🇸", "🇪🇬",
            "☕", "🍕", "🚗", "🏡", "⏰", "⏳", "✉️", "📞", "🔑", "🔒",
            "😃", "😄", "😅", "😆", "😉", "😋", "😎", "🤩", "🤪", "🤠",
            "🤝", "👥", "✍️", "📝", "📌", "📍", "📐", "⚔️", "🛡️", "🔑"
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(colors.background)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "الرموز التعبيرية (ايموجي)",
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = {
                        triggerHapticFeedback()
                        currentScreenMode.value = KeyboardScreenMode.KEYBOARD
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("رجوع للكيبورد", color = colors.textOnAccent, fontSize = 11.sp)
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 6.dp)
            ) {
                items(emojis) { emoji ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable {
                                triggerHapticFeedback()
                                currentInputConnection?.commitText(emoji, 1)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = emoji, fontSize = 24.sp)
                    }
                }
            }
        }
    }

    @Composable
    fun SpeechRecordingOverlay(colors: ThemePalette) {
        val textResult by remember { recordingResultText }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "تسجيل صوتي",
                    tint = colors.accent,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.15f))
                        .padding(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "جاري الاستماع... تحدث الآن باللغة المحددة",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (textResult.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.DarkGray),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = textResult,
                            color = colors.accent,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(12.dp).fillMaxWidth()
                        )
                    }
                } else {
                    Text(
                        text = "قل شيئاً ليقوم بالتحويل المباشر للكتابة...",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { stopVoiceRecognition() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("إيقاف التسجيل", color = Color.White)
                }
            }
        }
    }

    // Very realistic Offline Suggestions lookups
    private fun getWordSuggestions(prefix: String): List<String> {
        val trimmed = prefix.trim().lowercase(Locale.ENGLISH)
        if (trimmed.isEmpty()) return emptyList()

        val englishDictionary = listOf(
            "the", "this", "that", "there", "then", "their", "them", "thanks", "thank you",
            "and", "about", "are", "application", "ai studio", "android", "awesome",
            "builder", "beautiful", "best", "building", "briefcase",
            "can", "could", "clipboard", "come", "care", "custom",
            "development", "delay", "delete", "database", "design", "done",
            "english", "emoji", "excellent", "every", "easy", "enable",
            "feedback", "for", "from", "friendly", "feature", "framework",
            "good", "great", "generation", "greetings", "going",
            "hello", "how", "have", "here", "haptic", "history", "human",
            "is", "it", "in", "input", "integration", "important",
            "just", "job", "keyboard", "key", "kotlin", "keep",
            "love", "like", "language", "layout", "loader", "listening",
            "my", "me", "more", "much", "many", "modern", "material",
            "new", "nice", "never", "now", "notes", "number",
            "our", "out", "on", "okay", "offline", "opportunity",
            "please", "practical", "paste", "pinned", "program", "prompt",
            "quick", "question", "quiet", "quicker",
            "room", "repository", "recording", "real", "results", "rich",
            "service", "simulated", "suggestions", "speech", "smart", "status",
            "to", "the", "that", "typing", "theme", "time", "test",
            "understand", "user", "unicode", "utility", "unique",
            "voice", "vibration", "vibrate", "view", "verify", "value",
            "with", "working", "word", "write", "welcome", "workspace",
            "yes", "your", "you", "year", "yesterday", "yellow"
        )

        val arabicDictionary = listOf(
            "السلام", "عليكم", "ورحمة", "الله", "وبركاته", "شكرا", "جزيلا", "جزاك", "خيرا",
            "إن", "شاء", "الرحمن", "الرحيم", "بسم", "الحمد", "لله", "رب", "العالمين",
            "مرحباً", "بكم", "أخي", "العزيز", "صباح", "الخير", "مساء", "النور",
            "تطبيق", "لوحة", "مفاتيح", "كيبورد", "عملي", "مميز", "رائع", "جديد",
            "الحافظة", "الذكية", "الكتابة", "تلقائي", "محاكاة", "البشرية", "تأخير",
            "الرموز", "التعبيرية", "صوت", "تسجيل", "إدخال", "العربي", "الإنجليزي",
            "اليوم", "غداً", "العمل", "تعديل", "تثبيت", "حذف", "النص", "المظهر", "تحديث"
        )

        val joined = englishDictionary + arabicDictionary
        return joined.filter { it.startsWith(trimmed) || it.startsWith(prefix) }.take(5)
    }

    // Scale single pixel padding to dp
    private fun Float.pxToDp(): androidx.compose.ui.unit.Dp {
        return (this / (resources.displayMetrics.density)).dp
    }

    // Color Theme Definitions - Dynamic Light and Dark UI Match
    data class ThemePalette(
        val background: Color,
        val toolbarBackground: Color,
        val keyBackground: Color,
        val specialKeyBackground: Color,
        val accent: Color,
        val textPrimary: Color,
        val textOnSecondary: Color,
        val textOnAccent: Color
    )

    private fun getThemeColors(index: Int): ThemePalette {
        return when (index) {
            0 -> ThemePalette( // Dark Cosmic
                background = Color(0xFF1E1F22),
                toolbarBackground = Color(0xFF2B2D31),
                keyBackground = Color(0xFF35373C),
                specialKeyBackground = Color(0xFF2E3035),
                accent = Color(0xFF5865F2), // Elegant Purple Accent
                textPrimary = Color.White,
                textOnSecondary = Color(0xFF989AA2),
                textOnAccent = Color.White
            )
            1 -> ThemePalette( // Light Classic
                background = Color(0xFFF2F3F5),
                toolbarBackground = Color(0xFFE3E5E8),
                keyBackground = Color.White,
                specialKeyBackground = Color(0xFFDFE1E5),
                accent = Color(0xFF007AFF), // Apple Blue Accent
                textPrimary = Color.Black,
                textOnSecondary = Color(0xFF4E5058),
                textOnAccent = Color.White
            )
            2 -> ThemePalette( // Emerald Green Premium
                background = Color(0xFF0F1713),
                toolbarBackground = Color(0xFF16251D),
                keyBackground = Color(0xFF243A2F),
                specialKeyBackground = Color(0xFF1A2B22),
                accent = Color(0xFF10B981), // Emerald
                textPrimary = Color(0xFFECFDF5),
                textOnSecondary = Color(0xFFA7F3D0),
                textOnAccent = Color(0xFF064E3B)
            )
            3 -> ThemePalette( // Warm Amber
                background = Color(0xFF1A1612),
                toolbarBackground = Color(0xFF28211A),
                keyBackground = Color(0xFF3E3328),
                specialKeyBackground = Color(0xFF2E251E),
                accent = Color(0xFFF59E0B), // Warm Amber
                textPrimary = Color(0xFFFFFBEB),
                textOnSecondary = Color(0xFFFDE68A),
                textOnAccent = Color(0xFF78350F)
            )
            else -> ThemePalette(
                background = Color(0xFF1E1F22),
                toolbarBackground = Color(0xFF2B2D31),
                keyBackground = Color(0xFF35373C),
                specialKeyBackground = Color(0xFF2E3035),
                accent = Color(0xFF5865F2),
                textPrimary = Color.White,
                textOnSecondary = Color(0xFF989AA2),
                textOnAccent = Color.White
            )
        }
    }
}
