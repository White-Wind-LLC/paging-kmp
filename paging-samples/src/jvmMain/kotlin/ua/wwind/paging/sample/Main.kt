package ua.wwind.paging.sample

import App
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * Main entry point for JVM (Desktop) application
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Paging Library Sample"
    ) {
        App()
    }
}