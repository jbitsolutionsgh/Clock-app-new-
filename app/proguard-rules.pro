# Preserve line numbers, source file names, and enclosing method metadata for offline crash logs.
-keepattributes SourceFile,LineNumberTable,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Preserve manifest entry points (Application, Activities, Services, Receivers, DreamService).
-keep class com.example.AuraClockApp { <init>(); }
-keep class com.example.MainActivity { <init>(); }
-keep class com.example.timer.TimerForegroundService { <init>(); }
-keep class com.example.earbud.EarbudListenerService { <init>(); }
-keep class com.example.earbud.EarbudMediaButtonReceiver { <init>(); }
-keep class com.example.standby.StandByDreamService { <init>(); }
-keep class com.example.widget.AuraClockWidgetProvider { <init>(); }
-keep class com.example.widget.WidgetConfigActivity { <init>(); }
