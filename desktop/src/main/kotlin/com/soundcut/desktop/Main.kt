package com.soundcut.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.io.File

fun main() = application {
    val windowState = rememberWindowState(width = 1100.dp, height = 800.dp, position = WindowPosition.PlatformDefault)
    Window(onCloseRequest = ::exitApplication, title = "SoundCut — чистка дикторских записей", state = windowState) {
        val scope = rememberCoroutineScope()
        val app = remember { AppState(scope) }
        DisposableEffect(Unit) {
            // Перетаскивание файлов в окно: WAV — запись, TXT/DOCX — текст, ZIP — модель.
            window.dropTarget = object : DropTarget() {
                @Synchronized
                override fun drop(e: DropTargetDropEvent) {
                    e.acceptDrop(DnDConstants.ACTION_COPY)
                    @Suppress("UNCHECKED_CAST")
                    val files = e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File> ?: return
                    for (f in files) {
                        when (f.extension.lowercase()) {
                            "wav", "wave" -> app.pickAudio(f)
                            "txt", "docx" -> app.pickScript(f)
                            "zip" -> app.useModel(f)
                        }
                    }
                    e.dropComplete(true)
                }
            }
            onDispose { app.dispose() }
        }
        App(app, window)
    }
}
