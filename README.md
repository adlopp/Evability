# Evability

Evability es una **app sencilla para editar PDFs** en Android, pensada para tablets con lápiz (probada en una Redmi Pad 2 Pro con el Redmi Smart Pen). Importas un PDF, escribes y dibujas sobre él y puedes volver a abrirlo desde la lista de recientes.

## Qué puede hacer

- **Importar PDFs** y organizarlos: recientes agrupados por fecha, tarjeta «Continuar donde lo dejaste», buscador, orden, **favoritos**, **carpetas**, selección múltiple, renombrar, duplicar y eliminar con «Deshacer». Cada tarjeta muestra tus anotaciones y por qué página vas.
- **Anotar con el lápiz:** bolígrafo, resaltador y borrador, con colores y grosor ajustables. El lápiz dibuja y el dedo desplaza la página (con zoom por pellizco e inercia).
- **Escribir texto** sobre el PDF y arrastrarlo; pellizcar para cambiar su tamaño.
- **Cambiar texto del PDF:** tapa un fragmento con el color del fondo y escribe el nuevo encima.
- **Pegatinas** (packs de gatitos, hamsters, pollitos y chinchillas incluidos, emojis y tus propias imágenes), que se mueven, giran y cambian de tamaño con los dedos.
- **Post-its** para dibujar encima.
- **Plantillas:** mensual, semanal y hoja en blanco.
- **Añadir páginas en blanco** a cualquier documento.
- **Exportar** el PDF con todas las anotaciones.
- **Tema oscuro**, color de la app a elegir (rosa, lila, menta…) y botón para ocultar la barra de herramientas.
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

## Versión web para iPad / iPhone (carpeta `docs/`)

Una app web instalable (PWA) con las funciones principales de Evability, pensada para iPad: biblioteca, plantillas, anotaciones con lápiz,
selección de texto, papelera, Ternurín, «Analizar» con Gemini y copias de seguridad. Funciona sin conexión una vez instalada.

- **Publicarla:** en GitHub, *Settings → Pages → Deploy from a branch → `main` / carpeta `/docs`*. Queda en `https://<usuario>.github.io/Evability/`.
- **Instalarla en el iPad:** abrir esa dirección en Safari → Compartir → *Añadir a pantalla de inicio*.
- No necesita compilar nada: son archivos estáticos (PDF.js, pdf-lib y JSZip van incluidos en `docs/vendor/`).
- Para probarla en local: `python -m http.server 8000 --directory docs` y abrir `http://localhost:8000`.
- Al cambiar archivos de la app, sube el número de `CACHE` en `docs/sw.js` para que los dispositivos descarguen la versión nueva.
- Límites frente a la app de Android: no importa Word, el Ternurín solo suena con la app abierta y «Analizar» admite PDFs de hasta 14 MB.

## Notas

- Las pegatinas de gatitos, hamsters, pollitos y chinchillas son dibujos originales hechos para este proyecto.
- Al exportar, las páginas del PDF quedan como imagen (el texto original deja de ser seleccionable).
- Las anotaciones se guardan dentro de la app; el PDF original no se modifica.

## Amigos (perfil, punto verde y corazones)

La parte social usa Firebase (plan gratuito Spark) por su API REST, sin SDK, en Android y en la web.
Para montarla en otro proyecto de Firebase:

1. Authentication → Método de acceso → activar **Correo electrónico/contraseña**.
2. Firestore Database → crear en modo producción → pestaña **Reglas** → pegar [`firestore.rules`](firestore.rules) → Publicar.
3. Poner el ID del proyecto y la clave de API web en `Social.kt` y `docs/js/social.js`.

Se entra con correo y contraseña (el correo permite recuperar la contraseña y solo lo ve Firebase Auth) y cada persona elige un nombre de usuario público. Los datos guardados en la nube son el nombre,
una foto de 96×96 y la última conexión; los documentos nunca se suben. Los corazones solo llegan con la app abierta.
