# Preparar una publicación en tiendas

Esta versión sólo reproduce la biblioteca personal del usuario. No incluye
catálogos de terceros, instalación de actualizaciones desde la app ni métodos
de distribución alternativos. Los avisos de episodios consultan tu Relay Server.

## Privacidad de iOS

La etiqueta de App Store Connect queda **pendiente de revisar el archivo final**.
El manifiesto de Relaycast declara preferencias en UserDefaults (CA92.1) y fechas
de sus propios archivos para limpiar la caché (C617.1). No sustituye los
manifiestos de los SDK incorporados.

Se inspeccionó la [distribución dinámica oficial de Cast 4.8.6](https://dl.google.com/dl/chromecast/sdk/ios/GoogleCastSDK-ios-4.8.6_dynamic.zip),
verificada contra el SHA-256 `55f6c21291a1315c68063f07e7d76225564bff70f2fd38caad135c71d66eb310`
del [Package.swift oficial](https://github.com/googlecast/google-cast-ios-sdk/blob/4.8.6/Package.swift).
**Con este SDK no corresponde asumir «Data Not Collected».**

| Documento del SDK | Qué declara |
| --- | --- |
| `GoogleCast.framework/PrivacyInfo.xcprivacy` | ID de dispositivo, otros diagnósticos e interacción con el producto; sin vinculación a la identidad ni seguimiento, para analítica y funcionalidad. |
| `NutritionLabel/NutritionLabel.txt` | Diagnósticos por defecto; ID si se habilitan logs analíticos e interacción si se usa la UI del SDK. También enumera ubicación aproximada y rendimiento al responder el cuestionario. |

La etiqueta textual y el manifiesto no enumeran exactamente lo mismo. Revisa
ambos con el informe agregado del archivo final; la UI propia de dispositivos
no elimina la recogida predeterminada de diagnósticos del SDK.

1. Descarga el artefacto `ios-archive` de Actions y descomprime el `.xcarchive`
   en un Mac con la misma versión de Xcode, o archiva localmente el proyecto
   `ios/Relaycast.xcodeproj`, esquema `Relaycast`, destino iOS genérico.
2. Abre el archivo en Xcode Organizer y usa **Generate Privacy Report**.
3. Revisa el informe agregado y la información de la versión exacta de Google
   Cast (actualmente 4.8.6), incluido `NutritionLabel/NutritionLabel.txt` en la
   distribución del SDK. Actions conserva los manifiestos encontrados y las
   etiquetas disponibles en el artefacto `ios-privacy-inputs`; no es el informe
   agregado de Xcode.
4. Responde al cuestionario con las prácticas reales de la app y de sus SDK.
   No marques «Data Not Collected» sólo porque el desarrollador no tenga un
   servidor de analítica. Ajusta también la política si el informe lo requiere.
5. Firma y valida el archivo final con tu certificado de distribución antes de
   enviarlo. El IPA de GitHub no está firmado para App Store.

Referencias: [privacidad de Google Cast por versión](https://developers.google.com/cast/docs/ios_sender/app_privacy),
[manifiestos de Apple](https://developer.apple.com/documentation/bundleresources/privacy-manifest-files),
[motivos de acceso a APIs](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons).

## Instalador de Windows

Sin un certificado de firma de código, el instalador continúa sin firmar y
SmartScreen puede mostrar una advertencia. La clave de Android no sirve para
firmar Windows.

El workflow admite los secrets opcionales `WINDOWS_CSC_LINK` (certificado PFX
en base64) y `WINDOWS_CSC_KEY_PASSWORD`. Si proporcionas el certificado, exige
firma y comprueba Authenticode en el instalador y en el ejecutable de la app;
un fallo de firma impide publicar. No guardes el certificado en Git. La firma
no garantiza por sí sola que SmartScreen deje de mostrar avisos de reputación.

Referencia: [firma de Windows en electron-builder](https://www.electron.build/docs/features/code-signing/code-signing-win/).

## Herramientas multimedia

El companion incluye ffmpeg y ffprobe de la misma compilación Windows x64
6.1.1. Se verifica SHA-256 de la descarga de ffprobe y la versión de ambos al
preparar el instalador; no se utiliza el antiguo ffprobe 4.0.2.

## Android

Los tags usan los cuatro secrets de firma de Android. Conserva el keystore y
sus contraseñas para futuras actualizaciones. La publicación en Google Play
requiere además preparar la ficha, la sección de seguridad de datos y un AAB;
la release de GitHub entrega un APK.
