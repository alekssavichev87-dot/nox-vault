# NOX Vault — правила на будущее (минификация в v1 выключена, см. build.gradle.kts).
# Перед включением isMinifyEnabled=true обязательно прогнать все тесты на release-сборке.

# kotlinx.serialization: сохранять генерируемые сериализаторы моделей хранилища
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }

# Bouncy Castle (используется Argon2BytesGenerator)
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.crypto.generators.Argon2BytesGenerator { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters$Builder { *; }
