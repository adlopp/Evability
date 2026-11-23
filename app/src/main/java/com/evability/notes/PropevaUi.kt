package com.evability.notes

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Propeva Hunt (prototipo): el mismo juego que la web, en una pantalla web interna. La página y el juego (propeva.html y propeva.js) se copian
 * de docs/ a los assets al compilar, así que hay una sola versión del código. Por ahora solo la abre «adripollo» (ver [MiniGamesDialog]).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PropevaScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    AndroidView(
        modifier = Modifier.fillMaxSize().background(Color(0xFF14161C)).statusBarsPadding().navigationBarsPadding(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(0xFF14161C.toInt())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                // el botón «←» del juego avisa a la app para cerrar la pantalla
                addJavascriptInterface(object { @JavascriptInterface fun close() { post { onClose() } } }, "PropevaBridge")
                loadUrl("file:///android_asset/propeva.html")
            }
        },
        onRelease = { it.destroy() },
    )
}
