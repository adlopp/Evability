package com.evability.notes

import org.json.JSONObject

/**
 * Motor de reglas del Evapoly (Monopoly): copia idéntica de docs/js/evapoly-engine.js. La partida es una lista de acciones
 * («asiento.código») que se aplica sobre un estado inicial que sale de la semilla de la sala, así todos llegan al mismo estado.
 * Si cambias algo aquí, cámbialo también allí (y viceversa).
 *
 * Códigos: r tirar · p pagar multa · c usar carta de cárcel · b comprar · n no comprar · a subastar · i<cantidad> pujar · f retirarse
 * · e terminar turno · k usar Maricarmen · h<casilla> construir · s<casilla> vender · m<casilla> hipotecar · u<casilla> deshipotecar · d pagar deuda
 * · q quiebra · z abandonar · T<a>|<doy €>|<pido €>|<doy casillas>|<pido casillas>|<doy cartas>|<pido cartas> trato · Y aceptar · N rechazar
 */
object Poly {
    const val GO_SALARY = 200
    const val JAIL_FINE = 50
    const val JAIL = 10
    const val MAX_HOUSES = 32 // límite de casas y de hoteles de CADA jugador (no se comparte)
    const val MAX_HOTELS = 12
    /** Tienda de La Peza (regla opcional): objeto → (nombre, precio). No salen en Suerte ni en Caja. */
    val SHOP = mapOf(1 to ("la carta Maricarmen" to 450), 2 to ("el dado triple" to 100), 3 to ("la doble condena" to 250))

    class Sq(val n: String, val t: String, val g: String?, val p: Int, val r: IntArray, val i: String? = null)
    class Card(val x: String, val e: String)
    class Data(val squares: List<Sq>, val groups: Map<String, String>, val house: Map<String, Int>, val chance: List<Card>, val chest: List<Card>)
    class Cfg(val money: Int, val goBonus: Boolean, val auction: Boolean, val pot: Boolean = true, val dblBonus: Boolean = false, val rails: Boolean = false, val ownerFree: Boolean = false, val shop: Boolean = false)
    class Deck(var order: List<Int> = emptyList(), var i: Int = 0, var held: Int = -1, var held2: Int = -1)
    class Auction(val sq: Int, var bid: Int, var bidder: Int, val active: MutableList<Int>, var cur: Int)
    class Debt(val seat: Int, val amt: Int, var to: Int, val why: String, val rent: Boolean = false, val tax: Boolean = false)
    class Trade(val from: Int, val to: Int, val gc: Int, val rc: Int, val gp: List<Int>, val rp: List<Int>, val gj: Int, val rj: Int)
    class Ev(val k: String, val m: String, val seat: Int = -1, val to: Int = -1, val from: Int = -1, val amt: Int = 0, val d1: Int = 0, val d2: Int = 0, val dir: Int = 1, val sq: Int = -1, val text: String = "", val d3: Int = 0)

    fun eur(n: Int): String = n.toString().replace(Regex("\\B(?=(\\d{3})+(?!\\d))"), ".") + " €"

    fun load(json: String): Data {
        val o = JSONObject(json)
        val sqs = o.getJSONArray("squares")
        val squares = (0 until sqs.length()).map { i ->
            val q = sqs.getJSONObject(i)
            val r = q.optJSONArray("r")
            Sq(q.getString("n"), q.getString("t"), q.optString("g").ifEmpty { null }, q.optInt("p"), IntArray(r?.length() ?: 0) { r!!.getInt(it) }, q.optString("i").ifEmpty { null })
        }
        fun cards(k: String): List<Card> {
            val a = o.getJSONObject("cards").getJSONArray(k)
            return (0 until a.length()).map { Card(a.getJSONObject(it).getString("x"), a.getJSONObject(it).getString("e")) }
        }
        val gs = o.getJSONObject("groups"); val hs = o.getJSONObject("house")
        return Data(squares, gs.keys().asSequence().associateWith { gs.getString(it) }, hs.keys().asSequence().associateWith { hs.getInt(it) }, cards("chance"), cards("chest"))
    }

    class State(val data: Data, val n: Int, val cfg: Cfg, var r: Int) {
        val pos = IntArray(n)
        val cash = IntArray(n) { cfg.money }
        val jail = BooleanArray(n)
        val jt = IntArray(n)
        val jf = IntArray(n)
        val bk = BooleanArray(n)
        val own = IntArray(40) { -1 }
        val lvl = IntArray(40)
        val mort = BooleanArray(40)
        val deckC = Deck()
        val deckH = Deck()
        val heldBy = intArrayOf(-1, -1) // 0 = Suerte, 1 = Caja
        val mc = IntArray(n) // cartas Maricarmen en la mano
        var mcBy = -1
        var pot = 0 // impuestos acumulados en La Peza
        var ordCand = BooleanArray(n) { true } // quién tira para decidir quién empieza
        var ordRolls = IntArray(n)
        val railCd = IntArray(n) // turnos que faltan para poder volver a viajar en tren
        val tri = IntArray(n) // dados triples comprados en la tienda
        val dj = IntArray(n) // dobles condenas compradas en la tienda
        val jmax = IntArray(n) { 3 } // turnos máximos en la cárcel de cada jugador (6 con la doble condena)
        var turn = 0
        var phase = "roll"
        var dbl = 0
        var again = false
        var d1 = 0
        var d2 = 0
        var buy = -1
        var auction: Auction? = null
        var debts = ArrayList<Debt>()
        var pendMove = -1
        var trade: Trade? = null
        var winner = -1
        var ev = ArrayList<Ev>()
        var acts = 0

        fun sq(i: Int) = data.squares[i]
        fun deck(w: Int) = if (w == 0) deckC else deckH
        fun alive(): List<Int> = (0 until n).filter { !bk[it] }
        fun groupOf(g: String?): List<Int> = (0 until 40).filter { sq(it).g == g }
        fun houseCost(i: Int) = data.house[sq(i).g] ?: 0
        fun ownsAll(seat: Int, g: String?) = groupOf(g).all { own[it] == seat }
        fun countOwned(seat: Int, t: String) = (0 until 40).count { own[it] == seat && sq(it).t == t }
        fun housesOf(seat: Int) = (0 until 40).filter { own[it] == seat && lvl[it] in 1..4 }.sumOf { lvl[it] }
        fun hotelsOf(seat: Int) = (0 until 40).count { own[it] == seat && lvl[it] == 5 }
        fun unmortgageCost(i: Int) = Math.ceil(sq(i).p * 0.55).toInt()

        /** Alquiler que se paga al caer en la casilla `i` (los servicios dependen de los dados). */
        fun rentOf(i: Int, dice: Int = 0): Int {
            val q = sq(i); val o = own[i]
            if (o < 0 || mort[i]) return 0
            return when (q.t) {
                "street" -> if (lvl[i] > 0) q.r[lvl[i]] else if (ownsAll(o, q.g)) q.r[0] * 2 else q.r[0]
                "station" -> q.r[countOwned(o, "station") - 1]
                "utility" -> dice * (if (countOwned(o, "utility") == 2) 10 else 4)
                else -> 0
            }
        }

        /** Texto de resumen para comparar con la versión de JS en las pruebas. */
        fun digest(): String {
            fun b(x: Boolean) = if (x) "true" else "false"
            return "[" + listOf(
                pos.joinToString(",", "[", "]"), cash.joinToString(",", "[", "]"), jail.joinToString(",", "[", "]") { b(it) }, jt.joinToString(",", "[", "]"),
                jf.joinToString(",", "[", "]"), bk.joinToString(",", "[", "]") { b(it) }, own.joinToString(",", "[", "]"), lvl.joinToString(",", "[", "]"),
                mort.joinToString(",", "[", "]") { if (it) "1" else "0" }, heldBy[0].toString(), heldBy[1].toString(), turn.toString(),
                "\"$phase\"", dbl.toString(), b(again), buy.toString(), winner.toString(), r.toString(), mc.joinToString(",", "[", "]"), mcBy.toString(), pot.toString(), ordCand.joinToString(",", "[", "]") { if (it) "1" else "0" }, ordRolls.joinToString(",", "[", "]"), railCd.joinToString(",", "[", "]"), tri.joinToString(",", "[", "]"), dj.joinToString(",", "[", "]"), jmax.joinToString(",", "[", "]"),
                debts.joinToString(",", "[", "]") { "[${it.seat},${it.amt},${it.to}]" },
                auction?.let { "[${it.sq},${it.bid},${it.bidder},${it.cur}]" } ?: "null",
            ).joinToString(",") + "]"
        }
    }

    // ───── números aleatorios con semilla (mulberry32): idénticos a los de JS ─────
    private fun rnd(s: State): Double {
        s.r += 0x6D2B79F5
        var t = s.r
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }

    private fun rint(s: State, n: Int): Int = Math.floor(rnd(s) * n).toInt()

    private fun shuffled(s: State, n: Int): List<Int> {
        val a = (0 until n).toMutableList()
        for (i in n - 1 downTo 1) {
            val j = rint(s, i + 1)
            val t = a[i]; a[i] = a[j]; a[j] = t
        }
        return a
    }

    fun initGame(data: Data, n: Int, cfg: Cfg, seed: Int): State {
        val s = State(data, n, cfg, seed)
        s.deckC.order = shuffled(s, data.chance.size)
        s.deckH.order = shuffled(s, data.chest.size)
        s.phase = "order"
        say(s, "Todos tiran los dados: empieza el que saque más", "start")
        return s
    }

    private fun say(s: State, m: String, k: String = "msg", seat: Int = -1, to: Int = -1, from: Int = -1, amt: Int = 0, d1: Int = 0, d2: Int = 0, dir: Int = 1, sq: Int = -1, text: String = "", d3: Int = 0) {
        s.ev.add(Ev(k, m, seat, to, from, amt, d1, d2, dir, sq, text, d3))
    }

    private fun nextAlive(s: State, from: Int): Int {
        for (i in 1..s.n) { val t = (from + i) % s.n; if (!s.bk[t]) return t }
        return from
    }

    // ───── dinero ─────
    private fun pay(s: State, seat: Int, amt: Int, to: Int, why: String, rent: Boolean = false, tax: Boolean = false) {
        if (amt <= 0) return
        if (rent && s.mc[seat] > 0) {
            s.debts.add(Debt(seat, amt, to, why, true))
            say(s, "@$seat debe ${eur(amt)} a @$to$why", "debt", seat = seat)
        } else if (s.cash[seat] >= amt) {
            s.cash[seat] -= amt
            if (to >= 0) s.cash[to] += amt
            if (tax && s.cfg.pot) s.pot += amt
            say(s, if (to >= 0) "@$seat paga ${eur(amt)} a @$to$why" else "@$seat paga ${eur(amt)}$why", "pay", seat = seat, to = to, amt = amt)
        } else {
            s.debts.add(Debt(seat, amt, to, why, false, tax))
            say(s, "@$seat debe ${eur(amt)}${if (to >= 0) " a @$to" else ""}$why y no le llega el efectivo", "debt", seat = seat)
        }
    }

    // ───── movimiento ─────
    private fun goPay(s: State, seat: Int, landed: Boolean) {
        val amt = if (landed && s.cfg.goBonus) 2 * GO_SALARY else GO_SALARY
        s.cash[seat] += amt
        say(s, if (landed) "@$seat cae en la Salida y cobra ${eur(amt)}" else "@$seat pasa por la Salida y cobra ${eur(amt)}", "go", seat = seat, amt = amt)
    }

    private fun moveTo(s: State, seat: Int, to: Int, collect: Boolean) {
        val from = s.pos[seat]
        if (collect && (to < from || to == 0)) goPay(s, seat, to == 0)
        s.pos[seat] = to
        say(s, "@$seat avanza hasta ${s.sq(to).n}", "move", seat = seat, from = from, to = to, dir = 1)
    }

    private fun moveDice(s: State, seat: Int, total: Int) {
        val from = s.pos[seat]; val raw = from + total; val to = raw % 40
        if (raw >= 40) goPay(s, seat, to == 0)
        s.pos[seat] = to
        say(s, "@$seat avanza $total y cae en ${s.sq(to).n}", "move", seat = seat, from = from, to = to, dir = 1)
        land(s, seat, to, dice = total, utilRoll = false, double = false)
    }

    private fun sendJail(s: State, seat: Int, why: String) {
        val from = s.pos[seat]
        s.pos[seat] = JAIL; s.jail[seat] = true; s.jt[seat] = 0; s.jmax[seat] = 3
        s.again = false; s.dbl = 0
        say(s, "@$seat va a la cárcel$why", "jail", seat = seat, from = from, to = JAIL)
    }

    private fun land(s: State, seat: Int, i: Int, dice: Int = 0, utilRoll: Boolean = false, double: Boolean = false) {
        val q = s.sq(i)
        when (q.t) {
            "street", "station", "utility" -> {
                val o = s.own[i]
                if (o < 0) {
                    if (s.cash[seat] >= q.p || s.cfg.auction) s.buy = i
                    else say(s, "@$seat no tiene dinero para comprar ${q.n} (${eur(q.p)})")
                } else if (s.cfg.ownerFree && o != seat && !s.mort[i] && s.pos[o] == i) {
                    say(s, "@$o está en ${q.n}: @$seat no paga alquiler")
                } else if (o != seat && !s.mort[i]) {
                    val rent: Int
                    if (q.t == "utility" && utilRoll) {
                        val a = rint(s, 6) + 1; val b = rint(s, 6) + 1
                        say(s, "@$seat tira los dados: $a y $b", "dice", seat = seat, d1 = a, d2 = b)
                        rent = (a + b) * 10
                    } else rent = s.rentOf(i, dice) * (if (double) 2 else 1)
                    pay(s, seat, rent, o, " de alquiler por ${q.n}", true)
                }
            }
            "tax" -> pay(s, seat, q.p, -1, " de ${q.n.lowercase()}", false, true)
            "parking" -> if (s.cfg.pot && s.pot > 0) {
                val v = s.pot
                s.pot = 0
                s.cash[seat] += v
                s.ev.add(Ev("card", "La Peza: Tito Pepe te ha soltado unos fajos de billetes", seat = seat, text = "Tito Pepe te ha soltado unos fajos de billetes", sq = 2))
                say(s, "@$seat se lleva ${eur(v)} de los impuestos de ${q.n}", "gain", seat = seat, amt = v)
            }
            "chance" -> draw(s, seat, 0)
            "chest" -> draw(s, seat, 1)
            "gotojail" -> sendJail(s, seat, "")
            else -> {}
        }
    }

    // ───── cartas ─────
    private fun draw(s: State, seat: Int, which: Int) {
        val d = s.deck(which)
        val cards = if (which == 0) s.data.chance else s.data.chest
        var idx: Int
        do { idx = d.order[d.i % d.order.size]; d.i++ } while (idx == d.held || idx == d.held2)
        val parts = cards[idx].e.split(":")
        val kind = parts[0]
        val a = parts.getOrNull(1); val b = parts.getOrNull(2)
        say(s, "${if (which == 0) "Suerte" else "Caja"}: ${cards[idx].x}", "card", seat = seat, text = cards[idx].x, sq = which)
        when (kind) {
            "to" -> { moveTo(s, seat, a!!.toInt(), true); land(s, seat, a.toInt()) }
            "near" -> {
                val list = if (a == "u") listOf(12, 28) else listOf(5, 15, 25, 35)
                val p = s.pos[seat]
                val t = list.firstOrNull { it > p } ?: list[0]
                moveTo(s, seat, t, true)
                if (a == "u") land(s, seat, t, utilRoll = true) else land(s, seat, t, double = true)
            }
            "back" -> {
                val from = s.pos[seat]; val to = (from + 40 - a!!.toInt()) % 40
                s.pos[seat] = to
                say(s, "@$seat retrocede $a casillas hasta ${s.sq(to).n}", "move", seat = seat, from = from, to = to, dir = -1)
                land(s, seat, to)
            }
            "jail" -> sendJail(s, seat, "")
            "mc" -> { d.held2 = idx; s.mc[seat]++; s.mcBy = seat }
            "free" -> { d.held = idx; s.jf[seat]++; s.heldBy[which] = seat }
            "cash" -> {
                val v = a!!.toInt()
                if (v >= 0) { s.cash[seat] += v; say(s, "@$seat cobra ${eur(v)}", "gain", seat = seat, amt = v) } else pay(s, seat, -v, -1, "")
            }
            "repair" -> {
                var h = 0; var t = 0
                for (i in 0 until 40) if (s.own[i] == seat) { if (s.lvl[i] == 5) t++ else h += s.lvl[i] }
                pay(s, seat, h * a!!.toInt() + t * b!!.toInt(), -1, " de reparaciones")
            }
            "eachpay" -> for (o in s.alive()) if (o != seat) pay(s, seat, a!!.toInt(), o, "")
            "eachget" -> for (o in s.alive()) if (o != seat) pay(s, o, a!!.toInt(), seat, " de cumpleaños")
        }
    }

    // ───── deudas, quiebra y fin ─────
    private fun liquidate(s: State, seat: Int) {
        for (i in 0 until 40) {
            if (s.own[i] != seat || s.lvl[i] == 0) continue
            s.cash[seat] += s.lvl[i] * s.houseCost(i) / 2
            s.lvl[i] = 0
        }
    }

    private fun bankrupt(s: State, seat: Int, to: Int, why: String) {
        liquidate(s, seat)
        for (i in 0 until 40) if (s.own[i] == seat) { s.own[i] = to; if (to < 0) s.mort[i] = false }
        if (to >= 0) { s.cash[to] += s.cash[seat]; s.jf[to] += s.jf[seat]; s.mc[to] += s.mc[seat]; s.tri[to] += s.tri[seat]; s.dj[to] += s.dj[seat]; if (s.mcBy == seat) s.mcBy = to }
        else if (s.mcBy == seat) { s.deckC.held2 = -1; s.mcBy = -1 }
        else for (w in 0..1) if (s.deck(w).held >= 0 && s.heldBy[w] == seat) { s.deck(w).held = -1; s.heldBy[w] = -1 }
        s.cash[seat] = 0; s.jf[seat] = 0; s.mc[seat] = 0; s.tri[seat] = 0; s.dj[seat] = 0; s.bk[seat] = true; s.jail[seat] = false
        val rest = ArrayList<Debt>()
        for (d in s.debts) { if (d.seat == seat) continue; if (d.to == seat) d.to = to; rest.add(d) }
        s.debts = rest
        val t = s.trade
        if (t != null && (t.from == seat || t.to == seat)) s.trade = null
        say(s, "@$seat $why${if (to >= 0) ": todo pasa a @$to" else ": todo vuelve al banco"}", "bankrupt", seat = seat)
        val left = s.alive()
        if (left.size == 1) { s.winner = left[0]; s.phase = "over"; say(s, "@${left[0]} gana la partida", "win", seat = left[0]) }
    }

    private fun nextTurn(s: State) {
        s.turn = nextAlive(s, s.turn)
        s.phase = "roll"; s.dbl = 0; s.again = false; s.buy = -1
        if (s.railCd[s.turn] > 0) s.railCd[s.turn]--
    }

    private fun settle(s: State) {
        if (s.phase == "over") return
        if (s.debts.isNotEmpty()) { s.phase = "debt"; return }
        if (s.auction != null) { s.phase = "auction"; return }
        if (s.bk[s.turn]) { s.pendMove = -1; nextTurn(s); return }
        if (s.pendMove >= 0) { val t = s.pendMove; s.pendMove = -1; moveDice(s, s.turn, t); settle(s); return }
        if (s.buy >= 0) { s.phase = "buy"; return }
        s.phase = if (s.again) "roll" else "end"
    }

    // ───── validación de edificios ─────
    fun canBuild(s: State, seat: Int, i: Int): String? {
        val q = s.sq(i)
        if (q.t != "street" || s.own[i] != seat) return "No es tuya"
        if (!s.ownsAll(seat, q.g)) return "Necesitas todo el grupo"
        val g = s.groupOf(q.g)
        if (g.any { s.mort[it] }) return "Hay una hipotecada en el grupo"
        if (s.lvl[i] >= 5) return "Ya tiene hotel"
        if (g.any { s.lvl[it] < s.lvl[i] }) return "Hay que construir por igual en todo el grupo"
        if (s.cash[seat] < s.houseCost(i)) return "No tienes dinero"
        if (if (s.lvl[i] == 4) s.hotelsOf(seat) >= MAX_HOTELS else s.housesOf(seat) >= MAX_HOUSES) return if (s.lvl[i] == 4) "Ya tienes 12 hoteles" else "Ya tienes 32 casas"
        return null
    }

    fun canSell(s: State, seat: Int, i: Int): String? {
        val q = s.sq(i)
        if (q.t != "street" || s.own[i] != seat || s.lvl[i] < 1) return "Sin edificios"
        if (s.groupOf(q.g).any { s.lvl[it] > s.lvl[i] }) return "Hay que vender por igual en todo el grupo"
        if (s.lvl[i] == 5 && s.housesOf(seat) + 4 > MAX_HOUSES) return "No te caben 4 casas más para cambiar el hotel"
        return null
    }

    fun canMortgage(s: State, seat: Int, i: Int): String? {
        val q = s.sq(i)
        if (q.t !in listOf("street", "station", "utility") || s.own[i] != seat) return "No es tuya"
        if (s.mort[i]) return "Ya está hipotecada"
        if (q.g != null && s.groupOf(q.g).any { s.lvl[it] > 0 }) return "Primero vende los edificios del grupo"
        return null
    }

    fun canUnmortgage(s: State, seat: Int, i: Int): String? {
        if (s.sq(i).t !in listOf("street", "station", "utility") || s.own[i] != seat) return "No es tuya"
        if (!s.mort[i]) return "No está hipotecada"
        if (s.cash[seat] < s.unmortgageCost(i)) return "No tienes dinero"
        return null
    }

    // ───── tratos ─────
    private fun parseTrade(seat: Int, arg: String): Trade? {
        val p = arg.split("|")
        if (p.size != 7) return null
        fun list(x: String): List<Int>? {
            if (x.isEmpty()) return emptyList()
            val out = ArrayList<Int>()
            for (t in x.split(",")) out.add(t.toIntOrNull() ?: return null)
            return out
        }
        val to = p[0].toIntOrNull() ?: return null
        val gc = p[1].toIntOrNull() ?: return null; val rc = p[2].toIntOrNull() ?: return null
        val gj = p[5].toIntOrNull() ?: return null; val rj = p[6].toIntOrNull() ?: return null
        return Trade(seat, to, gc, rc, list(p[3]) ?: return null, list(p[4]) ?: return null, gj, rj)
    }

    private fun tradeError(s: State, t: Trade?): String? {
        if (t == null || t.to < 0 || t.to >= s.n || t.to == t.from || s.bk[t.to] || s.bk[t.from]) return "Trato no válido"
        for (v in listOf(t.gc, t.rc, t.gj, t.rj)) if (v < 0) return "Trato no válido"
        if (t.gc + t.rc + t.gp.size + t.rp.size + t.gj + t.rj == 0) return "Trato vacío"
        if (s.cash[t.from] < t.gc || s.cash[t.to] < t.rc) return "No hay dinero suficiente"
        if (s.jf[t.from] < t.gj || s.jf[t.to] < t.rj) return "Falta una carta"
        for ((list, who) in listOf(t.gp to t.from, t.rp to t.to)) {
            if (list.toSet().size != list.size) return "Trato no válido"
            for (i in list) {
                if (i < 0 || i > 39 || s.own[i] != who) return "Una casilla ya no es suya"
                val g = s.sq(i).g
                if (g != null && s.groupOf(g).any { s.lvl[it] > 0 }) return "Hay edificios en un grupo del trato"
            }
        }
        return null
    }

    // ───── acciones ─────
    /**
     * Aplica la acción `code` del jugador `seat`. Devuelve null si ha ido bien o el motivo si no es válida (y entonces el estado no
     * cambia). Con `dry` solo comprueba. Los mensajes del turno quedan en `s.ev`.
     */
    fun act(s: State, seat: Int, code: String, dry: Boolean = false): String? {
        if (!dry) s.ev = ArrayList()
        if (s.phase == "over") return "La partida ha terminado"
        if (seat < 0 || seat >= s.n || s.bk[seat]) return "Jugador no válido"
        if (code.isEmpty()) return "Acción desconocida"
        val op = code[0]; val arg = code.substring(1)
        val mine = seat == s.turn
        val free = s.phase == "roll" || s.phase == "end"
        fun ok(): String? { if (!dry) s.acts++; return null }

        // al principio todos tiran para ver quién empieza
        if (s.phase == "order") {
            if (op != 'r') return "Primero hay que tirar los dados para ver quién empieza"
            if (!s.ordCand[seat] || s.ordRolls[seat] > 0) return "Ya has tirado"
            if (dry) return null
            val a = rint(s, 6) + 1; val b = rint(s, 6) + 1
            s.ordRolls[seat] = a + b
            say(s, "@$seat saca $a y $b", "dice", seat = seat, d1 = a, d2 = b)
            if (!(0 until s.n).any { s.ordCand[it] && s.ordRolls[it] == 0 }) {
                val best = (0 until s.n).filter { s.ordCand[it] }.maxOf { s.ordRolls[it] }
                val win = (0 until s.n).filter { s.ordCand[it] && s.ordRolls[it] == best }
                if (win.size == 1) {
                    s.turn = win[0]; s.phase = "roll"; s.ordCand = BooleanArray(s.n); s.ordRolls = IntArray(s.n)
                    say(s, "@${win[0]} saca $best y empieza la partida", "start")
                } else {
                    s.ordCand = BooleanArray(s.n) { it in win }; s.ordRolls = IntArray(s.n)
                    say(s, "Empate a $best: vuelven a tirar ${win.joinToString(" y ") { "@$it" }}", "start")
                }
            }
            return ok()
        }

        if (s.trade != null && op != 'Y' && op != 'N' && op != 'z') return "Hay un trato pendiente"

        when (op) {
            'r' -> {
                if (!mine || s.phase != "roll") return "No puedes tirar ahora"
                if (dry) return null
                val a = rint(s, 6) + 1; val b = rint(s, 6) + 1
                s.d1 = a; s.d2 = b
                say(s, "@$seat saca $a y $b", "dice", seat = seat, d1 = a, d2 = b)
                if (s.jail[seat]) {
                    if (a == b) {
                        s.jail[seat] = false; s.jt[seat] = 0; s.again = false
                        say(s, "@$seat saca dobles y sale de la cárcel", "free")
                        moveDice(s, seat, a + b)
                    } else if (++s.jt[seat] >= s.jmax[seat]) {
                        s.jail[seat] = false; s.jt[seat] = 0; s.again = false
                        say(s, "@$seat agota su condena: paga la multa y sale")
                        pay(s, seat, JAIL_FINE, -1, " de multa")
                        if (s.debts.isNotEmpty()) s.pendMove = a + b else moveDice(s, seat, a + b)
                    } else { say(s, "@$seat sigue en la cárcel (${s.jt[seat]}/${s.jmax[seat]})"); s.again = false }
                } else if (a == b) {
                    if (++s.dbl >= 3) { say(s, "@$seat saca tres dobles seguidos"); sendJail(s, seat, "") }
                    else {
                        s.again = true
                        if (s.cfg.dblBonus) { val v = if (s.dbl == 1) 50 else 100; s.cash[seat] += v; say(s, "@$seat gana ${eur(v)} por dobles", "gain", seat = seat, amt = v) }
                        moveDice(s, seat, a + b)
                    }
                } else { s.again = false; s.dbl = 0; moveDice(s, seat, a + b) }
                settle(s)
                return ok()
            }
            'p' -> {
                if (!mine || s.phase != "roll" || !s.jail[seat]) return "No estás en la cárcel"
                if (s.cash[seat] < JAIL_FINE) return "No tienes dinero"
                if (dry) return null
                s.cash[seat] -= JAIL_FINE; s.jail[seat] = false; s.jt[seat] = 0
                say(s, "@$seat paga ${eur(JAIL_FINE)} y sale de la cárcel", "free", seat = seat, to = -1, amt = JAIL_FINE)
                return ok()
            }
            'c' -> {
                if (!mine || s.phase != "roll" || !s.jail[seat] || s.jf[seat] < 1) return "No puedes usar la carta"
                if (dry) return null
                s.jf[seat]--; s.jail[seat] = false; s.jt[seat] = 0
                for (w in 0..1) if (s.deck(w).held >= 0 && s.heldBy[w] == seat) { s.deck(w).held = -1; s.heldBy[w] = -1; break }
                say(s, "@$seat usa la carta y sale de la cárcel", "free")
                return ok()
            }
            'b' -> {
                if (!mine || s.phase != "buy") return "No puedes comprar ahora"
                val q = s.sq(s.buy)
                if (s.cash[seat] < q.p) return "No tienes dinero"
                if (dry) return null
                s.cash[seat] -= q.p; s.own[s.buy] = seat
                say(s, "@$seat compra ${q.n} por ${eur(q.p)}", "buy", seat = seat, sq = s.buy, amt = q.p)
                s.buy = -1; settle(s)
                return ok()
            }
            'n' -> {
                if (!mine || s.phase != "buy") return "No hay nada que decidir"
                if (dry) return null
                say(s, "@$seat no compra ${s.sq(s.buy).n}")
                s.buy = -1; settle(s)
                return ok()
            }
            'a' -> {
                if (!mine || s.phase != "buy" || !s.cfg.auction) return "No hay subasta"
                if (dry) return null
                val order = ArrayList<Int>()
                for (k in 1..s.n) { val t = (s.turn + k) % s.n; if (!s.bk[t]) order.add(t) }
                s.auction = Auction(s.buy, 0, -1, order, order[0])
                say(s, "Subasta de ${s.sq(s.buy).n}", "auction")
                s.buy = -1; settle(s)
                return ok()
            }
            'i', 'f' -> {
                val au = s.auction
                if (s.phase != "auction" || au == null || au.cur != seat) return "No es tu turno de pujar"
                if (op == 'i') {
                    val amt = arg.toIntOrNull()
                    if (amt == null || amt <= au.bid || amt > s.cash[seat]) return "Puja no válida"
                    if (dry) return null
                    au.bid = amt; au.bidder = seat
                    say(s, "@$seat puja ${eur(amt)}", "bid", seat = seat, amt = amt)
                } else {
                    if (dry) return null
                    say(s, "@$seat se retira")
                }
                val pos = au.active.indexOf(seat)
                if (op == 'f') au.active.removeAt(pos)
                if (au.active.isEmpty() || (au.active.size == 1 && au.bidder == au.active[0])) {
                    if (au.bidder >= 0) {
                        s.cash[au.bidder] -= au.bid; s.own[au.sq] = au.bidder
                        say(s, "@${au.bidder} gana la subasta de ${s.sq(au.sq).n} por ${eur(au.bid)}", "buy", seat = au.bidder, sq = au.sq, amt = au.bid)
                    } else say(s, "Nadie puja por ${s.sq(au.sq).n}")
                    s.auction = null; settle(s)
                } else au.cur = au.active[(if (op == 'f') pos else pos + 1) % au.active.size]
                return ok()
            }
            'e' -> {
                if (!mine || s.phase != "end") return "No puedes terminar el turno"
                if (dry) return null
                nextTurn(s)
                return ok()
            }
            'h', 's', 'm', 'u' -> {
                val i = arg.toIntOrNull()
                if (i == null || i < 0 || i > 39) return "Casilla no válida"
                val inDebt = s.phase == "debt" && s.debts.firstOrNull()?.seat == seat && (op == 's' || op == 'm')
                if (!inDebt && !(mine && free)) return "Ahora no puedes"
                val err = when (op) { 'h' -> canBuild(s, seat, i); 's' -> canSell(s, seat, i); 'm' -> canMortgage(s, seat, i); else -> canUnmortgage(s, seat, i) }
                if (err != null) return err
                if (dry) return null
                val q = s.sq(i); val c = s.houseCost(i)
                when (op) {
                    'h' -> {
                        s.cash[seat] -= c
                        s.lvl[i]++
                        say(s, "@$seat construye ${if (s.lvl[i] == 5) "un hotel" else "una casa"} en ${q.n}", "build", seat = seat, sq = i, amt = -c)
                    }
                    's' -> {
                        s.cash[seat] += c / 2
                        s.lvl[i]--
                        say(s, "@$seat vende un edificio de ${q.n}", "build", seat = seat, sq = i, amt = c / 2)
                    }
                    'm' -> {
                        s.mort[i] = true; s.cash[seat] += q.p / 2
                        say(s, "@$seat hipoteca ${q.n} y cobra ${eur(q.p / 2)}", "mort", seat = seat, sq = i, amt = q.p / 2)
                    }
                    else -> {
                        s.cash[seat] -= s.unmortgageCost(i); s.mort[i] = false
                        say(s, "@$seat deshipoteca ${q.n}", "mort", seat = seat, sq = i, amt = -s.unmortgageCost(i))
                    }
                }
                return ok()
            }
            'R' -> {
                // dado triple (comprado en la tienda): se tiran tres dados y no se aplica nada de los dobles
                if (!mine || s.phase != "roll" || s.tri[seat] < 1 || s.jail[seat]) return "No puedes tirar el dado triple ahora"
                if (dry) return null
                s.tri[seat]--
                val a = rint(s, 6) + 1; val b = rint(s, 6) + 1; val c3 = rint(s, 6) + 1
                s.d1 = a; s.d2 = b
                say(s, "@$seat usa el dado triple y saca $a, $b y $c3", "dice", seat = seat, d1 = a, d2 = b, d3 = c3)
                s.again = false; s.dbl = 0
                moveDice(s, seat, a + b + c3)
                settle(s)
                return ok()
            }
            'w' -> {
                // doble condena (comprada en la tienda): en la cárcel, en vez de 3 turnos puedes estar hasta 6
                if (!mine || s.phase != "roll" || !s.jail[seat] || s.dj[seat] < 1 || s.jmax[seat] > 3) return "No puedes usar la doble condena"
                if (dry) return null
                s.dj[seat]--; s.jmax[seat] = 6
                say(s, "@$seat usa la doble condena: puede estar hasta 6 turnos en la cárcel", "msg", seat = seat)
                return ok()
            }
            'o' -> {
                // tienda de La Peza (regla opcional): se compra estando en La Peza
                if (!s.cfg.shop || !(mine && free) || s.pos[seat] != 20 || s.jail[seat]) return "La tienda está en La Peza"
                val k = arg.toIntOrNull() ?: return "Objeto no válido"
                val item = SHOP[k] ?: return "Objeto no válido"
                if (s.cash[seat] < item.second) return "No tienes dinero"
                if (dry) return null
                s.cash[seat] -= item.second
                if (k == 1) s.mc[seat]++ else if (k == 2) s.tri[seat]++ else s.dj[seat]++
                say(s, "@$seat compra ${item.first} en la tienda por ${eur(item.second)}", "buy", seat = seat, amt = item.second)
                return ok()
            }
            'x' -> {
                // movimiento por raíles: en vez de tirar, pagas y viajas a otra estación tuya (la siguiente 50 €, la de enfrente 150 €); solo hacia delante
                if (!s.cfg.rails || !mine || s.phase != "roll" || s.dbl > 0 || s.again) return "Ahora no puedes viajar en tren"
                if (s.jail[seat]) return "Estás en la cárcel"
                if (s.railCd[seat] > 0) return "Tras viajar en tren, el turno siguiente toca tirar los dados"
                val st = listOf(5, 15, 25, 35)
                val from = s.pos[seat]
                val k = st.indexOf(from)
                val to = arg.toIntOrNull() ?: return "Estación no válida"
                val j = st.indexOf(to)
                if (k < 0 || s.own[from] != seat || s.mort[from]) return "Tienes que estar en una estación tuya"
                if (j < 0 || s.own[to] != seat || s.mort[to]) return "Esa estación no es tuya"
                val step = (j - k + 4) % 4
                val cost = if (step == 1) 50 else if (step == 2) 150 else 0
                if (cost == 0) return "Solo a la siguiente o a la de enfrente"
                if (s.cash[seat] < cost) return "No tienes dinero"
                if (dry) return null
                s.cash[seat] -= cost
                say(s, "@$seat paga ${eur(cost)} y viaja en tren hasta ${s.sq(to).n}", "rail", seat = seat, from = from, to = to, amt = cost)
                if (to < from) goPay(s, seat, false) // cruza la Salida
                s.pos[seat] = to; s.again = false; s.dbl = 0; s.railCd[seat] = 2 // en tu siguiente turno no puedes volver a viajar
                settle(s)
                return ok()
            }
            'k' -> {
                val d = s.debts.firstOrNull()
                if (s.phase != "debt" || d == null || d.seat != seat || !d.rent || s.mc[seat] < 1) return "No puedes usar Maricarmen"
                if (dry) return null
                s.debts.removeAt(0); s.mc[seat]--
                if (s.mcBy == seat) { s.deckC.held2 = -1; s.mcBy = -1 } // si era la de la baraja, vuelve a ella; la comprada simplemente se gasta
                say(s, "@$seat usa Maricarmen y no paga el alquiler", "free", seat = seat)
                settle(s)
                return ok()
            }
            'd' -> {
                val d = s.debts.firstOrNull()
                if (s.phase != "debt" || d == null || d.seat != seat) return "No tienes deudas"
                if (s.cash[seat] < d.amt) return "No tienes dinero suficiente"
                if (dry) return null
                s.debts.removeAt(0)
                if (d.tax && s.cfg.pot) s.pot += d.amt
                s.cash[seat] -= d.amt
                if (d.to >= 0) s.cash[d.to] += d.amt
                say(s, "@$seat paga ${eur(d.amt)}${if (d.to >= 0) " a @${d.to}" else ""}${d.why}", "pay", seat = seat, to = d.to, amt = d.amt)
                settle(s)
                return ok()
            }
            'q' -> {
                val d = s.debts.firstOrNull()
                if (s.phase != "debt" || d == null || d.seat != seat) return "No tienes deudas"
                if (dry) return null
                bankrupt(s, seat, d.to, "se declara en quiebra")
                settle(s)
                return ok()
            }
            'z' -> {
                if (!(mine && free) && !(s.phase == "debt" && s.debts.firstOrNull()?.seat == seat)) return "Solo puedes abandonar en tu turno"
                if (dry) return null
                bankrupt(s, seat, -1, "abandona la partida")
                if (s.auction != null) s.auction = null
                s.buy = -1
                if (s.phase != "over") { if (s.turn == seat || s.bk[s.turn]) nextTurn(s); settle(s) }
                return ok()
            }
            'T' -> {
                if (!(mine && free)) return "Solo en tu turno"
                val t = parseTrade(seat, arg)
                val err = tradeError(s, t)
                if (err != null) return err
                if (dry) return null
                s.trade = t
                say(s, "@$seat propone un trato a @${t!!.to}", "trade")
                return ok()
            }
            'Y' -> {
                val t = s.trade
                if (t == null || t.to != seat) return "No hay trato para ti"
                val err = tradeError(s, t)
                if (err != null) return err
                if (dry) return null
                s.cash[t.from] += t.rc - t.gc; s.cash[t.to] += t.gc - t.rc
                for (i in t.gp) s.own[i] = t.to
                for (i in t.rp) s.own[i] = t.from
                s.jf[t.from] += t.rj - t.gj; s.jf[t.to] += t.gj - t.rj
                var gj = t.gj; var rj = t.rj
                for (w in 0..1) {
                    if (s.deck(w).held < 0) continue
                    if (s.heldBy[w] == t.from && gj > 0) { s.heldBy[w] = t.to; gj-- } else if (s.heldBy[w] == t.to && rj > 0) { s.heldBy[w] = t.from; rj-- }
                }
                s.trade = null
                say(s, "@${t.to} acepta el trato de @${t.from}", "trade")
                return ok()
            }
            'N' -> {
                val t = s.trade
                if (t == null || (t.to != seat && t.from != seat)) return "No hay trato"
                if (dry) return null
                s.trade = null
                say(s, if (t.from == seat) "@$seat cancela el trato" else "@$seat rechaza el trato", "trade")
                return ok()
            }
            else -> return "Acción desconocida"
        }
    }

    fun applyText(s: State, text: String): String? {
        val k = text.indexOf('.')
        if (k < 1) return "Acción no válida"
        val seat = text.substring(0, k).toIntOrNull() ?: return "Acción no válida"
        return act(s, seat, text.substring(k + 1))
    }

    fun replay(data: Data, n: Int, cfg: Cfg, seed: Int, acts: List<String>): State {
        val s = initGame(data, n, cfg, seed)
        for (a in acts) applyText(s, a)
        return s
    }
}
