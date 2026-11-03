package com.evability.notes

// Motor de ajedrez con las reglas oficiales: movimientos legales, jaque, jaque mate, tablas por ahogado, material insuficiente,
// triple repetición y regla de los 50 movimientos, enroque, captura al paso y promoción.
// Casillas 0..63 como en FEN: 0 = a8, 7 = h8, 56 = a1, 63 = h1. Piezas: mayúscula = blancas, minúscula = negras, ' ' = vacío.
// Hay una versión idéntica en JavaScript (docs/js/chess-engine.js): si se cambia una, hay que cambiar la otra.

class ChessMove(val from: Int, val to: Int, val promo: Char = ' ', val flag: String = "")

class ChessPos(val b: CharArray, val turn: Char, val castling: String, val ep: Int, val half: Int, val full: Int)

/** `result`: 'w' ganan blancas, 'b' ganan negras, 'd' tablas. */
class ChessOutcome(val over: Boolean, val result: Char, val reason: String, val check: Boolean)

class ChessPlayed(val m: ChessMove, val captured: Char, val piece: Char)

class ChessReplay(val p: ChessPos, val keys: List<String>, val history: List<ChessPlayed>)

object Chess {
    const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private const val FILES = "abcdefgh"

    fun sqName(s: Int): String = "${FILES[s and 7]}${8 - (s shr 3)}"
    fun sqIndex(name: String): Int = (8 - (name[1] - '0')) * 8 + FILES.indexOf(name[0])
    fun colorOf(c: Char): Char = if (c == ' ') ' ' else if (c.isUpperCase()) 'w' else 'b'

    fun fromFen(fen: String): ChessPos {
        val parts = fen.split(" ")
        val b = CharArray(64) { ' ' }
        var i = 0
        for (ch in parts[0]) {
            when {
                ch == '/' -> {}
                ch in '1'..'8' -> i += ch - '0'
                else -> b[i++] = ch
            }
        }
        return ChessPos(
            b, parts[1][0], if (parts[2] == "-") "" else parts[2],
            if (parts[3] == "-") -1 else sqIndex(parts[3]), parts[4].toInt(), parts[5].toInt(),
        )
    }

    fun startPosition() = fromFen(START_FEN)

    private val KNIGHT = arrayOf(intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, -2), intArrayOf(-1, 2), intArrayOf(1, -2), intArrayOf(1, 2), intArrayOf(2, -1), intArrayOf(2, 1))
    private val KING = arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 0), intArrayOf(-1, 1), intArrayOf(0, -1), intArrayOf(0, 1), intArrayOf(1, -1), intArrayOf(1, 0), intArrayOf(1, 1))
    private val DIAG = arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1))
    private val ORTHO = arrayOf(intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1))

    /** ¿La casilla `sq` está atacada por las piezas de color `by`? */
    fun attacked(p: ChessPos, sq: Int, by: Char): Boolean {
        val r = sq shr 3
        val f = sq and 7
        val b = p.b
        fun at(rr: Int, ff: Int): Char = if (rr < 0 || rr > 7 || ff < 0 || ff > 7) '?' else b[rr * 8 + ff]
        val pawn = if (by == 'w') 'P' else 'p'
        val knight = if (by == 'w') 'N' else 'n'
        val king = if (by == 'w') 'K' else 'k'
        val pr = if (by == 'w') r + 1 else r - 1
        if (at(pr, f - 1) == pawn || at(pr, f + 1) == pawn) return true
        for (d in KNIGHT) if (at(r + d[0], f + d[1]) == knight) return true
        for (d in KING) if (at(r + d[0], f + d[1]) == king) return true
        val bishop = if (by == 'w') 'B' else 'b'
        val rook = if (by == 'w') 'R' else 'r'
        val queen = if (by == 'w') 'Q' else 'q'
        for (d in DIAG) {
            var rr = r + d[0]
            var ff = f + d[1]
            while (rr in 0..7 && ff in 0..7) {
                val c = b[rr * 8 + ff]
                if (c != ' ') {
                    if (c == bishop || c == queen) return true
                    break
                }
                rr += d[0]
                ff += d[1]
            }
        }
        for (d in ORTHO) {
            var rr = r + d[0]
            var ff = f + d[1]
            while (rr in 0..7 && ff in 0..7) {
                val c = b[rr * 8 + ff]
                if (c != ' ') {
                    if (c == rook || c == queen) return true
                    break
                }
                rr += d[0]
                ff += d[1]
            }
        }
        return false
    }

    fun kingSquare(p: ChessPos, color: Char): Int = p.b.indexOf(if (color == 'w') 'K' else 'k')
    fun inCheck(p: ChessPos, color: Char): Boolean = attacked(p, kingSquare(p, color), if (color == 'w') 'b' else 'w')

    /** Movimientos «pseudo-legales»: siguen las reglas de cada pieza pero pueden dejar al propio rey en jaque. */
    private fun pseudoMoves(p: ChessPos): List<ChessMove> {
        val moves = ArrayList<ChessMove>()
        val b = p.b
        val me = p.turn
        val enemy = if (me == 'w') 'b' else 'w'
        for (from in 0 until 64) {
            val c = b[from]
            if (c == ' ' || colorOf(c) != me) continue
            val r = from shr 3
            val f = from and 7
            val kind = c.lowercaseChar()
            if (kind == 'p') {
                val dir = if (me == 'w') -1 else 1
                val startRow = if (me == 'w') 6 else 1
                val lastRow = if (me == 'w') 0 else 7
                fun addPawn(to: Int, flag: String = "") {
                    if (to shr 3 == lastRow) for (pr in "qrbn") moves.add(ChessMove(from, to, pr, flag)) else moves.add(ChessMove(from, to, ' ', flag))
                }
                val one = from + dir * 8
                if (b[one] == ' ') {
                    addPawn(one)
                    if (r == startRow && b[one + dir * 8] == ' ') moves.add(ChessMove(from, one + dir * 8, ' ', "double"))
                }
                for (df in intArrayOf(-1, 1)) {
                    val ff = f + df
                    if (ff < 0 || ff > 7) continue
                    val to = (r + dir) * 8 + ff
                    if (b[to] != ' ' && colorOf(b[to]) == enemy) addPawn(to)
                    else if (to == p.ep) moves.add(ChessMove(from, to, ' ', "ep"))
                }
            } else if (kind == 'n' || kind == 'k') {
                for (d in if (kind == 'n') KNIGHT else KING) {
                    val rr = r + d[0]
                    val ff = f + d[1]
                    if (rr < 0 || rr > 7 || ff < 0 || ff > 7) continue
                    val to = rr * 8 + ff
                    if (b[to] == ' ' || colorOf(b[to]) == enemy) moves.add(ChessMove(from, to))
                }
            } else {
                val dirs = when (kind) { 'b' -> DIAG; 'r' -> ORTHO; else -> DIAG + ORTHO }
                for (d in dirs) {
                    var rr = r + d[0]
                    var ff = f + d[1]
                    while (rr in 0..7 && ff in 0..7) {
                        val to = rr * 8 + ff
                        if (b[to] == ' ') {
                            moves.add(ChessMove(from, to))
                        } else {
                            if (colorOf(b[to]) == enemy) moves.add(ChessMove(from, to))
                            break
                        }
                        rr += d[0]
                        ff += d[1]
                    }
                }
            }
        }
        // enroque: rey y torre sin moverse, casillas libres y el rey no sale, pasa ni llega a una casilla atacada
        val home = if (me == 'w') 60 else 4
        val king = if (me == 'w') 'K' else 'k'
        val rook = if (me == 'w') 'R' else 'r'
        if (b[home] == king && !attacked(p, home, enemy)) {
            val short = if (me == 'w') 'K' else 'k'
            val long = if (me == 'w') 'Q' else 'q'
            if (p.castling.contains(short) && b[home + 1] == ' ' && b[home + 2] == ' ' && b[home + 3] == rook &&
                !attacked(p, home + 1, enemy) && !attacked(p, home + 2, enemy)
            ) moves.add(ChessMove(home, home + 2, ' ', "castle"))
            if (p.castling.contains(long) && b[home - 1] == ' ' && b[home - 2] == ' ' && b[home - 3] == ' ' && b[home - 4] == rook &&
                !attacked(p, home - 1, enemy) && !attacked(p, home - 2, enemy)
            ) moves.add(ChessMove(home, home - 2, ' ', "castle"))
        }
        return moves
    }

    /** Aplica una jugada y devuelve la posición nueva (la anterior no se modifica). */
    fun applyMove(p: ChessPos, m: ChessMove): ChessPos {
        val b = p.b.copyOf()
        val piece = b[m.from]
        val me = p.turn
        val capture = b[m.to] != ' ' || m.flag == "ep"
        b[m.to] = if (m.promo != ' ') (if (me == 'w') m.promo.uppercaseChar() else m.promo) else piece
        b[m.from] = ' '
        if (m.flag == "ep") b[m.to + if (me == 'w') 8 else -8] = ' '
        if (m.flag == "castle") {
            when (m.to) {
                62 -> { b[61] = b[63]; b[63] = ' ' }
                58 -> { b[59] = b[56]; b[56] = ' ' }
                6 -> { b[5] = b[7]; b[7] = ' ' }
                2 -> { b[3] = b[0]; b[0] = ' ' }
            }
        }
        var castling = p.castling
        fun drop(chars: String) { for (ch in chars) castling = castling.replace(ch.toString(), "") }
        if (piece == 'K') drop("KQ")
        if (piece == 'k') drop("kq")
        for (s in intArrayOf(m.from, m.to)) {
            if (s == 63) drop("K")
            if (s == 56) drop("Q")
            if (s == 7) drop("k")
            if (s == 0) drop("q")
        }
        return ChessPos(
            b, if (me == 'w') 'b' else 'w', castling,
            if (m.flag == "double") (m.from + m.to) / 2 else -1,
            if (piece.lowercaseChar() == 'p' || capture) 0 else p.half + 1,
            p.full + if (me == 'b') 1 else 0,
        )
    }

    /** Todos los movimientos legales: los que no dejan al propio rey en jaque (si estás en jaque, solo valen los que lo resuelven). */
    fun legalMoves(p: ChessPos): List<ChessMove> = pseudoMoves(p).filter { !inCheck(applyMove(p, it), p.turn) }

    fun legalMovesFrom(p: ChessPos, sq: Int): List<ChessMove> = legalMoves(p).filter { it.from == sq }

    fun moveString(m: ChessMove): String = sqName(m.from) + sqName(m.to) + (if (m.promo == ' ') "" else m.promo.toString())

    /** Busca el movimiento legal que corresponde a un texto como «e2e4» o «e7e8q». */
    fun parseMove(p: ChessPos, str: String): ChessMove? = legalMoves(p).firstOrNull { moveString(it) == str }

    /** Clave de la posición para contar repeticiones (la casilla de captura al paso solo cuenta si de verdad se puede capturar). */
    fun positionKey(p: ChessPos): String {
        val epOk = p.ep >= 0 && legalMoves(p).any { it.flag == "ep" }
        val sb = StringBuilder()
        for (c in p.b) sb.append(if (c == ' ') '.' else c)
        return "$sb ${p.turn} ${p.castling} ${if (epOk) p.ep.toString() else "-"}"
    }

    private fun insufficientMaterial(p: ChessPos): Boolean {
        val rest = p.b.filter { it != ' ' && it.lowercaseChar() != 'k' }
        if (rest.isEmpty()) return true
        if (rest.size == 1 && "bn".contains(rest[0].lowercaseChar())) return true
        if (rest.all { it.lowercaseChar() == 'b' }) {
            val colors = HashSet<Int>()
            p.b.forEachIndexed { i, c -> if (c != ' ' && c.lowercaseChar() == 'b') colors.add(((i shr 3) + (i and 7)) % 2) }
            return colors.size == 1
        }
        return false
    }

    /** Estado de la partida. `keys` = claves de todas las posiciones desde el principio (la actual incluida). */
    fun outcome(p: ChessPos, keys: List<String> = emptyList()): ChessOutcome {
        val moves = legalMoves(p)
        val check = inCheck(p, p.turn)
        if (moves.isEmpty()) {
            return if (check) ChessOutcome(true, if (p.turn == 'w') 'b' else 'w', "mate", true) else ChessOutcome(true, 'd', "stalemate", false)
        }
        if (insufficientMaterial(p)) return ChessOutcome(true, 'd', "insufficient", check)
        if (p.half >= 100) return ChessOutcome(true, 'd', "50moves", check)
        val key = if (keys.isNotEmpty()) keys.last() else positionKey(p)
        if (keys.count { it == key } >= 3) return ChessOutcome(true, 'd', "repetition", check)
        return ChessOutcome(false, ' ', "", check)
    }

    /** Rejuega una lista de movimientos desde el principio: devuelve la posición, las claves y el historial (o null si algo no es legal). */
    fun replay(moves: List<String>): ChessReplay? {
        var p = startPosition()
        val keys = arrayListOf(positionKey(p))
        val history = ArrayList<ChessPlayed>()
        for (s in moves) {
            val m = parseMove(p, s) ?: return null
            val captured = if (m.flag == "ep") (if (p.turn == 'w') 'p' else 'P') else p.b[m.to]
            history.add(ChessPlayed(m, captured, p.b[m.from]))
            p = applyMove(p, m)
            keys.add(positionKey(p))
        }
        return ChessReplay(p, keys, history)
    }

    /** Cuenta de posibles jugadas hasta cierta profundidad (sirve para comprobar que el motor es correcto). */
    fun perft(p: ChessPos, depth: Int): Long {
        if (depth == 0) return 1
        var n = 0L
        for (m in legalMoves(p)) n += if (depth == 1) 1 else perft(applyMove(p, m), depth - 1)
        return n
    }
}
