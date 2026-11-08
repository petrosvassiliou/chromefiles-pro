# ChromeFiles Pro ProGuard configuration
-keep class de.chromefiles.app.model.** { *; }
-keep class de.chromefiles.app.data.local.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn com.hierynomus.**

# Netzwerkprotokolle: sshj, smbj und Bouncy Castle laden Algorithmen/Provider per Reflection.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn net.schmizz.sshj.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn com.jcraft.jzlib.**

# Google Drive REST API v3 & Google HTTP Client (nutzt @Key-Reflection auf DTOs)
-keep class com.google.api.** { *; }
-keep class com.google.apis.** { *; }
-keep class com.google.auth.** { *; }
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault
-dontwarn com.google.api.**
-dontwarn com.google.common.**
-dontwarn org.apache.http.**
-dontwarn io.opencensus.**
-dontwarn io.grpc.**

# Junrar (RAR-Archiv-Engine) & MBassador EventBus (transitiv aus smbj)
-keep class com.github.junrar.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn com.github.junrar.**
-dontwarn net.engio.mbassy.**
-dontwarn javax.el.**
