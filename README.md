# Relaycast

**Relaycast es un reproductor de vídeo para tus propios archivos.** Las apps (iPhone, iPad, Android y Android TV) reproducen las series y películas que guardas en tu PC, con subtítulos, varias pistas de audio, progreso compartido entre dispositivos y envío a la TV por AirPlay o Google Cast. No incluye ni ofrece contenido: sólo reproduce lo que tú compartes.

Tiene tres partes:

```text
Carpetas de vídeo ──► Relay Server (catálogo + HLS + subtítulos) ──► App (iPhone, iPad, Android, Android TV) ──► AirPlay / Cast / TV
        ▲                        ▲
        └── Companion (Windows): instala el servidor y administra tu biblioteca
```

| Parte | Qué es | Dónde |
| --- | --- | --- |
| **Apps** | El reproductor: iOS/iPadOS (SwiftUI) y Android (teléfono, tablet y TV, Kotlin). | [`ios/`](ios), [`android/`](android) |
| **Relay Server** | Servidor autoalojado, sin dependencias de npm. Escanea tus carpetas, ofrece el catálogo a las apps y convierte cada vídeo a HLS con `ffmpeg`. | [`server/`](server) |
| **Companion** | App de Windows (instalador `.exe`) que incluye el servidor y `ffmpeg`, y sirve para elegir carpetas, editar los metadatos y conectar las apps con un QR. | [`companion/`](companion) |

## Empezar

1. **En el PC con los vídeos**: instala el companion (`Relaycast-Setup-<versión>.exe`), añade las carpetas con tus series y películas y deja que las escanee.
2. **En la app** (iPhone, iPad, Android o TV): abre el asistente de configuración inicial, elige **Escanear código de configuración** y apunta al QR que muestra el companion. El teléfono y la TV deben estar en la misma red que el PC; para verlos desde fuera de casa, publica el servidor con HTTPS (ver [Servidor en otro equipo](#servidor-en-otro-equipo)).
3. Crea tu cuenta (o usa una que ya tengas), elige el perfil y reproduce.

## El reproductor

Las apps están en español e inglés (usan el idioma del dispositivo).

- **Catálogo**: Recientes, Buscar y filtros (género, año, estado, tipo, clasificación, orden), con las series que salen de tus carpetas. La ficha muestra sinopsis, temporadas relacionadas y episodios con su progreso.
- **Reproducción**: pantalla completa con los controles flotantes, doble toque ±10 s, gestos de brillo y volumen, pellizco para ajustar o llenar, velocidad, episodio anterior/siguiente, siguiente episodio automático e imagen dentro de imagen. **Saltar intro/outro** aparece cuando el episodio tiene esos rangos.
- **Audio y subtítulos**: cada pista de audio es una «versión» (la original es *Sub*, las demás *Dub*) y se cambia en el mismo punto del episodio. Los subtítulos (incrustados o `.srt`, `.ass`, `.vtt` junto al vídeo) llegan como pistas WebVTT; el idioma preferido se recuerda por cuenta. En iPhone y iPad los dibuja AVPlayer con el estilo de Accesibilidad; en Android, el estilo del sistema o un contorno legible. La traducción de subtítulos y de la sinopsis se hace en el dispositivo.
- **Continuar viendo**: guarda el punto exacto de las últimas 50 series y las ordena en *Nuevos episodios*, *En curso*, *Al día* y *Completados*; sincroniza el progreso entre todos tus dispositivos.
- **Descargas sin conexión**: un episodio, los siguientes o la serie completa, con todos sus subtítulos. Se reproducen sin red y se pueden borrar solas al verlas.
- **Avisos de nuevos episodios**: opcionales; sólo avisan de episodios que ya están en tu biblioteca.
- **Cuentas y perfiles**: cada token es una cuenta con su historial y sus preferencias; el perfil (nombre, foto o avatar, correo) vive en tu servidor. Los perfiles se pasan de un dispositivo a otro con un código QR.
- **TV**: AirPlay (iPhone/iPad), Google Cast (Chromecast, Google TV) y la app de Android TV como TV conectada, que se controla desde el teléfono aunque estén en redes distintas.
- **Pantallas anchas**: dos columnas en iPad, iPhone Duo y plegables abiertos; postura de mesa con el vídeo arriba y los controles abajo.

## Relay Server

Lee carpetas de vídeo de tu PC (o de un NAS o servidor) y arma con ellas el catálogo. Las apps hablan con él por HTTP; no consulta ningún servicio de terceros.

### Tu biblioteca

Una carpeta de biblioteca contiene una carpeta por serie (o película) y, dentro, los episodios:

```text
Videos/
├── Mi Serie (2021)/
│   ├── metadata.json            ← opcional
│   ├── poster.jpg               ← opcional (poster, cover, folder, thumb o front)
│   ├── Season 1/
│   │   ├── Mi Serie - 01.mkv
│   │   ├── Mi Serie - 01.es.srt ← subtítulos junto al vídeo
│   │   └── Mi Serie - 02.mkv
│   └── Season 2/
│       └── Mi Serie S02E01.mkv
├── Una Película (2019)/
│   └── Una Película (2019).mkv
└── Suelto - 03.mp4              ← los archivos sueltos se agrupan por título
```

- **Series y temporadas**: la carpeta que contiene los vídeos es la serie. `Season N`, `Temporada N` o `Specials` dentro de ella son temporadas relacionadas. Una carpeta con un solo vídeo sin número de episodio es una película.
- **Episodios**: el número sale del nombre (`S01E05`, `E05`, `Episode 5`, `1x05`, ` - 05`, `[05]`, un número al final). Si faltan, van por orden de nombre. Se ignoran las etiquetas de lanzamiento (`[Grupo]`, `1080p`, `x265`…).
- **Subtítulos**: SRT, ASS, WebVTT y mov_text, incrustados o junto al vídeo, se convierten a WebVTT. Los de imagen (PGS, VobSub) no se pueden usar.
- **Audio**: cada pista es una versión; el idioma original (japonés si existe, si no la pista predeterminada) es Sub y los demás son Dub.
- **Intro y outro**: se toman de los capítulos del archivo (`Opening`, `OP`, `Intro`, `Ending`, `ED`, `Outro`, `Credits`) o de lo que fijes en el companion.
- **Portada**: `poster.jpg` en la carpeta, la que elijas en el companion o, si no hay, un fotograma del primer episodio.

Todo lo que no se pueda deducir de los nombres se puede escribir en un `metadata.json` o, más cómodo, editar desde el companion (ver [Catálogo y metadatos](#catálogo-y-metadatos)):

```json
{
  "title": "Mi Serie", "titleJp": "私のシリーズ", "synopsis": "De qué trata.",
  "year": 2021, "season": "fall", "status": "finished", "mediaType": "TV", "genres": ["Comedia", "Drama"],
  "rating": "PG-13", "score": 8.4, "totalEpisodes": 24, "dub": false, "adult": false
}
```

### Cómo se reproduce

El servidor corta el vídeo en segmentos HLS de unos 6 s:

- **H.264 de 8 bits y progresivo**: se **copia** sin recodificar (casi no usa CPU). El archivo se reempaqueta una vez en segundo plano, mucho más rápido que la reproducción.
- **Cualquier otra cosa** (HEVC, 10 bits, entrelazado…): se **transcodifica** a H.264 de 8 bits por segmentos. Si hay una GPU compatible se usa su codificador (`h264_nvenc`, `h264_qsv`, `h264_amf`); si no, `libx264`. Un equipo modesto puede no alcanzar tiempo real con HEVC de 1080p: recodifica esos archivos.
- **Audio**: AAC, AC-3, E-AC-3 y MP3 se copian; el resto (Opus, FLAC, DTS…) se convierte a AAC.

### Companion de Windows

El companion se instala con un `.exe`, incluye el servidor, `ffmpeg` y `ffprobe`, y no necesita nada más:

1. Ejecuta `Relaycast-Setup-<versión>.exe`. Pide permisos de administrador para abrir el Firewall de Windows (entrada, sólo redes privadas), para que el teléfono y la TV lleguen al PC.
2. Relaycast arranca en segundo plano (icono en la bandeja) y con Windows. Cerrar la ventana no detiene el servidor; **Salir** en la bandeja sí.
3. **Añadir carpeta…** y elige donde guardas tus series y películas. Se escanean al momento y cada 10 minutos; **Buscar vídeos nuevos** fuerza la búsqueda.
4. **Conecta la app**: el QR de la ventana configura la dirección del servidor y, con la casilla marcada, permite crear la cuenta. Sin QR, escribe la dirección que muestra la ventana (por ejemplo `http://192.168.1.20:8787`).

El companion crea el token de administrador (`%APPDATA%\Relaycast\config.json`) y se lo pasa al servidor; los datos (historial, cuentas, preferencias, caché) viven en `%APPDATA%\Relaycast\data` y no se borran al desinstalar. El instalador no está firmado: SmartScreen avisará la primera vez.

#### Catálogo y metadatos

La pestaña **Catálogo y metadatos** del companion edita todo lo que las apps muestran. Los cambios se guardan en `data/catalog.json`, no tocan tus archivos y se pueden revertir con **Restablecer**:

- **Serie**: título, título original, sinopsis, géneros, tipo (TV, película, ONA, OVA, especial), estado (finalizada, en emisión, próximamente), clasificación, puntuación, año, temporada del año, episodios en total, próximo episodio, doblaje y contenido para adultos.
- **Portada**: elige una imagen JPG, PNG o WebP; **Quitar portada propia** vuelve a la de la carpeta.
- **Relaciones y temporadas**: número y etiqueta de temporada, y una **franquicia**: las series con la misma franquicia aparecen como temporadas relacionadas en la app, aunque estén en carpetas distintas.
- **Episodios**: número, título, doblaje, y los rangos de **intro** y **outro** (m:ss). **Detectar** los lee de los capítulos del archivo y **Sin salto** desactiva el salto en ese episodio.

Para compilar el instalador (Windows, Node 24):

```sh
cd companion
npm ci
npm run dist        # → companion/dist/Relaycast-Setup-<versión>.exe
npm start           # ejecuta el companion sin instalar
```

`npm run dist` copia `server/` y los binarios de `ffmpeg` y `ffprobe` a `companion/stage/` y empaqueta con electron-builder (NSIS). En un `npm ci` nuevo, npm bloquea el script de instalación de `ffmpeg-static`: apruébalo una vez con `npm install-scripts approve ffmpeg-static`. `ffmpeg-static` distribuye un `ffmpeg` con licencia GPL.

### Servidor en otro equipo

Requisitos: Node.js 24 o posterior y `ffmpeg` y `ffprobe` en el `PATH` (o en `FFMPEG_PATH` y `FFPROBE_PATH`).

```sh
cd server
npm test
LIBRARY_PATHS="/ruta/a/mis/videos" SERVER_ADMIN_TOKEN="$(openssl rand -hex 32)" node server.mjs
curl http://127.0.0.1:8787/health   # {"ok":true,"provider":"LOCAL","version":"…","library":{"series":12,"files":240,"scanning":false}}
```

Para un NAS o un servidor siempre encendido, con HTTPS:

1. Instala Node.js 24 y `ffmpeg`, clona el proyecto en `/opt/relaycast` y comprueba que `server/` arranca.
2. Crea el usuario de servicio y el archivo privado de entorno con el token administrador:

   ```sh
   sudo useradd --system --home /var/lib/relaycast --shell /usr/sbin/nologin relaycast
   sudo install -m 600 -o root -g relaycast /dev/null /etc/relaycast.env
   openssl rand -hex 32   # guárdalo como SERVER_ADMIN_TOKEN=… en ese archivo
   ```

3. Copia [`deploy/relaycast-server.service.example`](deploy/relaycast-server.service.example) a `/etc/systemd/system/relaycast-server.service`, ajusta `LIBRARY_PATHS` y `ReadOnlyPaths` y actívalo con `sudo systemctl enable --now relaycast-server`.
4. Usa [`deploy/Caddyfile.example`](deploy/Caddyfile.example) con tu dominio: Caddy obtiene el certificado HTTPS.

No expongas el puerto `8787` directamente a Internet. HTTP sólo sirve dentro de la red local.

### Variables de entorno

| Variable | Predeterminado | Uso |
| --- | --- | --- |
| `SERVER_HOST` / `SERVER_PORT` | `0.0.0.0` / `8787` | Interfaz y puerto de escucha. |
| `SERVER_ADMIN_TOKEN` | — | Activa las identidades y protege las rutas `/api/admin`. |
| `LIBRARY_PATHS` | — | Carpetas de la biblioteca, separadas como el `PATH` (`;` en Windows, `:` en Linux). Se suman a las que añade el companion. |
| `LIBRARY_RESCAN_MINUTES` | `10` | Cada cuánto busca vídeos nuevos (`0` lo desactiva). |
| `FFMPEG_PATH` / `FFPROBE_PATH` | `ffmpeg` / `ffprobe` | Ubicación de los binarios. |
| `FFMPEG_CONCURRENCY` | `3` | Procesos de ffmpeg simultáneos. |
| `VIDEO_ENCODER` | automático | Fuerza el codificador al transcodificar (`libx264`, `h264_nvenc`…). |
| `FORCE_TRANSCODE` | `false` | Con `true` transcodifica siempre. |
| `HISTORY_STORE_PATH` | `server/data/history.json` | Historial por usuario; su carpeta guarda también `library.json`, `catalog.json`, `art/` y `cache/`. |
| `PREFERENCES_STORE_PATH`, `PROFILES_STORE_PATH` | junto al historial | Preferencias y perfiles. |
| `SERVER_USER_STORE_PATH` | `server/data/users.json` | Hash y salt de los tokens registrados. |

Sin `SERVER_ADMIN_TOKEN` ni usuarios registrados, el servidor funciona sin autenticación (sólo para tu red local).

### API

Las apps usan estas rutas; no necesitas llamarlas a mano. Los nombres del protocolo (el encabezado de token y el parámetro de los códigos QR) no cambian.

- `GET /health`
- `GET /api/search?q=titulo` y `GET /api/latest?page=1&language=sub&genre=…&year=…&status=…&sort=…` (cada filtro se puede repetir)
- `GET /api/catalog/filters`: géneros (los de tu biblioteca), tipos y órdenes, en español e inglés.
- `POST /api/series` con `{ "watchUrl" }`: sinopsis, clasificación, estado, próximo episodio, episodios con su disponibilidad Sub/Dub y temporadas relacionadas. `POST /api/series/status` con `{ "watchUrls" }` para hasta 50 series. `GET /api/schedule` responde vacío.
- `GET /api/series/:seriesKey/servers?episode=2` y `POST /api/play` con `{ "seriesKey", "episode", "serverIndex" }`: versiones del episodio y enlace de reproducción (HLS, subtítulos, intro y outro).
- `GET` / `PUT /api/history`, `/api/preferences` y `/api/profile`: historial, preferencias y perfil de la cuenta.
- `POST /api/identities`, `POST /api/pair/start`, `GET /api/pair/status`, `POST /api/pair/approve` y la página `/setup`: perfiles y vinculación de la TV por QR.
- `GET /api/devices`, `PUT /api/devices/:id` y `GET`/`POST /api/devices/:id/commands`: TVs conectadas y órdenes desde el teléfono.
- `POST /api/cast` y `DELETE /api/cast/:id`, más `/cast/:id/…`: enlace temporal para Google Cast, sin el token de identidad.
- `GET /art/<id>.jpg`: portada de una serie (pública).
- Administración, con el encabezado de token de administrador del protocolo (lo envían el companion y las apps al crear cuentas): `POST /api/admin/users`; `GET`/`PUT /api/admin/library` y `POST /api/admin/library/rescan` (carpetas y escaneo); y el editor del companion: `GET /api/admin/library/series`, `GET`/`PUT /api/admin/library/series/:id` (campos, temporada, franquicia y episodios), `PUT`/`DELETE /api/admin/library/series/:id/poster` (imagen sin procesar) y `GET /api/admin/library/episodes/:fileId/marks` (capítulos del archivo e intro/outro detectados).

Las series se identifican con `https://library.relaycast.local/watch/<id>`, un identificador estable que no cambia al editar el título. El servidor sólo sirve archivos de las carpetas de la biblioteca, por identificador, y no expone un proxy genérico. Las sesiones de reproducción caducan a los 20 minutos y la app las recarga sola.

## Cuentas, perfiles y códigos QR

- Cada **token** es una cuenta del servidor, con su historial y sus preferencias. El servidor sólo guarda su hash con salt. En iOS el token se guarda en el Llavero de iCloud; en Android, cifrado con el Keystore.
- **Código de configuración** del servidor (`relaycast://provision?…`, con la dirección del servidor y el token administrador): lo genera el companion. La app prueba el servidor y pregunta cómo identificarte: con una cuenta existente (recomendado), creando una nueva con el token administrador, o más tarde. Quien tenga el código puede crear cuentas en tu servidor: compártelo sólo con quien deba tenerlo.
- **Código de perfil** (`relaycast://identity?…`): en **Ajustes › Mostrar código del perfil** o **Llevar al teléfono** en la TV. Se oculta en un minuto.
- Los códigos se leen con la cámara, con el escáner de la app o desde una imagen.
- **Asistente de configuración inicial**: bienvenida, servidor, cuenta, perfil, avisos y listo; se puede volver a abrir desde Ajustes.

## Control de la TV desde el teléfono

La app de Android TV aparece como TV conectada para las apps con la misma cuenta, también con la app cerrada. Desde el teléfono, **Ver en TV** abre un control remoto: progreso, pausa, ±10 s, saltar intro/outro, subtítulos, volumen, episodio anterior/siguiente y detener. Las órdenes pasan por el servidor, así que funcionan aunque el teléfono y la TV estén en redes distintas. Una TV sólo aparece mientras informa su estado (últimos 45 s), tiene abierto su canal de órdenes y la pantalla encendida.

Para que el teléfono pueda **abrir** la app de la TV cuando está cerrada, Android exige el permiso «Mostrar sobre otras apps» (la TV tiene un botón **Abrir desde el teléfono** que lo explica). En la TV, el selector «¿Quién está viendo?» elige el perfil; con OK mantenido se renombra, se lleva al teléfono o se quita. Para instalar por `adb` en el perfil principal: `adb install --user 0 Relaycast.apk`.

## Google Cast

Desde el reproductor del iPhone, iPad y Android se envía el episodio a un Chromecast, Google TV o TV con Chromecast integrado, con el receptor predeterminado de Google (no hay nada que registrar). La app pide al servidor un enlace temporal (`POST /api/cast`) con un id aleatorio que sólo abre esa reproducción y caduca con ella; el receptor nunca recibe tu token. Hay control de reproducción, subtítulos, saltar intro/outro, siguiente episodio y volumen, y la sesión se reanuda al volver a abrir la app. El dispositivo Cast tiene que poder abrir la dirección del servidor que usa el teléfono; con un servidor público usa HTTPS. Las descargas se reproducen siempre en el teléfono.

## Desarrollo

```sh
cd server && npm test                  # servidor (incluye una prueba de extremo a extremo con ffmpeg)
cd companion && npm test && npm start  # companion
```

- **iOS**: proyecto Xcode en [`ios/`](ios) (iOS/iPadOS 26 o posterior, Liquid Glass; el build corre con Xcode 27.1, el primero con el SDK del iPhone Duo). Dependencia: SDK de Google Cast por Swift Package Manager.
- **Android**: Gradle en [`android/`](android) (Android 8 o posterior; teléfono, tablet y TV con el mismo APK). Cast con `play-services-cast-framework`.
- **Versión**: [`version.txt`](version.txt) es la única fuente; [`scripts/version.mjs`](scripts/version.mjs) la sube y la sincroniza con el proyecto Xcode y `server/package.json`. Una vez por clon, activa el hook con la tarea de VS Code **Git: activar hook de version** o `scripts/install-hooks.ps1`: cada commit sube el parche y crea la etiqueta `vX.Y.Z`. Usa [Conventional Commits](https://www.conventionalcommits.org/).
- **CI** ([`.github/workflows`](.github/workflows)): al subir una etiqueta, el workflow compila lo que cambió (app iOS sin firmar, APK firmado, servidor probado) y publica la release con `Relaycast.ipa`, `Relaycast.apk` y `Relaycast-simulator.zip`; el workflow del companion adjunta `Relaycast-Setup-<versión>.exe`. Para firmar el APK hacen falta los secretos `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` y `ANDROID_KEY_PASSWORD`; conserva un respaldo del keystore. El IPA sale sin certificado: se firma con el de distribución al publicar en la tienda.

## Límites técnicos

- Los vídeos deben ser archivos que `ffmpeg` pueda leer, sin DRM. Los subtítulos de imagen se ignoran.
- Transcodificar HEVC, 10 bits o entrelazado exige CPU o GPU: con equipos modestos la reproducción puede cortarse.
- El catálogo sólo conoce lo que dicen tus carpetas y lo que edites en el companion: no hay sinopsis, puntuaciones ni calendario automáticos.
- No hay cuentas de terceros: el progreso compartido vive en tu servidor, protegido por token.
