package com.evability.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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
    class FriendRow(val profile: Profile, val lastSeen: Long, val lastPokeAt: Long)
    class Overview(val friends: List<FriendRow>, val incoming: List<Profile>, val outgoing: Set<String>)
    class Heart(val id: Long, val x: Float, val y: Float)

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
    private fun JSONObject.time(key: String): Long =
        optJSONObject("fields")?.optJSONObject(key)?.optString("timestampValue")?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    /** Documentos que existen, por id. */
    private fun getAll(collection: String, ids: List<String>): Map<String, JSONObject> {
        if (ids.isEmpty()) return emptyMap()
        val body = JSONObject().put("documents", JSONArray(ids.map { "$DB/$collection/$it" }))
        val arr = JSONArray(postText("$DOCS:batchGet", body.toString(), token = live().idToken))
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
        save(null)
        hearts.clear()
        lastShownPoke = -1L
    }

    suspend fun myProfile(): Profile = withContext(Dispatchers.IO) {
        val s = live()
        profile(s.uid, getAll("users", listOf(s.uid))[s.uid])
    }

    suspend fun setPhoto(base64: String) = withContext(Dispatchers.IO) {
        val s = live()
        commit(write("users/${s.uid}", JSONObject().put("username", str(s.username)).put("photo", str(base64))))
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
        val profiles = getAll("users", (friendUids + incomingUids).distinct())
        val presence = runCatching { getAll("presence", friendUids) }.getOrDefault(emptyMap())
        val pokes = runCatching { getAll("pokes", friendUids.map { "${it}_$me" }) }.getOrDefault(emptyMap())
        Overview(
            friendUids.map { FriendRow(profile(it, profiles[it]), presence[it]?.time("lastSeen") ?: 0L, pokes["${it}_$me"]?.time("at") ?: 0L) }
                .sortedBy { it.profile.username },
            incomingUids.map { profile(it, profiles[it]) },
            outgoing,
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

    suspend fun heartbeat() = withContext(Dispatchers.IO) {
        commit(write("presence/${live().uid}", JSONObject(), serverTime = "lastSeen"))
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

    private fun showHeart() {
        hearts.add(Heart(nextHeart++, 0.12f + 0.76f * Math.random().toFloat(), 0.15f + 0.6f * Math.random().toFloat()))
    }
}
