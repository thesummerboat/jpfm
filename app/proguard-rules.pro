# JPFM – R8-regler.
# Media3, OkHttp, Coil och Compose har egna regler i sina paket. Här behövs bara det som
# Android startar via namn i manifestet (täcks av AAPT) och lite tystnad för valfria beroenden.

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn kotlinx.coroutines.debug.**

# Behåll radnummer i kraschrapporter.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
