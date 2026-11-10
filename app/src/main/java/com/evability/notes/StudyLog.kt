package com.evability.notes

import android.content.Context
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Registro del tiempo de estudio efectivo (sin descansos) de cada día, para la pantalla de estadísticas.
 * Se guarda un total en milisegundos por fecha; si un tramo cruza la medianoche se reparte entre los dos días.
 */
object StudyLog {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("evability_study", Context.MODE_PRIVATE)

    @Synchronized
    fun add(ctx: Context, fromWall: Long, toWall: Long) {
        if (toWall <= fromWall) return
        val sp = sp(ctx)
        val ed = sp.edit()
        val zone = ZoneId.systemDefault()
        var start = Instant.ofEpochMilli(fromWall).atZone(zone)
        val end = Instant.ofEpochMilli(toWall).atZone(zone)
        while (true) {
            val day = start.toLocalDate()
            val midnight = day.plusDays(1).atStartOfDay(zone)
            val segEnd = if (end.isAfter(midnight)) midnight else end
            val key = day.toString()
            ed.putLong(key, sp.getLong(key, 0L) + Duration.between(start, segEnd).toMillis())
            if (!end.isAfter(midnight)) break
            start = segEnd
        }
        ed.apply()
    }

    private fun remoteSp(ctx: Context) = ctx.applicationContext.getSharedPreferences("evability_study_remote", Context.MODE_PRIVATE)

    private fun read(sp: android.content.SharedPreferences): Map<LocalDate, Long> {
        val out = HashMap<LocalDate, Long>()
        for ((k, v) in sp.all) {
            val ms = v as? Long ?: continue
            val d = runCatching { LocalDate.parse(k) }.getOrNull() ?: continue
            out[d] = ms
        }
        return out
    }

    /** Lo estudiado en este dispositivo, por día (es lo que se sube a la cuenta). */
    fun local(ctx: Context): Map<LocalDate, Long> = read(sp(ctx))

    /** Lo estudiado en tus otros dispositivos (se baja de la cuenta). */
    fun setRemote(ctx: Context, m: Map<String, Long>) {
        val ed = remoteSp(ctx).edit().clear()
        m.forEach { (d, ms) -> ed.putLong(d, ms) }
        ed.apply()
    }

    /** Milisegundos estudiados por día: este dispositivo más los otros de la cuenta. */
    fun all(ctx: Context): Map<LocalDate, Long> {
        val out = HashMap(local(ctx))
        for ((d, ms) in read(remoteSp(ctx))) out[d] = (out[d] ?: 0L) + ms
        return out
    }
}
