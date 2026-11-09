package com.evability.notes

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Danger = Color(0xFFFF6B6B)

/** Foto del grupo: al tocarla se abre la galería y luego el recorte. */
@Composable
private fun GroupPhotoPicker(name: String, photo: String, onPhoto: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cropSrc by remember { mutableStateOf<Bitmap?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            try { cropSrc = withContext(Dispatchers.IO) { loadForCrop(ctx, uri) } } catch (e: Exception) { Toast.makeText(ctx, "No se pudo abrir la foto", Toast.LENGTH_SHORT).show() }
        }
    }
    cropSrc?.let { src ->
        CropDialog(
            src, onCancel = { cropSrc = null },
            onApply = { square ->
                cropSrc = null
                scope.launch { onPhoto(withContext(Dispatchers.IO) { encodePhoto(square).first }) }
            },
        )
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(name.ifBlank { "?" }, photo, 84, onTap = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        Text(if (photo.isEmpty()) "Añadir foto" else "Cambiar foto", fontSize = 12.sp, color = OnSurfaceDim, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Lista de amigos con casillas para elegir a quién invitar (`exclude` = quienes ya están o ya están invitados). */
@Composable
private fun FriendPicker(friends: List<Social.Profile>, exclude: Set<String>, max: Int, picked: MutableList<String>) {
    val ctx = LocalContext.current
    val list = friends.filter { it.uid !in exclude }
    if (list.isEmpty()) {
        Text("No hay más amigos a quienes invitar.", fontSize = 13.sp, color = OnSurfaceDim)
        return
    }
    list.forEach { f ->
        val on = f.uid in picked
        Row(
            Modifier.fillMaxWidth().clickable {
                if (on) picked.remove(f.uid)
                else if (picked.size >= max) Toast.makeText(ctx, "Un grupo puede tener hasta ${Social.MAX_GROUP} personas", Toast.LENGTH_SHORT).show()
                else picked.add(f.uid)
            }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(f.username, f.photo, 36)
            Spacer(Modifier.width(12.dp))
            Text(f.username, color = OnSurface, modifier = Modifier.weight(1f))
            Checkbox(checked = on, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Accent, checkmarkColor = OnAccent))
        }
    }
}

/** «Nuevo grupo»: nombre, descripción, foto y a qué amigos se invita. `onCreated(id)` se llama al terminar. */
@Composable
fun CreateGroupDialog(friends: List<Social.Profile>, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Nuevo grupo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GroupPhotoPicker(name.trim().take(1).uppercase(), photo) { photo = it }
                OutlinedTextField(name, { name = it.take(40); error = "" }, singleLine = true, label = { Text("Nombre del grupo") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it.take(200) }, maxLines = 3, label = { Text("Descripción (opcional)") }, modifier = Modifier.fillMaxWidth())
                Text("Invitar amigos", fontWeight = FontWeight.SemiBold, color = OnSurface, modifier = Modifier.padding(top = 4.dp))
                FriendPicker(friends, emptySet(), Social.MAX_GROUP - 1, picked)
                Text("Les llegará una invitación y entrarán cuando la acepten.", fontSize = 12.sp, color = OnSurfaceDim)
                if (error.isNotEmpty()) Text(error, color = Danger, fontSize = 14.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (name.isBlank()) {
                        error = "Ponle un nombre al grupo"
                    } else {
                        scope.launch {
                            busy = true
                            try {
                                val id = Social.createGroup(name, desc, photo, picked.toList())
                                Toast.makeText(ctx, "Grupo creado", Toast.LENGTH_SHORT).show()
                                onCreated(id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "No se pudo crear el grupo"
                            }
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Creando…" else "Crear grupo", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Cambiar el nombre, la descripción o la foto del grupo (solo quien lo creó). */
@Composable
private fun EditGroupDialog(g: Social.Group, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(g.name) }
    var desc by remember { mutableStateOf(g.desc) }
    var photo by remember { mutableStateOf(g.photo) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Editar grupo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GroupPhotoPicker(name.trim().take(1).uppercase(), photo) { photo = it }
                OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text("Nombre del grupo") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it.take(200) }, maxLines = 3, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            Social.editGroup(g.id, name, desc, photo)
                            onSaved()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(ctx, e.message ?: "No se pudo guardar", Toast.LENGTH_SHORT).show()
                        }
                        busy = false
                    }
                },
            ) { Text("Guardar", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Invitar a más amigos a un grupo que ya existe. */
@Composable
private fun InviteToGroupDialog(g: Social.Group, friends: List<Social.Profile>, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Invitar al grupo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FriendPicker(friends, (g.members + g.invited).toSet(), Social.MAX_GROUP - g.members.size - g.invited.size, picked)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && picked.isNotEmpty(),
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            Social.inviteToGroup(g.id, picked.toList())
                            Toast.makeText(ctx, "Invitación enviada", Toast.LENGTH_SHORT).show()
                            onDone()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(ctx, e.message ?: "No se pudo invitar", Toast.LENGTH_SHORT).show()
                        }
                        busy = false
                    }
                },
            ) { Text("Invitar", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } },
    )
}

/**
 * Información del grupo: foto, descripción y miembros. Quien lo creó puede editarlo, invitar, echar a alguien o eliminarlo;
 * los demás pueden salir. `onChanged` avisa con el grupo al día; `onGone` cuando ya no estás en él (saliste, te echaron o se eliminó).
 */
@Composable
fun GroupInfoDialog(group: Social.Group, onChanged: (Social.Group) -> Unit, onGone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = Social.session?.uid ?: return
    var g by remember { mutableStateOf(group) }
    var profs by remember { mutableStateOf<Map<String, Social.Profile>>(emptyMap()) }
    var friends by remember { mutableStateOf<List<Social.Profile>>(emptyList()) }
    var editing by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) } // «delete», «leave» o «kick:<uid>»
    val isOwner = g.owner == me

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    suspend fun reload() {
        val fresh = Social.getGroup(g.id)
        if (fresh == null || me !in fresh.members) { onGone(); return }
        g = fresh
        onChanged(fresh)
        profs = Social.profilesOf((fresh.members + fresh.invited).distinct())
    }
    fun refreshAfter(block: suspend () -> Unit) {
        scope.launch {
            try { block(); reload() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "No se pudo completar") }
        }
    }
    LaunchedEffect(Unit) {
        try { reload() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            profs = runCatching { Social.profilesOf((g.members + g.invited).distinct()) }.getOrDefault(emptyMap())
        }
    }

    if (editing) EditGroupDialog(g, onDismiss = { editing = false }, onSaved = { editing = false; refreshAfter { } })
    if (inviting) InviteToGroupDialog(g, friends, onDismiss = { inviting = false }, onDone = { inviting = false; refreshAfter { } })
    confirm?.let { what ->
        val kickUid = what.removePrefix("kick:").takeIf { what.startsWith("kick:") }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(when { what == "delete" -> "Eliminar grupo"; what == "leave" -> "Salir del grupo"; else -> "Quitar del grupo" }) },
            text = {
                Text(
                    when {
                        what == "delete" -> "Se eliminará para todos y se perderán sus mensajes."
                        what == "leave" -> "Dejarás de ver sus mensajes."
                        else -> "¿Quitar a ${profs[kickUid]?.username ?: "esta persona"} del grupo?"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    when {
                        what == "delete" -> scope.launch {
                            try { Social.deleteGroup(g.id); onGone() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "No se pudo eliminar") }
                        }
                        what == "leave" -> scope.launch {
                            try { Social.leaveGroup(g.id); onGone() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "No se pudo salir") }
                        }
                        kickUid != null -> refreshAfter { Social.kickFromGroup(g.id, kickUid) }
                    }
                }) { Text(if (what == "delete") "Eliminar" else if (what == "leave") "Salir" else "Quitar", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
        )
    }

    @Composable
    fun PersonLine(u: String, pendingInvite: Boolean) {
        val p = profs[u]
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(p?.username ?: "?", p?.photo ?: "", 38)
            Spacer(Modifier.width(12.dp))
            Text((p?.username ?: "?") + if (u == me) " (tú)" else "", color = OnSurface, modifier = Modifier.weight(1f))
            if (u == g.owner) Text("Creador", fontSize = 12.sp, color = OnSurfaceDim)
            else if (pendingInvite) Text("Invitado", fontSize = 12.sp, color = OnSurfaceDim)
            else if (isOwner) IconButton(onClick = { confirm = "kick:$u" }) { Icon(Icons.Default.PersonRemove, "Quitar del grupo", tint = OnSurfaceDim) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Grupo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(g.name, g.photo, 84)
                    Text(g.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OnSurface, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        g.desc.ifBlank { "Sin descripción" }, fontSize = 14.sp, color = OnSurfaceDim, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text("Miembros (${g.members.size}/${Social.MAX_GROUP})", fontWeight = FontWeight.SemiBold, color = OnSurface, modifier = Modifier.padding(top = 10.dp))
                g.members.forEach { PersonLine(it, false) }
                if (g.invited.isNotEmpty()) {
                    Text("Invitaciones pendientes", fontWeight = FontWeight.SemiBold, color = OnSurface, modifier = Modifier.padding(top = 8.dp))
                    g.invited.forEach { PersonLine(it, true) }
                }
                if (isOwner) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { editing = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Text(" Editar")
                        }
                        OutlinedButton(
                            enabled = g.members.size + g.invited.size < Social.MAX_GROUP,
                            onClick = {
                                scope.launch {
                                    friends = try { Social.overview().friends.map { it.profile } } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Sin conexión"); return@launch }
                                    inviting = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp)); Text(" Invitar")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = {
            TextButton(onClick = { confirm = if (isOwner) "delete" else "leave" }) { Text(if (isOwner) "Eliminar grupo" else "Salir del grupo", color = Danger) }
        },
    )
}
