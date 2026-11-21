# Protocolo del emulador

`emulator_controller.proto` procede del SDK Android Emulator 36.4.9, build 14788078, instalado durante la prueba inicial. Conserva el encabezado de licencia Apache 2.0 de AOSP.

Fuente pública de referencia del protocolo en [AOSP](https://android.googlesource.com/platform/prebuilts/android-emulator/+/master/linux-x86_64/lib/emulator_controller.proto).

El archivo se versiona para generar siempre los mismos mensajes y servicios. Las credenciales de conexión se leen en tiempo de ejecución desde los archivos de descubrimiento del emulador y permanecen en memoria.
