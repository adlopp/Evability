package com.evability.notes

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Hundir la flota: reglas y formatos. Es la misma lógica que `fleet-engine.js` (los dos dispositivos se leen entre sí, así que los
 * formatos tienen que ser idénticos):
 *  · Flota: los 5 barcos por orden (5, 4, 3, 3, 2) como «NNd» separados por comas; NN = casilla de la proa (fila × 10 + columna) y
 *    d = 'h' (hacia la derecha) o 'v' (hacia abajo). Ej.: «00h,10h,20h,30h,40h».
 *  · Compromiso: sha256(flota + ":" + sal) en hexadecimal; la flota y la sal solo se guardan en el dispositivo y se revelan al terminar.
 *  · Disparos: «NN» por disparo. Respuestas: un carácter por disparo del rival: '0' agua, '1' tocado, '2'–'5' hundido (tamaño del barco).
 */
object Fleet {
    const val SIZE = 10
    val LENS = intArrayOf(5, 4, 3, 3, 2)
    val NAMES = listOf("Portaaviones", "Acorazado", "Crucero", "Submarino", "Destructor")

    class Ship(val cell: Int, val dir: Char)

    fun pad2(n: Int) = n.toString().padStart(2, '0')

    fun cellsOf(s: Ship, i: Int): List<Int> = (0 until LENS[i]).map { if (s.dir == 'h') s.cell + it else s.cell + SIZE * it }

    fun fits(s: Ship, i: Int): Boolean {
        if (s.cell !in 0 until SIZE * SIZE || (s.dir != 'h' && s.dir != 'v')) return false
        return if (s.dir == 'h') s.cell % SIZE + LENS[i] <= SIZE else s.cell / SIZE + LENS[i] <= SIZE
    }

    private fun near(a: Int, b: Int) = kotlin.math.abs(a / SIZE - b / SIZE) <= 1 && kotlin.math.abs(a % SIZE - b % SIZE) <= 1

    /** ¿Cabe el barco `i` en `s` sin salirse, sin pisar a los demás y (si `adj` es falso) sin tocarlos? */
    fun canPlace(ships: List<Ship?>, i: Int, s: Ship, adj: Boolean): Boolean {
        if (!fits(s, i)) return false
        val mine = cellsOf(s, i)
        for (j in ships.indices) {
            val o = ships[j] ?: continue
            if (j == i) continue
            for (c in cellsOf(o, j)) for (d in mine) { if (c == d) return false; if (!adj && near(c, d)) return false }
        }
        return true
    }

    fun validFleet(ships: List<Ship?>, adj: Boolean): Boolean = ships.size == LENS.size && ships.all { it != null } && ships.indices.all { canPlace(ships, it, ships[it]!!, adj) }

    fun packFleet(ships: List<Ship>) = ships.joinToString(",") { pad2(it.cell) + it.dir }

    fun parseFleet(f: String): List<Ship>? {
        val parts = f.split(",")
        if (parts.size != LENS.size) return null
        return parts.map { p ->
            if (!Regex("^\\d\\d[hv]$").matches(p)) return null
            Ship(p.substring(0, 2).toInt(), p[2])
        }
    }

    fun randomFleet(adj: Boolean): List<Ship> {
        val rnd = java.util.Random()
        repeat(500) {
            val ships = arrayOfNulls<Ship>(LENS.size)
            var ok = true
            for (i in LENS.indices) {
                var placed = false
                var t = 0
                while (t++ < 200 && !placed) {
                    val s = Ship(rnd.nextInt(SIZE * SIZE), if (rnd.nextBoolean()) 'h' else 'v')
                    if (canPlace(ships.toList(), i, s, adj)) { ships[i] = s; placed = true }
                }
                if (!placed) { ok = false; break }
            }
            if (ok) return ships.map { it!! }
        }
        return LENS.indices.map { Ship(it * 20, 'h') }
    }

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun newSalt(): String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    fun commitOf(fleet: String, salt: String) = sha256("$fleet:$salt")

    /** Respuesta a cada disparo recibido, por orden. */
    fun answers(ships: List<Ship>, cells: List<Int>): String {
        val owner = HashMap<Int, Int>()
        ships.forEachIndexed { i, s -> cellsOf(s, i).forEach { owner[it] = i } }
        val seen = List(ships.size) { HashSet<Int>() }
        val out = StringBuilder()
        for (c in cells) {
            val i = owner[c]
            if (i == null) { out.append('0'); continue }
            seen[i].add(c)
            out.append(if (seen[i].size >= LENS[i]) ('0' + LENS[i]) else '1')
        }
        return out.toString()
    }

    fun shotCells(s: String?): List<Int> = (s ?: "").chunked(2).filter { it.length == 2 }.mapNotNull { it.toIntOrNull() }

    class Ev(val by: String, val cell: Int, val res: Char)
    class Replay(val cur: String, val opp: String, val pending: Boolean, val log: List<Ev>, val winner: String, val left: Int = 1)

    /** Reproduce la partida con lo de la nube: turno, disparo pendiente de respuesta y registro. Empieza players[seed % 2]; cada turno son `shots` disparos. */
    fun replay(players: List<String>, seed: Int, again: Boolean, s: Map<String, String>, a: Map<String, String>, shots: Int = 1): Replay {
        val p0 = players[0]; val p1 = players[1]
        val n = shots.coerceIn(1, 5)
        val idx = hashMapOf(p0 to 0, p1 to 0)
        val sunk = hashMapOf(p0 to 0, p1 to 0)
        val log = ArrayList<Ev>()
        var cur = players[seed % 2]
        var winner = ""
        var left = n
        var guard = 0
        while (guard++ < 400) {
            val opp = if (cur == p0) p1 else p0
            val sc = shotCells(s[cur])
            val ans = a[opp] ?: ""
            val k = idx.getValue(cur)
            if (k < sc.size && k < ans.length) {
                val res = ans[k]
                log.add(Ev(cur, sc[k], res))
                idx[cur] = k + 1
                if (res >= '2') sunk[cur] = sunk.getValue(cur) + 1
                if (sunk.getValue(cur) >= LENS.size) { winner = cur; break }
                if (!(res != '0' && again)) left--
                if (left <= 0) { cur = opp; left = n }
            } else break
        }
        val opp = if (cur == p0) p1 else p0
        return Replay(cur, opp, winner.isEmpty() && idx.getValue(cur) < shotCells(s[cur]).size, log, winner, left)
    }

    class SunkShip(val idx: Int, val ship: Ship)

    /** Dónde estaban los barcos que `by` ha hundido (misma deducción que `sunkShips` de la web): `idx` = qué barco de la flota era, por tamaño. */
    fun sunkShips(log: List<Ev>, by: String): List<SunkShip> {
        val hits = HashSet<Int>(); val used = HashSet<Int>(); val taken = HashSet<Int>(); val out = ArrayList<SunkShip>()
        for (e in log) {
            if (e.by != by || e.res == '0') continue
            hits.add(e.cell)
            if (e.res < '2') continue
            val len = e.res - '0'
            var found: Ship? = null
            for (dir in charArrayOf('h', 'v')) {
                var k = 0
                while (k < len && found == null) {
                    val step = if (dir == 'h') 1 else SIZE
                    val head = e.cell - k * step
                    k++
                    if (head < 0 || (if (dir == 'h') head % SIZE + len > SIZE else head / SIZE + len > SIZE)) continue
                    val cells = (0 until len).map { head + it * step }
                    if (cells.all { it in hits && it !in used }) { found = Ship(head, dir); used.addAll(cells) }
                }
                if (found != null) break
            }
            val idx = LENS.indices.firstOrNull { LENS[it] == len && it !in taken } ?: -1
            if (idx >= 0) taken.add(idx)
            found?.let { out.add(SunkShip(idx, it)) }
        }
        return out
    }

    /** Comprobación final: la flota revelada («flota:sal») ¿coincide con el compromiso, es válida y da las mismas respuestas? '' = todo bien. */
    fun verify(reveal: String?, commit: String?, adj: Boolean, shots: String, given: String): String {
        if (reveal.isNullOrEmpty() || commit.isNullOrEmpty()) return "sin revelar"
        val cut = reveal.lastIndexOf(':')
        if (cut < 0) return "sin revelar"
        val fleet = reveal.substring(0, cut); val salt = reveal.substring(cut + 1)
        if (commitOf(fleet, salt) != commit) return "la flota no coincide con la que dejó al empezar"
        val ships = parseFleet(fleet)
        if (ships == null || !validFleet(ships, adj)) return "la flota no era válida"
        val expect = answers(ships, shotCells(shots))
        val n = minOf(expect.length, given.length)
        if (expect.substring(0, n) != given.substring(0, n)) return "respondió algo que no era cierto"
        return ""
    }

    // ───── dibujo de los barcos (las mismas piezas que en la web, en una cuadrícula de largo × 1) ─────

    sealed class Part(val color: Long)
    class Poly(val pts: List<Pair<Float, Float>>, color: Long) : Part(color)
    class Rect(val x: Float, val y: Float, val w: Float, val h: Float, color: Long) : Part(color)
    class Ell(val cx: Float, val cy: Float, val rx: Float, val ry: Float, color: Long) : Part(color)
    class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val w: Float, color: Long) : Part(color)

    private const val HULL = 0xFF6F7D8CL
    private const val DARK = 0xFF3F4A56L
    private const val MID = 0xFF566370L
    private const val LIGHT = 0xFFB9C4CFL
    private const val RED = 0xFFD9534FL
    private const val GLASS = 0xFF8FD3FFL

    fun shipParts(i: Int): List<Part> {
        val l = LENS[i].toFloat()
        val hull = Poly(listOf(0.06f to 0.5f, (l - 0.06f) to 0.5f, (l - 0.2f) to 0.9f, 0.22f to 0.9f), HULL)
        val waterline = Rect(0.2f, 0.82f, l - 0.4f, 0.08f, DARK)
        return when (i) {
            0 -> listOf(
                hull, waterline, Rect(0.1f, 0.4f, l - 0.24f, 0.12f, LIGHT), Rect(l * 0.62f, 0.16f, 0.5f, 0.26f, DARK),
                Rect(l * 0.62f + 0.08f, 0.22f, 0.34f, 0.08f, GLASS), Rect(l * 0.62f + 0.2f, 0.06f, 0.06f, 0.12f, MID),
                Poly(listOf(0.5f to 0.4f, 1.1f to 0.4f, 0.8f to 0.26f), RED), Poly(listOf(1.6f to 0.4f, 2.2f to 0.4f, 1.9f to 0.26f), RED),
            )
            1 -> listOf(
                hull, waterline, Rect(0.3f, 0.34f, 0.5f, 0.17f, MID), Line(0.7f, 0.42f, 1.1f, 0.42f, 0.07f, DARK),
                Rect(1.2f, 0.34f, 0.5f, 0.17f, MID), Line(1.6f, 0.42f, 2.0f, 0.42f, 0.07f, DARK),
                Rect(2.2f, 0.14f, 0.6f, 0.36f, DARK), Rect(2.3f, 0.22f, 0.4f, 0.07f, GLASS), Rect(2.45f, 0.04f, 0.06f, 0.12f, MID),
                Rect(3.1f, 0.34f, 0.4f, 0.17f, MID),
            )
            2 -> listOf(
                hull, waterline, Rect(0.3f, 0.34f, 0.45f, 0.17f, MID), Line(0.65f, 0.42f, 1.0f, 0.42f, 0.07f, DARK),
                Rect(1.1f, 0.16f, 0.6f, 0.34f, DARK), Rect(1.2f, 0.24f, 0.4f, 0.07f, GLASS), Rect(1.85f, 0.2f, 0.2f, 0.3f, RED), Rect(1.4f, 0.06f, 0.05f, 0.12f, MID),
            )
            3 -> listOf(
                Ell(l / 2, 0.64f, l / 2 - 0.08f, 0.24f, HULL), Rect(l / 2 - 0.3f, 0.28f, 0.6f, 0.3f, DARK), Rect(l / 2 - 0.2f, 0.34f, 0.4f, 0.07f, GLASS),
                Line(l / 2 + 0.1f, 0.28f, l / 2 + 0.1f, 0.08f, 0.05f, MID), Line(l / 2 + 0.1f, 0.08f, l / 2 + 0.32f, 0.08f, 0.05f, MID), Rect(0.12f, 0.6f, 0.22f, 0.07f, DARK),
            )
            else -> listOf(
                hull, waterline, Rect(0.3f, 0.36f, 0.34f, 0.15f, MID), Line(0.55f, 0.43f, 0.82f, 0.43f, 0.06f, DARK),
                Rect(0.95f, 0.2f, 0.5f, 0.3f, DARK), Rect(1.02f, 0.27f, 0.34f, 0.07f, GLASS), Rect(1.5f, 0.3f, 0.14f, 0.2f, RED),
            )
        }
    }
}
