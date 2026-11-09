# Software de terceros

Relaycast se publica con la [licencia Apache 2.0](LICENSE). Usa y, en algunos casos, distribuye software de terceros con sus propias licencias. Esta lista cubre lo que llevan las apps y el instalador del companion; las herramientas que sólo se usan para compilar (Gradle, Xcode, electron-builder…) no se distribuyen.

## FFmpeg (instalador del companion)

El instalador de Windows incluye `ffmpeg.exe` y `ffprobe.exe` como **programas separados**: el Relay Server los ejecuta como procesos y no los enlaza. Ambos son compilaciones de [FFmpeg](https://ffmpeg.org) con licencia **GNU GPL versión 3**; el texto de la licencia y la configuración de la compilación se instalan en la carpeta `licenses` del companion.

| Archivo | Versión | Origen | Código fuente |
| --- | --- | --- | --- |
| `ffmpeg.exe` | 6.1.1, «essentials build» de [gyan.dev](https://www.gyan.dev/ffmpeg/builds/), vía el paquete npm `ffmpeg-static` 5.3.0 | `companion/node_modules/ffmpeg-static` | [FFmpeg, commit e38092ef93](https://github.com/FFmpeg/FFmpeg/commit/e38092ef93) y las bibliotecas que lista `ffmpeg-README.txt` |
| `ffprobe.exe` | 6.1.1, la misma «essentials build» de gyan.dev que `ffmpeg.exe` | [Release b6.1.1 de ffmpeg-static](https://github.com/eugeneware/ffmpeg-static/releases/tag/b6.1.1), con SHA-256 fijado en `companion/scripts/ffprobe.mjs` | [FFmpeg, commit e38092ef93](https://github.com/FFmpeg/FFmpeg/commit/e38092ef93) y las bibliotecas que lista `ffmpeg-README.txt` |

Puedes reemplazar estos programas por otra compilación de FFmpeg: están en la carpeta `bin` de la instalación del companion.

## Companion para Windows

| Componente | Licencia |
| --- | --- |
| [Electron](https://www.electronjs.org) (incluye Chromium y Node.js; sus licencias van en `LICENSES.chromium.html` dentro de la instalación) | MIT |
| [node-qrcode](https://github.com/soldair/node-qrcode) | MIT |

El Relay Server no tiene dependencias de npm.

## App de iPhone y iPad

| Componente | Licencia |
| --- | --- |
| [Google Cast SDK para iOS](https://developers.google.com/cast/docs/ios_sender) 4.8.6 | [Términos de las API de Google](https://developers.google.com/terms) y [términos adicionales del SDK de Google Cast](https://developers.google.com/cast/docs/terms) |
| [SMBClient](https://github.com/kishikawakatsumi/SMBClient) (fuentes SMB) | MIT |
| [Citadel](https://github.com/orlandos-nl/Citadel) (fuentes SFTP) | MIT |
| [swift-nio-ssh](https://github.com/Wellz26/swift-nio-ssh) (la versión que usa Citadel), [SwiftNIO](https://github.com/apple/swift-nio), [Swift Crypto](https://github.com/apple/swift-crypto), [swift-log](https://github.com/apple/swift-log) | Apache 2.0 |
| [BigInt](https://github.com/attaswift/BigInt), [ColorizeSwift](https://github.com/mtynior/ColorizeSwift) (dependencias de Citadel) | MIT |

El resto son frameworks del sistema de Apple.

## App de Android (teléfono, tablet y TV)

| Componente | Licencia |
| --- | --- |
| AndroidX: Core, Activity, Lifecycle, Compose (UI, Foundation, Material 3, Material Icons, Material 3 Adaptive), TV Material, Media3 (ExoPlayer, HLS, UI, Session), WorkManager | Apache 2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Apache 2.0 |
| [OkHttp](https://square.github.io/okhttp/) | Apache 2.0 |
| [Coil](https://coil-kt.github.io/coil/) | Apache 2.0 |
| [ZXing](https://github.com/zxing/zxing) (core) | Apache 2.0 |
| [smbj](https://github.com/hierynomus/smbj) (fuentes SMB), [sshj](https://github.com/hierynomus/sshj) (fuentes SFTP), [asn-one](https://github.com/hierynomus/asn-one) | Apache 2.0 |
| [Bouncy Castle](https://www.bouncycastle.org) (bcprov, bcpkix, bcutil) | [Licencia de Bouncy Castle](https://www.bouncycastle.org/licence.html) (tipo MIT) |
| [MBassador](https://github.com/bennidi/mbassador), [SLF4J](https://www.slf4j.org) (API) | MIT |
| Google Play services: Cast framework, escáner de códigos, ML Kit (lectura de códigos) | [Términos del SDK de Android](https://developer.android.com/studio/terms) y [términos de las API de Google](https://developers.google.com/terms) |
| [ML Kit Translation](https://developers.google.com/ml-kit/language/translation) | [Términos de ML Kit](https://developers.google.com/ml-kit/terms) |

Las versiones exactas están en [`android/gradle/libs.versions.toml`](android/gradle/libs.versions.toml).
