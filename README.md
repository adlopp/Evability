# Evability

Evability es una **app sencilla para editar PDFs** en Android, pensada para tablets con lápiz (probada en una Redmi Pad 2 Pro con el Redmi Smart Pen). Importas un PDF, escribes y dibujas sobre él y puedes volver a abrirlo desde la lista de recientes.

## Qué puede hacer

- **Importar PDFs** y tenerlos en una lista de **recientes**, con miniatura y retomando por la última página que viste.
- **Anotar con el lápiz:** bolígrafo, resaltador y borrador, con colores y grosor ajustables. El lápiz dibuja y el dedo desplaza la página (con zoom por pellizco e inercia).
- **Escribir texto** sobre el PDF y arrastrarlo; pellizcar para cambiar su tamaño.
- **Cambiar texto del PDF:** tapa un fragmento con el color del fondo y escribe el nuevo encima.
- **Pegatinas** (packs de gatitos, hamsters, pollitos y chinchillas incluidos, emojis y tus propias imágenes), que se mueven, giran y cambian de tamaño con los dedos.
- **Post-its** para dibujar encima.
- **Plantillas:** mensual, semanal y hoja en blanco.
- **Añadir páginas en blanco** a cualquier documento.
- **Exportar** el PDF con todas las anotaciones.
- **Tema oscuro** y botón para ocultar la barra de herramientas.
- **Actualizaciones** desde esta página con un botón (icono de dos flechas, arriba a la derecha).

## Instalar

1. Entra en [Releases](../../releases/latest) y descarga `Evability.apk` en tu móvil o tablet Android (8.0 o superior).
2. Ábrelo y, si Android lo pide, permite instalar apps desde esa fuente.
3. Para actualizar más adelante basta con pulsar el botón de actualizar dentro de la app.

## Compilar

Necesitas JDK 17 y el SDK de Android (plataforma 35):

```
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/`.

## Notas

- Las pegatinas de gatitos, hamsters, pollitos y chinchillas son dibujos originales hechos para este proyecto.
- Al exportar, las páginas del PDF quedan como imagen (el texto original deja de ser seleccionable).
- Las anotaciones se guardan dentro de la app; el PDF original no se modifica.
