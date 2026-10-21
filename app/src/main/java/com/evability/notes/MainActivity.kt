package com.evability.notes

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        handle(intent)
        Prefs.init(applicationContext)
        StickerBitmaps.init(applicationContext)
        val store = Store(applicationContext)
        store.cleanOrphans()
        setContent { EvabilityTheme { App(store, incoming) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) incoming.value = i.data
    }
}

@Composable
private fun App(store: Store, incoming: MutableState<Uri?>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf(store.loadIndex()) }
    var editor by remember { mutableStateOf<EditorState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportInfo by remember { mutableStateOf<DocInfo?>(null) }

    fun reload() {
        docs = store.loadIndex()
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    fun openInfo(info: DocInfo) {
        scope.launch {
            busy = true
            try {
                editor = withContext(Dispatchers.IO) {
                    store.touch(info.id)
                    val doc = PdfDoc(store.pdfFile(info.id))
                    val aspects = List(doc.pageCount) { i -> doc.pageSize(i).let { (w, h) -> h.toFloat() / w } }
                    EditorState(store, store.loadIndex().firstOrNull { it.id == info.id } ?: info, doc, aspects)
                }
            } catch (e: Exception) {
                toast("No se pudo abrir el PDF")
            } finally {
                busy = false
            }
        }
    }

    fun importUri(uri: Uri) {
        scope.launch {
            busy = true
            try {
                val info = withContext(Dispatchers.IO) { store.import(uri) }
                reload()
                busy = false
                openInfo(info)
            } catch (e: Exception) {
                busy = false
                toast("No se pudo importar el archivo. ¿Es un PDF válido y sin contraseña?")
            }
        }
    }

    fun createTemplate(kind: TemplateKind) {
        scope.launch {
            busy = true
            try {
                val info = withContext(Dispatchers.IO) { store.createFromTemplate(kind) }
                reload()
                busy = false
                openInfo(info)
            } catch (e: Exception) {
                busy = false
                toast("No se pudo crear el documento")
            }
        }
    }

    fun closeEditor() {
        val st = editor ?: return
        editor = null
        scope.launch {
            withContext(Dispatchers.IO) {
                st.save()
                store.setLastPage(st.info.id, st.currentPage)
                try {
                    store.refreshThumbnail(st.info.id, st.doc, st.anns)
                } catch (e: Exception) {
                    // la miniatura anterior se queda
                }
                st.doc.close()
            }
            reload()
        }
    }

    // ───── actualizaciones desde GitHub ─────
    var update by remember { mutableStateOf<Updater.Release?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }

    fun checkUpdate(manual: Boolean) {
        scope.launch {
            if (manual) toast("Buscando actualizaciones…")
            try {
                val r = withContext(Dispatchers.IO) { Updater.check(ctx) }
                if (r != null) update = r else if (manual) toast("Ya tienes la última versión")
            } catch (e: Exception) {
                if (manual) toast("No se pudo comprobar. ¿Tienes conexión a internet?")
            }
        }
    }

    fun startUpdate(r: Updater.Release) {
        if (!Updater.canInstall(ctx)) {
            toast("Activa «Permitir desde esta fuente» para Evability y vuelve a pulsar Actualizar")
            Updater.openInstallSettings(ctx)
            return
        }
        scope.launch {
            progress = 0f
            try {
                val apk = withContext(Dispatchers.IO) { Updater.download(ctx, r.url) { progress = it } }
                progress = null
                update = null
                Updater.install(ctx, apk)
            } catch (e: Exception) {
                progress = null
                toast("No se pudo descargar la actualización")
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri(uri)
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val info = exportInfo
        if (uri != null && info != null) {
            scope.launch {
                exporting = true
                try {
                    withContext(Dispatchers.IO) {
                        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                            val st = editor
                            if (st != null && st.info.id == info.id) {
                                Exporter.export(st.doc, st.extraPages, st.anns, out)
                            } else {
                                PdfDoc(store.pdfFile(info.id)).use { d ->
                                    Exporter.export(d, info.extra, store.loadAnns(info.id), out)
                                }
                            }
                        }
                    }
                    toast("PDF guardado con tus anotaciones")
                } catch (e: Exception) {
                    toast("No se pudo exportar el PDF")
                } finally {
                    exporting = false
                }
            }
        }
    }

    LaunchedEffect(Unit) { checkUpdate(false) }

    LaunchedEffect(incoming.value) {
        incoming.value?.let {
            incoming.value = null
            importUri(it)
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        val st = editor
        if (st == null) {
            HomeScreen(
                docs = docs,
                store = store,
                onImport = { picker.launch(arrayOf("application/pdf")) },
                onTemplate = { createTemplate(it) },
                onCheckUpdate = { checkUpdate(true) },
                onOpen = { openInfo(it) },
                onExport = { exportInfo = it; saver.launch(it.name + "-anotado.pdf") },
                onChanged = { reload() },
            )
        } else {
            EditorScreen(
                state = st,
                exporting = exporting,
                onClose = { closeEditor() },
                onExport = { exportInfo = st.info; saver.launch(st.info.name + "-anotado.pdf") },
            )
        }
        update?.let { r ->
            AlertDialog(
                onDismissRequest = { if (progress == null) update = null },
                title = { Text("Nueva versión disponible") },
                text = {
                    Column {
                        Text(r.name + if (r.notes.isNotBlank()) "\n\n" + r.notes.trim() else "")
                        progress?.let { p ->
                            Spacer(Modifier.height(16.dp))
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = progress == null, onClick = { startUpdate(r) }) {
                        Text(if (progress == null) "Actualizar" else "Descargando…")
                    }
                },
                dismissButton = { TextButton(enabled = progress == null, onClick = { update = null }) { Text("Ahora no") } },
            )
        }
        if (busy) {
            Box(
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x99000000)).clickable(enabled = false) {},
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
    }
}
