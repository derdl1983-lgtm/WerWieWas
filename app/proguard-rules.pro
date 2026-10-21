# Wer Wie Was – R8-Regeln für den Release-Build
# Compose, Kotlinx-Coroutines und AndroidX bringen eigene Consumer-Regeln mit;
# hier bleiben nur die Warnungen weg, die durch optionale Abhängigkeiten entstehen.

# OkHttp/Okio: optionale TLS-Provider sind auf Android nicht vorhanden – Warnungen unterdrücken
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
