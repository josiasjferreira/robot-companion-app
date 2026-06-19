# Mantém as classes do RobotSDK / Slamware / CSJBot intactas — são acessadas por
# reflexão e/ou via AIDL, então o R8 não pode renomear nem remover.
-keep class com.slamtec.** { *; }
-keep class com.csjbot.** { *; }
-dontwarn com.slamtec.**
-dontwarn com.csjbot.**

# Paho MQTT
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**
