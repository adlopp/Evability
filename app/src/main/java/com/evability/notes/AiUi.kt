package com.evability.notes

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun openUrl(ctx: android.content.Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, "No se pudo abrir el enlace", Toast.LENGTH_LONG).show()
    }
}

/** Ajustes: campo para la clave de Gemini y las instrucciones para conseguir la propia. */
@Composable
fun GeminiKeySection() {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(Prefs.geminiKey) }
    var show by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Clave de Gemini (para «Analizar»)", color = OnSurfaceDim)
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim(); Prefs.geminiKey = key },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Pega aquí tu clave (AIza…)") },
            visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Ocultar" else "Ver") } },
        )
        Text(
            "Es gratis y cada persona usa la suya:\n" +
                "1. Pulsa «Conseguir mi clave» (abre Google AI Studio).\n" +
                "2. Inicia sesión con tu cuenta de Google.\n" +
                "3. Pulsa «Crear clave de API» y elige un proyecto (o crea uno nuevo).\n" +
                "4. Copia la clave que empieza por AIza y pégala aquí arriba.",
            fontSize = 13.sp, color = OnSurfaceDim,
        )
        TextButton(onClick = { openUrl(ctx, Gemini.KEY_URL) }, contentPadding = PaddingValues(0.dp)) {
            Text("Conseguir mi clave", color = Accent, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "La clave se guarda solo en este dispositivo. Los documentos que analices se envían a Google.",
            fontSize = 12.sp, color = OnSurfaceDim,
        )
    }
}

private sealed interface Phase {
    data object Pick : Phase
    data class Running(val mode: Gemini.Mode) : Phase
    data class Failed(val message: String) : Phase
}

/** Todo el flujo de «Analizar»: elegir documento y tipo, esperar, y ver el resultado. */
@Composable
fun AnalyzeFlow(docs: List<DocInfo>, store: Store, onOpenSettings: () -> Unit, onCreated: (DocInfo) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<Phase>(Phase.Pick) }
    var picked by remember { mutableStateOf(docs.maxByOrNull { it.lastOpened }) }
    var job by remember { mutableStateOf<Job?>(null) }
    var stage by remember { mutableStateOf("Preparando…") }
    var fraction by remember { mutableStateOf<Float?>(null) }
    var seconds by remember { mutableStateOf(0) }

    fun start(mode: Gemini.Mode) {
        val doc = picked ?: return
        phase = Phase.Running(mode)
        stage = "Preparando…"
        fraction = null
        seconds = 0
        job = scope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    val r = Gemini.analyze(Prefs.geminiKey, store.pdfFile(doc.id), doc.name, mode) { st, f -> stage = st; fraction = f }
                    stage = "Creando el documento…"
                    fraction = 1f
                    val title = (if (mode == Gemini.Mode.RESEARCH) "Resumen_investigación_" else "Resumen_") + doc.name
                    try {
                        store.createGenerated(title) { SummaryPdf.write(title, doc.name, r.text, r.sources, it) }
                    } catch (e: Exception) {
                        throw Gemini.GeminiException("Se obtuvo el resumen pero no se pudo crear el documento")
                    }
                }
                onCreated(info)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Gemini.GeminiException) {
                phase = Phase.Failed(e.message ?: "Error desconocido")
            } catch (e: java.net.SocketTimeoutException) {
                phase = Phase.Failed("Gemini tardó demasiado en responder. Inténtalo de nuevo.")
            } catch (e: java.io.IOException) {
                phase = Phase.Failed("No se pudo conectar. ¿Tienes conexión a internet?")
            } catch (e: Exception) {
                phase = Phase.Failed("No se pudo analizar el documento")
            }
        }
    }

    LaunchedEffect(phase) {
        while (phase is Phase.Running) {
            kotlinx.coroutines.delay(1000)
            seconds++
        }
    }

    when (val p = phase) {
        Phase.Pick -> PickDialog(docs, picked, { picked = it }, ::start, onOpenSettings, onDismiss)
        is Phase.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(if (p.mode == Gemini.Mode.RESEARCH) "Investigando…" else "Resumiendo…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (p.mode == Gemini.Mode.RESEARCH) {
                            "«${picked?.name}»: busco en internet si algo ha cambiado. Puede tardar varios minutos; no cierres la app."
                        } else {
                            "«${picked?.name}»"
                        },
                        color = OnSurfaceDim, fontSize = 13.sp,
                    )
                    Text(stage, fontWeight = FontWeight.SemiBold)
                    val f = fraction
                    if (f == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
                    } else {
                        LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (f == null) "Esperando a Gemini…" else "${(f * 100).toInt()}%", fontSize = 13.sp, color = OnSurfaceDim)
                        Text("%d:%02d".format(seconds / 60, seconds % 60), fontSize = 13.sp, color = OnSurfaceDim)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { job?.cancel(); onDismiss() }) { Text("Cancelar") } },
        )
        is Phase.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("No se pudo analizar") },
            text = { Text(p.message) },
            confirmButton = { TextButton(onClick = { phase = Phase.Pick }) { Text("Volver") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        )
    }
}

@Composable
private fun PickDialog(
    docs: List<DocInfo>,
    picked: DocInfo?,
    onPick: (DocInfo) -> Unit,
    onStart: (Gemini.Mode) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hasKey = Prefs.geminiKey.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(buildAnnotatedString {
                append("Analizar con IA ")
                withStyle(SpanStyle(fontSize = 13.sp, color = OnSurfaceDim, fontWeight = FontWeight.Normal)) { append("(en construcción)") }
            })
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (docs.isEmpty()) {
                    Text("Primero importa un PDF para poder analizarlo.", color = OnSurfaceDim)
                    return@Column
                }
                Text("Documento", color = OnSurfaceDim)
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 190.dp).clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Outline, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()),
                ) {
                    docs.sortedByDescending { it.lastOpened }.forEach { d ->
                        val sel = d.id == picked?.id
                        Text(
                            d.name,
                            modifier = Modifier.fillMaxWidth().background(if (sel) Accent.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { onPick(d) }.padding(horizontal = 14.dp, vertical = 11.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                if (!hasKey) {
                    Text("Para usar esta función necesitas tu clave gratuita de Gemini.", color = OnSurface)
                    Button(onClick = onOpenSettings, shape = RoundedCornerShape(14.dp)) { Text("Cómo conseguirla (Ajustes)") }
                } else {
                    Button(
                        onClick = { onStart(Gemini.Mode.SUMMARY) },
                        enabled = picked != null,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Icon(Icons.Default.Summarize, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Resumen", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text("Lo esencial del documento, sin relleno", fontSize = 12.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = { onStart(Gemini.Mode.RESEARCH) },
                        enabled = picked != null,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.5.dp, Accent),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Icon(Icons.Default.TravelExplore, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Resumen con investigación", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text("Busca en internet si algo está desactualizado (tarda más)", fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
