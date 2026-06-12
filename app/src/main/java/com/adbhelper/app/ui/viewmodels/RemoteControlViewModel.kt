package com.adbhelper.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adbhelper.app.core.adb.DeviceSession
import com.adbhelper.app.core.shell.ShellExecutor
import com.adbhelper.app.core.shell.ShellResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RemoteControlViewModel @Inject constructor(
    private val shellExecutor: ShellExecutor,
    private val deviceSession: DeviceSession
) : ViewModel() {

    private val _feedback = MutableSharedFlow<String>()
    val feedback: SharedFlow<String> = _feedback.asSharedFlow()

    private val serial: String?
        get() = deviceSession.selectedSerial.value

    fun home() = launchCmd { shellExecutor.inputKeyEvent(3, serial) }
    fun task() = launchCmd { shellExecutor.inputKeyEvent(187, serial) }
    fun menu() = launchCmd { shellExecutor.inputKeyEvent(82, serial) }
    fun back() = launchCmd { shellExecutor.inputKeyEvent(4, serial) }
    fun wakeUp() = launchCmd { shellExecutor.inputKeyEvent(224, serial) }
    fun sleep() = launchCmd { shellExecutor.inputKeyEvent(26, serial) }
    fun dpadUp() = launchCmd { shellExecutor.inputKeyEvent(19, serial) }
    fun dpadDown() = launchCmd { shellExecutor.inputKeyEvent(20, serial) }
    fun dpadLeft() = launchCmd { shellExecutor.inputKeyEvent(21, serial) }
    fun dpadRight() = launchCmd { shellExecutor.inputKeyEvent(22, serial) }
    fun dpadCenter() = launchCmd { shellExecutor.inputKeyEvent(23, serial) }
    fun volUp() = launchCmd { shellExecutor.inputKeyEvent(24, serial) }
    fun volDown() = launchCmd { shellExecutor.inputKeyEvent(25, serial) }
    fun tap(x: Int, y: Int) = launchCmd { shellExecutor.inputTap(x, y, serial) }
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) = launchCmd { shellExecutor.inputSwipe(x1, y1, x2, y2, serial) }
    fun sendText(text: String) = launchCmd { shellExecutor.inputText(text, serial) }

    private fun launchCmd(block: suspend () -> ShellResult) {
        viewModelScope.launch {
            try {
                val result = block()
                _feedback.emit(if (result.exitCode == 0) "ok" else "error: ${result.output.trim().take(100)}")
            } catch (e: Exception) {
                _feedback.emit("err: ${e.message?.take(100) ?: "unknown"}")
            }
        }
    }
}
