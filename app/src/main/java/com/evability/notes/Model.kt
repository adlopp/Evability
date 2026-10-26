package com.evability.notes

import androidx.compose.ui.geometry.Offset

enum class Tool { SELECT, PEN, HIGHLIGHTER, ERASER, TEXT, STICKER, POSTIT, COVER }

/**
 * Las coordenadas de las anotaciones están normalizadas respecto al ANCHO de la página
 * (x e y se dividen entre el ancho), así que no dependen del zoom ni de la pantalla.
 */
sealed interface Ann {
    val id: Long
    val page: Int
}

data class Stroke(
    override val id: Long,
    override val page: Int,
    val highlighter: Boolean,
    val color: Int,
    val width: Float,
    val points: List<Offset>,
) : Ann

data class TextNote(
    override val id: Long,
    override val page: Int,
    val text: String,
    val color: Int,
    val size: Float,
    val x: Float,
    val y: Float,
) : Ann

/** Pegatina centrada en (x, y); `size` es el lado como fracción del ancho de página y `rot` está en grados. */
data class Sticker(
    override val id: Long,
    override val page: Int,
    val src: String,
    val x: Float,
    val y: Float,
    val size: Float,
    val rot: Float,
) : Ann

/** Trazo dibujado sobre un post-it: puntos en coordenadas locales (centradas en el post-it, en unidades de su lado). */
data class PostitStroke(val color: Int, val width: Float, val highlighter: Boolean, val points: List<Offset>)

/** Post-it cuadrado centrado en (x, y) donde se dibuja. `size` es el lado (fracción del ancho de página). */
data class Postit(
    override val id: Long,
    override val page: Int,
    val x: Float,
    val y: Float,
    val size: Float,
    val rot: Float,
    val strokes: List<PostitStroke> = emptyList(),
) : Ann

/** Rectángulo opaco que tapa parte de la página (esquina superior izquierda + tamaño). Sirve para "cambiar" texto del PDF. */
data class Cover(
    override val id: Long,
    override val page: Int,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val color: Int,
) : Ann

fun Ann.moved(dx: Float, dy: Float): Ann = when (this) {
    is Stroke -> copy(points = points.map { Offset(it.x + dx, it.y + dy) })
    is TextNote -> copy(x = x + dx, y = y + dy)
    is Sticker -> copy(x = x + dx, y = y + dy)
    is Postit -> copy(x = x + dx, y = y + dy)
    is Cover -> copy(x = x + dx, y = y + dy)
}

data class DocInfo(
    val id: String,
    val name: String,
    val pages: Int,
    val lastOpened: Long,
    val added: Long,
    val lastPage: Int = 0,
    val extra: Int = 0, // páginas en blanco añadidas al final
    val favorite: Boolean = false,
    val folder: String = "", // "" = sin carpeta
    val template: String = "", // TemplateKind con el que se creó ("" = importado)
    val extraKinds: List<String> = emptyList(), // por cada página extra: "" = en blanco, o el nombre de una plantilla
    val hidden: List<Int> = emptyList(), // páginas eliminadas (el PDF original no se toca, solo se ocultan)
) {
    /** Plantilla mensual/semanal de este documento (los creados antes de guardar el tipo se reconocen por el nombre). */
    val templateKind: TemplateKind?
        get() = (TemplateKind.entries.firstOrNull { it.name == template } ?: TemplateKind.entries.firstOrNull { it.docName == name })
            ?.takeIf { it != TemplateKind.BLANK }
}

/** `size`/`cover`: al "cambiar texto" se guarda el tapado junto con el texto nuevo (y se descarta si se cancela). */
data class TextEdit(val existing: TextNote?, val page: Int, val pos: Offset, val size: Float? = null, val cover: Cover? = null)
