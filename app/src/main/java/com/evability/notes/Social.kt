package com.evability.notes

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.random.Random
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/**
 * Parte social (perfil, amigos, presencia y «toques»). Habla con Firebase por su API REST, sin SDK, igual que Gemini:
 * Auth con correo y contraseña (permite recuperarla) y Firestore con las reglas de `firestore.rules`.
 */
object Social {
    // Estas claves no son secretas: la seguridad la ponen las reglas de Firestore.
    private const val PROJECT = "evability-a4c4d"
    private const val API_KEY = "AIzaSyD1LmNMAePR63FPvCldLWPXQl4JvmnFars"
    private const val DB = "projects/$PROJECT/databases/(default)/documents"
    private const val DOCS = "https://firestore.googleapis.com/v1/$DB"
    private const val AUTH = "https://identitytoolkit.googleapis.com/v1/accounts"

    const val COOLDOWN_MS = 15 * 60_000L
    const val ONLINE_MS = 90_000L

    class SocialError(message: String) : Exception(message)

    class Session(val uid: String, val username: String, var idToken: String, var refreshToken: String, var expiresAt: Long)
    class Profile(val uid: String, val username: String, val photo: String)
    class FriendRow(val profile: Profile, val lastSeen: Long, val lastPokeAt: Long, val studying: Boolean = false)
    /** Un grupo de amigos: `members` ya están dentro (el primero es quien lo creó), `invited` tienen la invitación pendiente. */
    class Group(val id: String, val name: String, val desc: String, val photo: String, val owner: String, val members: List<String>, val invited: List<String>, val lastFrom: String, val lastAt: Long)
    class GroupInvite(val group: Group, val ownerName: String)
    class Overview(val friends: List<FriendRow>, val incoming: List<Profile>, val outgoing: Set<String>, val groups: List<Group> = emptyList(), val groupInvites: List<GroupInvite> = emptyList())
    class Heart(val id: Long, val x: Float, val y: Float)
    class ChatMessage(val id: String, val mine: Boolean, val text: String, val at: Long, val kind: String = "", val dur: Long = 0L, val mins: Int = 0, val replyText: String = "", val replyFrom: String = "", val game: String = "", val deleted: Boolean = false, val from: String = "") {
        /** El mismo mensaje ya borrado: sin contenido, solo queda el aviso. */
        fun gone() = ChatMessage(id, mine, "", at, kind, 0L, 0, "", "", game, true, from)
    }

    /** Amigos y grupos con mensajes sin leer (para el punto de la lista y el de la cabecera). Los grupos van con la clave «g:<id>». */
    val unread = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    /** Solicitudes de amistad e invitaciones a grupos que esperan respuesta (también encienden el punto de la cabecera). */
    var pending by mutableIntStateOf(0)
        private set
    private var groupsCache: List<Group> = emptyList()
    private var friendUidsCache: List<String> = emptyList()
    private var friendsFetchedAt = 0L

    var session by mutableStateOf<Session?>(null)
        private set

    /** Corazones que se están viendo ahora mismo en pantalla. */
    val hearts = mutableStateListOf<Heart>()

    private var lastShownPoke = -1L
    private var nextHeart = 0L

    fun load() {
        session = runCatching {
            val j = JSONObject(Prefs.socialSession)
            Session(j.getString("uid"), j.getString("username"), j.getString("idToken"), j.getString("refreshToken"), j.getLong("expiresAt"))
        }.getOrNull()
    }

    private fun save(s: Session?) {
        session = s
        Prefs.socialSession = if (s == null) "" else JSONObject()
            .put("uid", s.uid).put("username", s.username).put("idToken", s.idToken)
            .put("refreshToken", s.refreshToken).put("expiresAt", s.expiresAt).toString()
    }

    /** Solo minúsculas, números y guion bajo (3 a 16). */
    fun cleanName(raw: String) = raw.trim().lowercase().replace(Regex("[^a-z0-9_]"), "")

    // ───────── red ─────────

    private fun explain(code: Int, text: String): String {
        val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrDefault("")
        return when {
            msg.startsWith("EMAIL_EXISTS") -> "Ya hay una cuenta con ese correo"
            msg.startsWith("INVALID_EMAIL") -> "El correo no es válido"
            msg.startsWith("INVALID_LOGIN_CREDENTIALS") || msg.startsWith("EMAIL_NOT_FOUND") || msg.startsWith("INVALID_PASSWORD") -> "Usuario o contraseña incorrectos"
            msg.startsWith("WEAK_PASSWORD") -> "La contraseña debe tener al menos 6 caracteres"
            msg.startsWith("OPERATION_NOT_ALLOWED") -> "Falta activar «Correo/contraseña» en Firebase → Authentication"
            msg.startsWith("TOO_MANY_ATTEMPTS") -> "Demasiados intentos. Espera un poco y vuelve a probar"
            msg.startsWith("USER_DISABLED") -> "Esta cuenta está desactivada"
            code == 409 || msg.contains("ALREADY_EXISTS") -> "Ya existe"
            code == 403 || msg.contains("PERMISSION_DENIED") -> "Sin permiso (¿has publicado las reglas de Firestore?)"
            code == 404 -> "Falta crear la base de datos Firestore en Firebase"
            code == 429 -> "Se ha agotado la cuota gratuita de hoy. Inténtalo mañana"
            else -> "Error del servidor ($code)"
        }
    }

    private fun postText(url: String, body: String, contentType: String = "application/json", token: String? = null): String {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw SocialError(explain(code, text))
            return text
        } catch (e: SocialError) {
            throw e
        } catch (e: IOException) {
            throw SocialError("Sin conexión a internet")
        }
    }

    private fun post(url: String, body: JSONObject, token: String? = null) = JSONObject(postText(url, body.toString(), token = token))

    /** Devuelve la sesión con el token al día (lo renueva si le queda menos de un minuto). */
    @Synchronized
    private fun live(): Session {
        val s = session ?: throw SocialError("Inicia sesión primero")
        if (s.expiresAt - 60_000 < System.currentTimeMillis()) {
            val form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(s.refreshToken, "UTF-8")
            val r = JSONObject(postText("https://securetoken.googleapis.com/v1/token?key=$API_KEY", form, "application/x-www-form-urlencoded"))
            s.idToken = r.getString("id_token")
            s.refreshToken = r.getString("refresh_token")
            s.expiresAt = System.currentTimeMillis() + r.getString("expires_in").toLong() * 1000
            save(s)
        }
        return s
    }

    // ───────── Firestore (todo con POST: commit, batchGet y runQuery) ─────────

    private fun str(v: String) = JSONObject().put("stringValue", v)
    private fun strs(v: List<String>) = JSONObject().put("arrayValue", JSONObject().put("values", JSONArray(v.map { str(it) })))

    private fun write(path: String, fields: JSONObject, serverTime: String? = null, mustNotExist: Boolean = false): JSONObject {
        val w = JSONObject().put("update", JSONObject().put("name", "$DB/$path").put("fields", fields))
        if (serverTime != null) w.put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", serverTime).put("setToServerValue", "REQUEST_TIME")))
        if (mustNotExist) w.put("currentDocument", JSONObject().put("exists", false))
        return w
    }

    private fun delete(path: String) = JSONObject().put("delete", "$DB/$path")

    private fun commit(vararg writes: JSONObject) {
        post("$DOCS:commit", JSONObject().put("writes", JSONArray(writes.toList())), live().idToken)
    }

    private fun idOf(name: String) = name.substringAfterLast('/')
    private fun JSONObject.text(key: String) = optJSONObject("fields")?.optJSONObject(key)?.optString("stringValue", "") ?: ""
    private fun JSONObject.list(key: String) =
        optJSONObject("fields")?.optJSONObject(key)?.optJSONObject("arrayValue")?.optJSONArray("values")
            ?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("stringValue") } } ?: emptyList()
    private fun JSONObject.bool(key: String) = optJSONObject("fields")?.optJSONObject(key)?.optBoolean("booleanValue", false) ?: false
    private fun JSONObject.time(key: String): Long =
        optJSONObject("fields")?.optJSONObject(key)?.optString("timestampValue")?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    /** Documentos que existen, por id. */
    /** Milisegundos de diferencia entre el reloj del servidor y el de este dispositivo (se ajusta en cada lectura). */
    @Volatile var serverSkew = 0L
    fun serverNow() = System.currentTimeMillis() + serverSkew

    private fun getAll(collection: String, ids: List<String>): Map<String, JSONObject> {
        if (ids.isEmpty()) return emptyMap()
        val body = JSONObject().put("documents", JSONArray(ids.map { "$DB/$collection/$it" }))
        val arr = JSONArray(postText("$DOCS:batchGet", body.toString(), token = live().idToken))
        arr.optJSONObject(0)?.optString("readTime")?.takeIf { it.isNotEmpty() }?.let { serverSkew = Instant.parse(it).toEpochMilli() - System.currentTimeMillis() }
        val out = HashMap<String, JSONObject>()
        for (i in 0 until arr.length()) arr.getJSONObject(i).optJSONObject("found")?.let { out[idOf(it.getString("name"))] = it }
        return out
    }

    private fun filter(field: String, op: String, value: JSONObject) =
        JSONObject().put("fieldFilter", JSONObject().put("field", JSONObject().put("fieldPath", field)).put("op", op).put("value", value))

    private fun query(collection: String, where: JSONObject, limit: Int = 50): List<JSONObject> {
        val q = JSONObject().put("from", JSONArray().put(JSONObject().put("collectionId", collection))).put("where", where).put("limit", limit)
        val arr = JSONArray(postText("$DOCS:runQuery", JSONObject().put("structuredQuery", q).toString(), token = live().idToken))
        return (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optJSONObject("document") }
    }

    private fun profile(uid: String, d: JSONObject?) = Profile(uid, d?.text("username").orEmpty().ifEmpty { "?" }, d?.text("photo").orEmpty())

    // ───────── cuenta ─────────

    private fun enter(endpoint: String, email: String, pass: String, username: String): Session {
        val r = post(
            "$AUTH:$endpoint?key=$API_KEY",
            JSONObject().put("email", email).put("password", pass).put("returnSecureToken", true),
        )
        return Session(r.getString("localId"), username, r.getString("idToken"), r.getString("refreshToken"), System.currentTimeMillis() + r.getString("expiresIn").toLong() * 1000)
    }

    private fun cleanEmail(raw: String): String {
        val e = raw.trim().lowercase()
        if (!Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""").matches(e)) throw SocialError("Escribe un correo válido")
        return e
    }

    /** El correo solo sirve para entrar y recuperar la contraseña; vive en Firebase Auth y nadie más lo ve. */
    suspend fun signUp(rawEmail: String, rawName: String, pass: String) = withContext(Dispatchers.IO) {
        val email = cleanEmail(rawEmail)
        val name = cleanName(rawName)
        if (name.length !in 3..16) throw SocialError("El nombre de usuario debe tener entre 3 y 16 letras, números o _")
        if (pass.length < 6) throw SocialError("La contraseña debe tener al menos 6 caracteres")
        val s = enter("signUp", email, pass, name)
        save(s)
        try {
            commit(write("usernames/$name", JSONObject().put("uid", str(s.uid)), mustNotExist = true))
            commit(write("users/${s.uid}", JSONObject().put("username", str(name)).put("photo", str(""))))
        } catch (e: Exception) {
            runCatching { post("$AUTH:delete?key=$API_KEY", JSONObject().put("idToken", s.idToken)) }
            save(null)
            throw if (e is SocialError && e.message == "Ya existe") SocialError("Ese nombre de usuario ya está cogido") else e
        }
    }

    suspend fun signIn(rawEmail: String, pass: String) = withContext(Dispatchers.IO) {
        if (rawEmail.isBlank() || pass.isEmpty()) throw SocialError("Escribe tu correo y contraseña")
        val s = enter("signInWithPassword", cleanEmail(rawEmail), pass, "")
        save(s)
        try {
            val name = profile(s.uid, getAll("users", listOf(s.uid))[s.uid]).username
            save(Session(s.uid, name, s.idToken, s.refreshToken, s.expiresAt))
        } catch (e: Exception) {
            save(null)
            throw e
        }
        lastShownPoke = -1L
    }

    /** Firebase manda un correo con un enlace para elegir una contraseña nueva. */
    suspend fun resetPassword(rawEmail: String) = withContext(Dispatchers.IO) {
        val email = cleanEmail(rawEmail)
        try {
            post("$AUTH:sendOobCode?key=$API_KEY", JSONObject().put("requestType", "PASSWORD_RESET").put("email", email))
        } catch (e: SocialError) {
            if (e.message == "Usuario o contraseña incorrectos") throw SocialError("No hay ninguna cuenta con ese correo") else throw e
        }
    }

    fun signOut() {
        unread.clear()
        friendUidsCache = emptyList()
        save(null)
        hearts.clear()
        lastShownPoke = -1L
    }

    suspend fun myProfile(): Profile = withContext(Dispatchers.IO) {
        val s = live()
        profile(s.uid, getAll("users", listOf(s.uid))[s.uid])
    }

    /**
     * Guarda la miniatura (la que ven las listas) y, aparte, la foto en alta resolución para cuando se amplía.
     * Devuelve false si la grande no se pudo guardar (por ejemplo, si faltan las reglas nuevas): entonces se amplía la miniatura.
     */
    suspend fun setPhoto(thumb: String, full: String): Boolean = withContext(Dispatchers.IO) {
        val s = live()
        commit(write("users/${s.uid}", JSONObject().put("username", str(s.username)).put("photo", str(thumb))))
        try {
            commit(write("photos/${s.uid}", JSONObject().put("data", str(full))))
            fullCache["${s.uid}:${thumb.length}:${thumb.takeLast(16)}"] = full
            true
        } catch (e: SocialError) {
            false
        }
    }

    private val fullCache = HashMap<String, String>()

    /** Foto en alta resolución de una persona (null si no tiene). `thumb` identifica la versión para no volver a descargarla. */
    suspend fun fullPhoto(uid: String, thumb: String): String? = withContext(Dispatchers.IO) {
        val key = "$uid:${thumb.length}:${thumb.takeLast(16)}"
        fullCache[key]?.let { return@withContext it }
        val data = runCatching { getAll("photos", listOf(uid))[uid]?.text("data") }.getOrNull()
        if (!data.isNullOrEmpty()) fullCache[key] = data
        data?.takeIf { it.isNotEmpty() }
    }

    // ───────── amigos ─────────

    suspend fun search(raw: String): List<Profile> = withContext(Dispatchers.IO) {
        val q = cleanName(raw)
        if (q.isEmpty()) return@withContext emptyList()
        val where = JSONObject().put(
            "compositeFilter",
            JSONObject().put("op", "AND").put(
                "filters",
                JSONArray().put(filter("username", "GREATER_THAN_OR_EQUAL", str(q))).put(filter("username", "LESS_THAN_OR_EQUAL", str(q + ""))),
            ),
        )
        val me = live().uid
        query("users", where, 10).map { profile(idOf(it.getString("name")), it) }.filter { it.uid != me }
    }

    /** Amigos (con presencia y último toque mío), solicitudes recibidas y enviadas. */
    suspend fun overview(): Overview = withContext(Dispatchers.IO) {
        val me = live().uid
        val friendUids = query("friends", filter("members", "ARRAY_CONTAINS", str(me)))
            .map { d -> d.list("members").first { it != me } }
        val incomingUids = query("requests", filter("to", "EQUAL", str(me))).map { it.text("from") }
        val outgoing = query("requests", filter("from", "EQUAL", str(me))).map { it.text("to") }.toSet()
        // grupos (si todavía no se han publicado las reglas nuevas, simplemente no salen)
        var groups = emptyList<Group>()
        var invites = emptyList<Group>()
        try {
            groups = query("groups", filter("members", "ARRAY_CONTAINS", str(me))).map { groupOf(it) }.sortedBy { it.name.lowercase() }
            invites = query("groups", filter("invited", "ARRAY_CONTAINS", str(me))).map { groupOf(it) }
        } catch (e: SocialError) { /* sin grupos */ }
        val profiles = getAll("users", (friendUids + incomingUids + invites.map { it.owner }).distinct())
        pending = incomingUids.size + invites.size
        groupsCache = groups
        for (k in unread.keys.toList()) if (isGroup(k) && groups.none { "g:${it.id}" == k }) unread.remove(k)
        for (g in groups) unread["g:${g.id}"] = g.lastFrom.isNotEmpty() && g.lastFrom != me && g.lastAt > Prefs.chatRead("g:${g.id}")
        val presence = runCatching { getAll("presence", friendUids) }.getOrDefault(emptyMap())
        val pokes = runCatching { getAll("pokes", friendUids.map { "${it}_$me" }) }.getOrDefault(emptyMap())
        Overview(
            friendUids.map { FriendRow(profile(it, profiles[it]), presence[it]?.time("lastSeen") ?: 0L, pokes["${it}_$me"]?.time("at") ?: 0L, presence[it]?.bool("studying") ?: false) }
                .sortedBy { it.profile.username },
            incomingUids.map { profile(it, profiles[it]) },
            outgoing,
            groups,
            invites.map { GroupInvite(it, profile(it.owner, profiles[it.owner]).username) },
        )
    }

    suspend fun request(uid: String) = withContext(Dispatchers.IO) {
        val me = live().uid
        try {
            commit(write("requests/${me}_$uid", JSONObject().put("from", str(me)).put("to", str(uid)), mustNotExist = true))
        } catch (e: SocialError) {
            if (e.message == "Ya existe") throw SocialError("Ya le enviaste una solicitud") else throw e
        }
    }

    suspend fun accept(uid: String) = withContext(Dispatchers.IO) {
        val me = live().uid
        val members = listOf(me, uid).sorted()
        commit(
            write("friends/${members[0]}_${members[1]}", JSONObject().put("members", strs(members)), mustNotExist = true),
            delete("requests/${uid}_$me"),
        )
    }

    suspend fun decline(uid: String) = withContext(Dispatchers.IO) { commit(delete("requests/${uid}_${live().uid}")) }

    suspend fun removeFriend(uid: String) = withContext(Dispatchers.IO) {
        val members = listOf(live().uid, uid).sorted()
        commit(delete("friends/${members[0]}_${members[1]}"))
    }

    // ───────── presencia y toques ─────────

    /** Latido de presencia: cuándo estuviste por última vez y si ahora mismo estás estudiando. */
    suspend fun heartbeat(studying: Boolean = false) = withContext(Dispatchers.IO) {
        commit(write("presence/${live().uid}", JSONObject().put("studying", JSONObject().put("booleanValue", studying)), serverTime = "lastSeen"))
    }

    suspend fun poke(uid: String) = withContext(Dispatchers.IO) {
        val me = live().uid
        try {
            commit(write("pokes/${uid}_$me", JSONObject().put("from", str(me)).put("to", str(uid)), serverTime = "at"))
        } catch (e: SocialError) {
            if (e.message?.startsWith("Sin permiso") == true) throw SocialError("Ya le enviaste un ❤ hace menos de 15 minutos") else throw e
        }
    }

    /** Revisa si alguien te ha mandado un ❤ desde la última vez. La primera vez solo toma nota de los antiguos. */
    suspend fun checkPokes() = withContext(Dispatchers.IO) {
        val me = live().uid
        val times = query("pokes", filter("to", "EQUAL", str(me))).map { it.time("at") }
        val newest = times.maxOrNull() ?: 0L
        if (lastShownPoke < 0) {
            lastShownPoke = newest
            return@withContext
        }
        val fresh = times.count { it > lastShownPoke }
        lastShownPoke = maxOf(lastShownPoke, newest)
        repeat(fresh.coerceAtMost(3)) { showHeart() }
    }

    // ───────── documentos entre amigos ─────────

    const val MAX_SHARE = 10 * 1024 * 1024
    private const val CHUNK = 600_000

    /** Lo que hace la app con un documento recibido: lo guarda en la biblioteca. Lo pone `MainActivity`. */
    var receiver: (suspend (name: String, from: String, bytes: ByteArray) -> Boolean)? = null
    private val receiving = HashSet<String>()

    private fun int(v: Long) = JSONObject().put("integerValue", v.toString())
    private fun JSONObject.int(key: String) = optJSONObject("fields")?.optJSONObject(key)?.optString("integerValue")?.toIntOrNull() ?: 0

    /** Envía un documento a un amigo, troceado en partes de 600 KB (Firestore admite 1 MB por documento). */
    suspend fun sendPdf(toUid: String, name: String, file: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val me = live().uid
        val size = file.length()
        if (size > MAX_SHARE) throw SocialError("Este documento pesa más de 10 MB y no se puede enviar")
        val chunks = ((size + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
        val id = UUID.randomUUID().toString()
        commit(
            write(
                "shares/$id",
                JSONObject().put("from", str(me)).put("to", str(toUid)).put("name", str(name.take(150)))
                    .put("size", int(size)).put("chunks", int(chunks.toLong())).put("ready", JSONObject().put("booleanValue", false)),
                serverTime = "at",
            ),
        )
        try {
            file.inputStream().use { input ->
                val buf = ByteArray(CHUNK)
                for (i in 0 until chunks) {
                    var n = 0
                    while (n < CHUNK) { val r = input.read(buf, n, CHUNK - n); if (r < 0) break; n += r }
                    commit(write("shares/$id/parts/$i", JSONObject().put("data", str(Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)))))
                    onProgress((i + 1f) / chunks)
                }
            }
            // Solo ahora se marca como listo, para que el otro no lo baje a medias.
            val ready = JSONObject().put("update", JSONObject().put("name", "$DB/shares/$id").put("fields", JSONObject().put("ready", JSONObject().put("booleanValue", true))))
                .put("updateMask", JSONObject().put("fieldPaths", JSONArray().put("ready")))
            commit(ready)
        } catch (e: Exception) {
            runCatching { commit(*((0 until chunks).map { delete("shares/$id/parts/$it") } + delete("shares/$id")).toTypedArray()) }
            throw e
        }
    }

    /** Baja los documentos que te han enviado, los entrega a [receiver] y los borra de la nube. */
    suspend fun checkShares(
        recv: (suspend (name: String, from: String, bytes: ByteArray) -> Boolean)? = receiver,
    ) = withContext(Dispatchers.IO) {
        recv ?: return@withContext
        val me = live().uid
        val where = JSONObject().put(
            "compositeFilter",
            JSONObject().put("op", "AND").put("filters", JSONArray().put(filter("to", "EQUAL", str(me))).put(filter("ready", "EQUAL", JSONObject().put("booleanValue", true)))),
        )
        // De más antiguo a más nuevo: si te lo mandan dos veces, la última copia es la que se queda.
        for (d in query("shares", where, 5).sortedBy { it.time("at") }) {
            val id = idOf(d.getString("name"))
            if (!receiving.add(id)) continue
            try {
                val chunks = d.int("chunks")
                val from = d.text("from")
                val name = d.text("name").ifBlank { "Documento recibido" }
                val bytes = ByteArrayOutputStream()
                var i = 0
                while (i < chunks) {
                    val ids = (i until minOf(i + 3, chunks)).map { it.toString() }
                    val got = postText("$DOCS:batchGet", JSONObject().put("documents", JSONArray(ids.map { "$DB/shares/$id/parts/$it" })).toString(), token = live().idToken)
                    val parts = HashMap<String, JSONObject>()
                    val arr = JSONArray(got)
                    for (k in 0 until arr.length()) arr.getJSONObject(k).optJSONObject("found")?.let { parts[idOf(it.getString("name"))] = it }
                    for (p in ids) bytes.write(Base64.decode(parts[p]?.text("data") ?: throw SocialError("Faltan partes del documento"), Base64.DEFAULT))
                    i += ids.size
                }
                val sender = getAll("users", listOf(from))[from]?.text("username") ?: "un amigo"
                try {
                    recv(name, sender, bytes.toByteArray())
                } finally {
                    runCatching { commit(*((0 until chunks).map { delete("shares/$id/parts/$it") } + delete("shares/$id")).toTypedArray()) }
                }
            } finally {
                receiving.remove(id)
            }
        }
    }

    // ───────── chat ─────────

    private fun pairId(a: String, b: String) = if (a < b) "${a}_$b" else "${b}_$a"

    /** Un chat es con un amigo (se identifica por su uid) o con un grupo (clave «g:<id>»). */
    fun isGroup(key: String) = key.startsWith("g:")

    /** Dónde vive el chat en Firestore: chats/<pareja> o groups/<id>. */
    private fun chatBase(key: String) = if (isGroup(key)) "groups/${key.drop(2)}" else "chats/${pairId(live().uid, key)}"

    /** Clave con la que se recuerda hasta dónde has leído. */
    private fun chatKey(key: String, me: String) = if (isGroup(key)) key else pairId(me, key)

    /** Anota quién escribió el último mensaje y cuándo (en el documento del chat o en el del grupo). */
    private fun metaWrite(base: String, me: String): JSONObject =
        if (base.startsWith("groups/")) writeMask(base, JSONObject().put("lastFrom", str(me)), listOf("lastFrom"), "lastAt")
        else write(base, JSONObject().put("lastFrom", str(me)), serverTime = "lastAt")

    private fun queryUnder(parent: String, structured: JSONObject): List<JSONObject> {
        val arr = JSONArray(postText("$DOCS/$parent:runQuery", JSONObject().put("structuredQuery", structured).toString(), token = live().idToken))
        return (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optJSONObject("document") }
    }

    private fun messageOf(d: JSONObject, me: String) =
        ChatMessage(idOf(d.getString("name")), d.text("from") == me, d.text("text"), d.time("at"), d.text("kind"), d.int("dur").toLong(), d.int("mins"), d.text("rq"), d.text("rf"), d.text("game"), d.bool("del"), d.text("from"))

    private fun orderByAt(direction: String) =
        JSONArray().put(JSONObject().put("field", JSONObject().put("fieldPath", "at")).put("direction", direction))

    /** Mira si algún amigo te ha escrito algo que no has leído (una lectura por amigo). */
    suspend fun checkChats() = withContext(Dispatchers.IO) {
        val me = live().uid
        if (friendUidsCache.isEmpty() || System.currentTimeMillis() - friendsFetchedAt > 60_000) {
            friendUidsCache = query("friends", filter("members", "ARRAY_CONTAINS", str(me))).map { d -> d.list("members").first { it != me } }
            friendsFetchedAt = System.currentTimeMillis()
        }
        if (friendUidsCache.isNotEmpty()) {
            val metas = getAll("chats", friendUidsCache.map { pairId(me, it) })
            for (u in friendUidsCache) {
                val pair = pairId(me, u)
                val meta = metas[pair]
                unread[u] = meta != null && meta.text("lastFrom") != me && meta.time("lastAt") > Prefs.chatRead(pair)
            }
        }
        // grupos, invitaciones a grupos y solicitudes de amistad
        try {
            val mine = query("groups", filter("members", "ARRAY_CONTAINS", str(me))).map { groupOf(it) }
            val invites = query("groups", filter("invited", "ARRAY_CONTAINS", str(me))).map { groupOf(it) }
            val reqs = query("requests", filter("to", "EQUAL", str(me)))
            for (k in unread.keys.toList()) if (isGroup(k) && mine.none { "g:${it.id}" == k }) unread.remove(k)
            for (g in mine) unread["g:${g.id}"] = g.lastFrom.isNotEmpty() && g.lastFrom != me && g.lastAt > Prefs.chatRead("g:${g.id}")
            groupsCache = mine
            groupInvitesCache = invites
            pending = invites.size + reqs.size
        } catch (e: SocialError) { /* sin reglas de grupos: no pasa nada */ }
    }

    private var groupInvitesCache: List<Group> = emptyList()

    class ChatAlert(val uid: String, val name: String, val preview: String)

    /**
     * Chats con mensajes sin leer de los que todavía no se ha avisado. Solo se avisa del primer mensaje (o invitación a jugar) sin leer
     * de cada chat: hasta que se lee no se vuelve a avisar por los siguientes.
     */
    suspend fun newChatAlerts(): List<ChatAlert> = withContext(Dispatchers.IO) {
        checkChats()
        val me = live().uid
        val out = ArrayList<ChatAlert>()
        for (u in friendUidsCache) {
            val pair = pairId(me, u)
            if (unread[u] != true || Prefs.chatNotified(pair)) continue
            val q = JSONObject().put("from", JSONArray().put(JSONObject().put("collectionId", "messages")))
                .put("where", filter("at", "GREATER_THAN", JSONObject().put("timestampValue", Instant.ofEpochMilli(Prefs.chatRead(pair)).toString())))
                .put("orderBy", orderByAt("ASCENDING")).put("limit", 5)
            val first = queryUnder("chats/$pair", q).map { messageOf(it, me) }.firstOrNull { !it.mine } ?: continue
            val name = getAll("users", listOf(u))[u]?.text("username") ?: "Un amigo"
            val preview = when (first.kind) {
                "chess" -> "Te invita a jugar al Evadrez"
                "audio" -> "Te ha enviado un audio"
                "image" -> "Te ha enviado una foto"
                else -> first.text.take(120)
            }
            Prefs.setChatNotified(pair, true)
            out.add(ChatAlert(u, name, preview))
        }
        // mensajes nuevos en grupos y grupos a los que te invitan
        for (g in groupsCache) {
            val key = "g:${g.id}"
            if (unread[key] != true || Prefs.chatNotified(key)) continue
            val q = JSONObject().put("from", JSONArray().put(JSONObject().put("collectionId", "messages")))
                .put("where", filter("at", "GREATER_THAN", JSONObject().put("timestampValue", Instant.ofEpochMilli(Prefs.chatRead(key)).toString())))
                .put("orderBy", orderByAt("ASCENDING")).put("limit", 5)
            val first = queryUnder("groups/${g.id}", q).map { messageOf(it, me) }.firstOrNull { !it.mine } ?: continue
            val who = getAll("users", listOf(first.from))[first.from]?.text("username") ?: "Alguien"
            val what = when (first.kind) {
                "audio" -> "ha enviado un audio"
                "image" -> "ha enviado una foto"
                else -> first.text.take(120)
            }
            Prefs.setChatNotified(key, true)
            out.add(ChatAlert(key, g.name, if (first.kind.isEmpty()) "$who: $what" else "$who $what"))
        }
        for (g in groupInvitesCache) {
            val key = "gi:${g.id}"
            if (Prefs.chatNotified(key)) continue
            val owner = getAll("users", listOf(g.owner))[g.owner]?.text("username") ?: "Un amigo"
            Prefs.setChatNotified(key, true)
            out.add(ChatAlert(key, g.name, "$owner te invita a unirte al grupo"))
        }
        out
    }

    /** Mensajes del chat: los 50 últimos si `after` es 0, o solo los posteriores a `after` (ms). */
    suspend fun chatMessages(friendUid: String, after: Long): List<ChatMessage> = withContext(Dispatchers.IO) {
        val me = live().uid
        val path = chatBase(friendUid)
        val base = JSONObject().put("from", JSONArray().put(JSONObject().put("collectionId", "messages")))
        val q = if (after <= 0) base.put("orderBy", orderByAt("DESCENDING")).put("limit", 50)
        else base.put("where", filter("at", "GREATER_THAN", JSONObject().put("timestampValue", Instant.ofEpochMilli(after).toString())))
            .put("orderBy", orderByAt("ASCENDING")).put("limit", 100)
        val list = queryUnder(path, q).map { d -> messageOf(d, me) }
        if (after > 0) list else list.reversed()
    }

    /** Los 50 mensajes anteriores a `before` (ms): se piden al subir en la conversación, así el historial no tiene límite. */
    suspend fun chatOlder(friendUid: String, before: Long): List<ChatMessage> = withContext(Dispatchers.IO) {
        val me = live().uid
        val path = chatBase(friendUid)
        val q = JSONObject().put("from", JSONArray().put(JSONObject().put("collectionId", "messages")))
            .put("where", filter("at", "LESS_THAN", JSONObject().put("timestampValue", Instant.ofEpochMilli(before).toString())))
            .put("orderBy", orderByAt("DESCENDING")).put("limit", 50)
        queryUnder(path, q).map { d -> messageOf(d, me) }.reversed()
    }

    /** `replyTo` = mensaje al que se responde: se guarda un extracto y su autor para enseñarlo encima. */
    suspend fun sendMessage(friendUid: String, text: String, replyTo: ChatMessage? = null, replyPreview: String = "") = withContext(Dispatchers.IO) {
        val me = live().uid
        val base = chatBase(friendUid)
        val t = text.trim().take(5000)
        if (t.isEmpty()) return@withContext
        commit(
            write(
                "$base/messages/${UUID.randomUUID()}",
                JSONObject().put("from", str(me)).put("text", str(t)).also {
                    if (replyTo != null) it.put("rid", str(replyTo.id)).put("rq", str(replyPreview.take(120))).put("rf", str(replyTo.from.ifEmpty { if (replyTo.mine) me else friendUid }))
                },
                serverTime = "at",
            ),
            metaWrite(base, me),
        )
    }

    /** Borra un mensaje propio para los dos: queda vacío y marcado, y si llevaba audio o foto se borra también su contenido. */
    suspend fun deleteMessage(friendUid: String, m: ChatMessage) = withContext(Dispatchers.IO) {
        val base = chatBase(friendUid)
        val writes = arrayListOf(
            writeMask("$base/messages/${m.id}", JSONObject().put("del", bool(true)).put("text", str("")).put("rq", str("")), listOf("del", "text", "rq"), "delAt"),
        )
        if (m.kind == "audio" || m.kind == "image") writes.add(delete("$base/audio/${m.id}"))
        commit(*writes.toTypedArray())
    }

    /** Ids de los mensajes borrados desde `since` (ms) y la hora del último: el otro lado los ve sin releer todo el chat. */
    suspend fun chatDeletions(friendUid: String, since: Long): Pair<List<String>, Long> = withContext(Dispatchers.IO) {
        val path = chatBase(friendUid)
        val q = JSONObject().put("from", JSONArray().put(JSONObject().put("collectionId", "messages")))
            .put("where", filter("delAt", "GREATER_THAN", JSONObject().put("timestampValue", Instant.ofEpochMilli(since).toString())))
            .put("orderBy", JSONArray().put(JSONObject().put("field", JSONObject().put("fieldPath", "delAt")).put("direction", "ASCENDING")))
            .put("limit", 50)
        val docs = queryUnder(path, q)
        docs.map { idOf(it.getString("name")) } to (docs.maxOfOrNull { it.time("delAt") } ?: 0L)
    }

    /** Manda un audio: el contenido va en su propio documento y el mensaje solo lleva la duración (así el chat carga ligero). */
    suspend fun sendAudio(friendUid: String, file: File, durMs: Long) = withContext(Dispatchers.IO) {
        val me = live().uid
        val base = chatBase(friendUid)
        val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        if (b64.length > 700_000) throw SocialError("El audio es demasiado largo")
        val id = UUID.randomUUID().toString()
        commit(
            write("$base/audio/$id", JSONObject().put("from", str(me)).put("data", str(b64))),
            write(
                "$base/messages/$id",
                JSONObject().put("from", str(me)).put("text", str("")).put("kind", str("audio")).put("dur", int(durMs)).put("mime", str("audio/mp4")),
                serverTime = "at",
            ),
            metaWrite(base, me),
        )
    }

    /** Manda una foto (JPEG ya reducido): el contenido va en el documento `audio/{id}` y el mensaje solo avisa de que es una foto. */
    suspend fun sendPhoto(friendUid: String, jpeg: ByteArray) = withContext(Dispatchers.IO) {
        val me = live().uid
        val base = chatBase(friendUid)
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        if (b64.length > 700_000) throw SocialError("La foto es demasiado grande")
        val id = UUID.randomUUID().toString()
        commit(
            write("$base/audio/$id", JSONObject().put("from", str(me)).put("data", str(b64))),
            write(
                "$base/messages/$id",
                JSONObject().put("from", str(me)).put("text", str("")).put("kind", str("image")).put("mime", str("image/jpeg")),
                serverTime = "at",
            ),
            metaWrite(base, me),
        )
    }

    /** Descarga una foto del chat a un archivo (si ya está descargada, lo reutiliza). */
    suspend fun photoFile(ctx: android.content.Context, friendUid: String, msgId: String): File = withContext(Dispatchers.IO) {
        val f = File(ctx.cacheDir, "img-$msgId.jpg")
        if (f.exists() && f.length() > 0) return@withContext f
        val data = getAll("${chatBase(friendUid)}/audio", listOf(msgId))[msgId]?.text("data").orEmpty()
        if (data.isEmpty()) throw SocialError("No se pudo cargar la foto")
        f.writeBytes(Base64.decode(data, Base64.DEFAULT))
        f
    }

    /** Descarga un audio a un archivo temporal (si ya está descargado, lo reutiliza). */
    suspend fun audioFile(ctx: android.content.Context, friendUid: String, msgId: String): File = withContext(Dispatchers.IO) {
        val f = File(ctx.cacheDir, "aud-$msgId.m4a")
        if (f.exists() && f.length() > 0) return@withContext f
        val data = getAll("${chatBase(friendUid)}/audio", listOf(msgId))[msgId]?.text("data").orEmpty()
        if (data.isEmpty()) throw SocialError("No se pudo cargar el audio")
        f.writeBytes(Base64.decode(data, Base64.DEFAULT))
        f
    }

    // ───────── Evapoly: salas en la nube ─────────

    /** `players` = asientos por orden de llegada (el primero crea la sala); `tok` = ficha de cada jugador; `acts` = acciones de la partida. */
    class PolyRoom(
        val id: String, val host: String, val invited: List<String>, val players: List<String>, val tok: Map<String, Int>,
        val money: Int, val goBonus: Boolean, val auction: Boolean, val pot: Boolean, val dblBonus: Boolean, val rails: Boolean, val ownerFree: Boolean, val shop: Boolean, val events: Boolean, val goals: Boolean, val status: String, val seed: Int, val acts: List<String>,
    )

    private fun roomOf(d: JSONObject?, id: String): PolyRoom? = d?.let {
        val tf = it.optJSONObject("fields")?.optJSONObject("tok")?.optJSONObject("mapValue")?.optJSONObject("fields")
        val tok = HashMap<String, Int>()
        if (tf != null) for (k in tf.keys()) tok[k] = tf.getJSONObject(k).optString("integerValue").toIntOrNull() ?: 0
        PolyRoom(
            id, it.text("host"), it.list("invited"), it.list("players"), tok, it.int("money").takeIf { m -> m > 0 } ?: 1500,
            it.bool("goBonus"), it.bool("auction"), it.optJSONObject("fields")?.has("pot") != true || it.bool("pot"), it.bool("dblBonus"), it.bool("rails"), it.bool("ownerFree"), it.bool("shop"), it.bool("events"), it.bool("goals"), it.text("status"), it.int("seed"), it.list("acts"),
        )
    }

    private fun bool(v: Boolean) = JSONObject().put("booleanValue", v)
    private fun tokMap(uid: String, tok: Int) = JSONObject().put("mapValue", JSONObject().put("fields", JSONObject().put(uid, int(tok.toLong()))))

    suspend fun polyRoom(id: String): PolyRoom? = withContext(Dispatchers.IO) { roomOf(getAll("rooms", listOf(id))[id], id) }

    /** Crea la sala y manda la invitación al chat del amigo (el id de la sala es el del mensaje). */
    suspend fun createPoly(friendUid: String): String = withContext(Dispatchers.IO) {
        val me = live().uid
        val pair = pairId(me, friendUid)
        val id = UUID.randomUUID().toString()
        commit(
            write(
                "rooms/$id",
                JSONObject().put("host", str(me)).put("invited", strs(listOf(friendUid))).put("players", strs(listOf(me))).put("tok", tokMap(me, 0))
                    .put("money", int(1500)).put("goBonus", bool(false)).put("auction", bool(true)).put("pot", bool(true)).put("dblBonus", bool(false)).put("rails", bool(false)).put("ownerFree", bool(false)).put("shop", bool(false)).put("events", bool(false)).put("goals", bool(false)).put("status", str("lobby")).put("seed", int(0)).put("acts", strs(emptyList())),
                serverTime = "at",
            ),
            write("chats/$pair/messages/$id", JSONObject().put("from", str(me)).put("text", str("")).put("kind", str("poly")).put("game", str(id)), serverTime = "at"),
            write("chats/$pair", JSONObject().put("lastFrom", str(me)), serverTime = "lastAt"),
        )
        id
    }

    suspend fun polyInvite(room: PolyRoom, friendUid: String) = withContext(Dispatchers.IO) {
        val me = live().uid
        val pair = pairId(me, friendUid)
        commit(
            writeMask("rooms/${room.id}", JSONObject().put("invited", strs(room.invited + friendUid)), listOf("invited")),
            write("chats/$pair/messages/${UUID.randomUUID()}", JSONObject().put("from", str(me)).put("text", str("")).put("kind", str("poly")).put("game", str(room.id)), serverTime = "at"),
            write("chats/$pair", JSONObject().put("lastFrom", str(me)), serverTime = "lastAt"),
        )
    }

    suspend fun polyJoin(room: PolyRoom, tok: Int) = withContext(Dispatchers.IO) {
        val me = live().uid
        commit(writeMask("rooms/${room.id}", JSONObject().put("players", strs(room.players + me)).put("tok", tokMap(me, tok)), listOf("players", "tok.`$me`")))
    }

    suspend fun polyLeave(room: PolyRoom) = withContext(Dispatchers.IO) {
        val me = live().uid
        commit(writeMask("rooms/${room.id}", JSONObject().put("players", strs(room.players.filter { it != me })), listOf("players")))
    }

    /** Reglas que elige el anfitrión en la sala de espera. */
    class RoomCfg(
        val money: Int, val goBonus: Boolean, val auction: Boolean, val pot: Boolean, val dblBonus: Boolean, val rails: Boolean, val ownerFree: Boolean, val shop: Boolean, val events: Boolean, val goals: Boolean,
    )

    suspend fun polyCfg(room: PolyRoom, c: RoomCfg) = withContext(Dispatchers.IO) {
        commit(
            writeMask(
                "rooms/${room.id}",
                JSONObject().put("money", int(c.money.toLong())).put("goBonus", bool(c.goBonus)).put("auction", bool(c.auction)).put("pot", bool(c.pot))
                    .put("dblBonus", bool(c.dblBonus)).put("rails", bool(c.rails)).put("ownerFree", bool(c.ownerFree)).put("shop", bool(c.shop)).put("events", bool(c.events)).put("goals", bool(c.goals)),
                listOf("money", "goBonus", "auction", "pot", "dblBonus", "rails", "ownerFree", "shop", "events", "goals"),
            ),
        )
    }

    suspend fun polyTok(room: PolyRoom, tok: Int) = withContext(Dispatchers.IO) {
        val me = live().uid
        commit(writeMask("rooms/${room.id}", JSONObject().put("tok", tokMap(me, tok)), listOf("tok.`$me`")))
    }

    suspend fun polyStart(room: PolyRoom, seed: Int) = withContext(Dispatchers.IO) {
        commit(writeMask("rooms/${room.id}", JSONObject().put("status", str("playing")).put("seed", int(seed.toLong())), listOf("status", "seed")))
    }

    /** Manda la lista completa de acciones (una más que la de la nube); `over` marca la partida como terminada. */
    suspend fun polyAct(id: String, acts: List<String>, over: Boolean) = withContext(Dispatchers.IO) {
        val f = JSONObject().put("acts", strs(acts))
        val mask = arrayListOf("acts")
        if (over) { f.put("status", str("finished")); mask.add("status") }
        commit(writeMask("rooms/$id", f, mask))
    }

    suspend fun polyRemove(id: String) = withContext(Dispatchers.IO) { commit(delete("rooms/$id")) }

    // ───────── grupos ─────────

    const val MAX_GROUP = 10

    private fun groupOf(d: JSONObject) =
        Group(idOf(d.getString("name")), d.text("name"), d.text("desc"), d.text("photo"), d.text("owner"), d.list("members"), d.list("invited"), d.text("lastFrom"), d.time("lastAt"))

    /** Cambia listas de un documento sin pisar lo que hayan hecho otros a la vez: añade o quita uids de una lista (`op` = appendMissingElements | removeAllFromArray). */
    private fun arrayOps(path: String, ops: List<Triple<String, String, List<String>>>): JSONObject {
        val tr = JSONArray()
        ops.forEach { (field, op, uids) -> tr.put(JSONObject().put("fieldPath", field).put(op, JSONObject().put("values", JSONArray(uids.map { str(it) })))) }
        return JSONObject().put("update", JSONObject().put("name", "$DB/$path").put("fields", JSONObject()))
            .put("updateMask", JSONObject().put("fieldPaths", JSONArray()))
            .put("currentDocument", JSONObject().put("exists", true))
            .put("updateTransforms", tr)
    }

    /** Crea el grupo con quien lo crea dentro y manda la invitación a los amigos elegidos (hasta 9). Devuelve el id. */
    suspend fun createGroup(name: String, desc: String, photo: String, invite: List<String>): String = withContext(Dispatchers.IO) {
        val me = live().uid
        val n = name.trim().take(40)
        if (n.isEmpty()) throw SocialError("Ponle un nombre al grupo")
        if (invite.size > MAX_GROUP - 1) throw SocialError("Un grupo puede tener hasta $MAX_GROUP personas")
        val id = UUID.randomUUID().toString()
        commit(
            write(
                "groups/$id",
                JSONObject().put("owner", str(me)).put("name", str(n)).put("desc", str(desc.trim().take(200))).put("photo", str(photo))
                    .put("members", strs(listOf(me))).put("invited", strs(invite)),
                serverTime = "at",
            ),
        )
        id
    }

    suspend fun getGroup(id: String): Group? = withContext(Dispatchers.IO) { runCatching { getAll("groups", listOf(id))[id]?.let { groupOf(it) } }.getOrNull() }

    suspend fun acceptGroup(id: String) = withContext(Dispatchers.IO) {
        val me = live().uid
        commit(arrayOps("groups/$id", listOf(Triple("members", "appendMissingElements", listOf(me)), Triple("invited", "removeAllFromArray", listOf(me)))))
    }

    suspend fun declineGroup(id: String) = withContext(Dispatchers.IO) {
        val me = live().uid
        commit(arrayOps("groups/$id", listOf(Triple("invited", "removeAllFromArray", listOf(me)))))
    }

    suspend fun leaveGroup(id: String) = withContext(Dispatchers.IO) {
        val me = live().uid
        commit(arrayOps("groups/$id", listOf(Triple("members", "removeAllFromArray", listOf(me)))))
    }

    suspend fun kickFromGroup(id: String, uid: String) = withContext(Dispatchers.IO) {
        commit(arrayOps("groups/$id", listOf(Triple("members", "removeAllFromArray", listOf(uid)))))
    }

    suspend fun inviteToGroup(id: String, uids: List<String>) = withContext(Dispatchers.IO) {
        commit(arrayOps("groups/$id", listOf(Triple("invited", "appendMissingElements", uids))))
    }

    suspend fun deleteGroup(id: String) = withContext(Dispatchers.IO) { commit(delete("groups/$id")) }

    suspend fun editGroup(id: String, name: String, desc: String, photo: String) = withContext(Dispatchers.IO) {
        val n = name.trim().take(40)
        if (n.isEmpty()) throw SocialError("Ponle un nombre al grupo")
        commit(writeMask("groups/$id", JSONObject().put("name", str(n)).put("desc", str(desc.trim().take(200))).put("photo", str(photo)), listOf("name", "desc", "photo")))
    }

    suspend fun profilesOf(uids: List<String>): Map<String, Profile> = withContext(Dispatchers.IO) {
        val d = getAll("users", uids)
        uids.associateWith { profile(it, d[it]) }
    }

    // ───────── ajedrez: partidas en la nube ─────────

    /** `white`/`black` = uid de quien lleva cada color; `moves` = jugadas («e2e4»); `wms`/`bms` = tiempo que le queda a cada uno en `lastAt`. */
    data class ChessGame(
        val id: String, val creator: String, val joiner: String, val white: String, val black: String, val status: String, val mins: Int,
        val moves: List<String>, val wms: Long, val bms: Long, val lastAt: Long, val result: String, val reason: String, val drawOffer: String,
    )

    private fun gameOf(d: JSONObject?, id: String) = d?.let {
        ChessGame(
            id, it.text("creator"), it.text("joiner"), it.text("white"), it.text("black"), it.text("status"), it.int("mins"),
            it.list("moves"), it.int("wms").toLong(), it.int("bms").toLong(), it.time("lastAt"), it.text("result"), it.text("reason"), it.text("drawOffer"),
        )
    }

    private fun writeMask(path: String, fields: JSONObject, mask: List<String>, serverTime: String? = null): JSONObject {
        val w = JSONObject().put("update", JSONObject().put("name", "$DB/$path").put("fields", fields))
            .put("updateMask", JSONObject().put("fieldPaths", JSONArray(mask)))
            .put("currentDocument", JSONObject().put("exists", true))
        if (serverTime != null) w.put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", serverTime).put("setToServerValue", "REQUEST_TIME")))
        return w
    }

    suspend fun chessGame(friendUid: String, id: String): ChessGame? = withContext(Dispatchers.IO) {
        val pair = pairId(live().uid, friendUid)
        gameOf(getAll("chats/$pair/games", listOf(id))[id], id)
    }

    /** Crea la partida y manda al chat la invitación (el id de la partida es el del mensaje). `mins` = minutos por jugador, 0 = sin tiempo. */
    suspend fun createChess(friendUid: String, mins: Int): String = withContext(Dispatchers.IO) {
        val me = live().uid
        val pair = pairId(me, friendUid)
        val id = UUID.randomUUID().toString()
        commit(
            write(
                "chats/$pair/games/$id",
                JSONObject().put("creator", str(me)).put("joiner", str("")).put("white", str("")).put("black", str("")).put("status", str("waiting"))
                    .put("mins", int(mins.toLong())).put("moves", strs(emptyList())).put("wms", int(0)).put("bms", int(0))
                    .put("result", str("")).put("reason", str("")).put("drawOffer", str("")),
                serverTime = "lastAt",
            ),
            write(
                "chats/$pair/messages/$id",
                JSONObject().put("from", str(me)).put("text", str("")).put("kind", str("chess")).put("game", str(id)).put("mins", int(mins.toLong())),
                serverTime = "at",
            ),
            write("chats/$pair", JSONObject().put("lastFrom", str(me)), serverTime = "lastAt"),
        )
        id
    }

    /** Unirse a una partida: los colores se reparten al azar entre los dos. */
    suspend fun joinChess(friendUid: String, g: ChessGame) = withContext(Dispatchers.IO) {
        val me = live().uid
        val pair = pairId(me, friendUid)
        val iAmWhite = Random.nextBoolean()
        val ms = g.mins * 60_000L
        commit(
            writeMask(
                "chats/$pair/games/${g.id}",
                JSONObject().put("status", str("playing")).put("joiner", str(me)).put("white", str(if (iAmWhite) me else g.creator))
                    .put("black", str(if (iAmWhite) g.creator else me)).put("wms", int(ms)).put("bms", int(ms)),
                listOf("status", "joiner", "white", "black", "wms", "bms"), "lastAt",
            ),
        )
    }

    /** Manda una jugada: la lista completa de jugadas, los relojes y, si la partida acaba con ella, el resultado. */
    suspend fun chessMove(friendUid: String, id: String, moves: List<String>, wms: Long, bms: Long, endResult: String?, endReason: String?) = withContext(Dispatchers.IO) {
        val pair = pairId(live().uid, friendUid)
        val fields = JSONObject().put("moves", strs(moves)).put("wms", int(wms)).put("bms", int(bms)).put("drawOffer", str(""))
        val mask = arrayListOf("moves", "wms", "bms", "drawOffer")
        if (endResult != null && endReason != null) {
            fields.put("status", str("finished")).put("result", str(endResult)).put("reason", str(endReason))
            mask.addAll(listOf("status", "result", "reason"))
        }
        commit(writeMask("chats/$pair/games/$id", fields, mask, "lastAt"))
    }

    suspend fun chessEnd(friendUid: String, id: String, result: String, reason: String) = withContext(Dispatchers.IO) {
        val pair = pairId(live().uid, friendUid)
        commit(
            writeMask(
                "chats/$pair/games/$id",
                JSONObject().put("status", str("finished")).put("result", str(result)).put("reason", str(reason)).put("drawOffer", str("")),
                listOf("status", "result", "reason", "drawOffer"),
            ),
        )
    }

    suspend fun chessOffer(friendUid: String, id: String, uidOrEmpty: String) = withContext(Dispatchers.IO) {
        val pair = pairId(live().uid, friendUid)
        commit(writeMask("chats/$pair/games/$id", JSONObject().put("drawOffer", str(uidOrEmpty)), listOf("drawOffer")))
    }

    fun markRead(friendUid: String, at: Long) {
        val me = session?.uid ?: return
        Prefs.setChatRead(chatKey(friendUid, me), at)
        Prefs.setChatNotified(chatKey(friendUid, me), false)
        unread[friendUid] = false
    }

    private fun showHeart() {
        hearts.add(Heart(nextHeart++, 0.12f + 0.76f * Math.random().toFloat(), 0.15f + 0.6f * Math.random().toFloat()))
    }
}
