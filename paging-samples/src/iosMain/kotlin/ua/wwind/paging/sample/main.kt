package ua.wwind.paging.sample

import App
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

// PascalCase is the Compose Multiplatform iOS entry-point convention; Swift calls this by name.
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
