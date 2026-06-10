package com.adbhelper.app

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.os.LocaleListCompat
import com.adbhelper.app.data.repositories.SettingsRepository
import com.adbhelper.app.ui.navigation.AdbHelperNavHost
import com.adbhelper.app.ui.theme.AdbHelperTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 开启 edge-to-edge 模式，让 Compose 的 imePadding() 在低版本安卓（如 Android 11）上也能正确获取键盘高度
        WindowCompat.setDecorFitsSystemWindows(window, false)

        applyLanguageSetting()
        applyThemeSettings()

        setContent {
            val darkMode by settingsRepository.darkModeFlow.collectAsState()
            val keepScreenOn by settingsRepository.keepScreenOnFlow.collectAsState()

            LaunchedEffect(keepScreenOn) {
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            AdbHelperTheme(
                darkTheme = darkMode
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AdbHelperNavHost()
                }
            }
        }
    }

    private fun applyLanguageSetting() {
        val language = runBlocking(Dispatchers.IO) {
            settingsRepository.getLanguage()
        }
        val localeList = when (language) {
            "en" -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.forLanguageTags("zh")
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    private fun applyThemeSettings() {
        runBlocking(Dispatchers.IO) { settingsRepository.loadSettings() }
        val keepScreenOn = settingsRepository.keepScreenOnFlow.value
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
