# Wer Wie Was – R8-Regeln für den Release-Build
# Compose, Kotlinx-Coroutines und AndroidX bringen eigene Consumer-Regeln mit;
# hier bleiben nur die Warnungen weg, die durch optionale Abhängigkeiten entstehen.

# OkHttp/Okio: optionale TLS-Provider sind auf Android nicht vorhanden – Warnungen unterdrücken
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink (über androidx.security:security-crypto) referenziert Error-Prone-Annotationen,
# die nur zur Kompilierzeit existieren und nicht in der Runtime-Klassenliste liegen.
-dontwarn com.google.errorprone.annotations.**

# sherpa-onnx (Offline-Stimme „Victoria"): Die nativen JNI-Bindings greifen über
# Reflexion auf die Kotlin-Klassen zu – R8 darf hier nichts umbenennen oder entfernen.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# commons-compress (nur zum Entpacken der espeak-ng-Daten beim Stimmen-Download):
# referenziert optionale Klassen, die auf Android nicht vorhanden sind.
-dontwarn org.apache.commons.compress.**

