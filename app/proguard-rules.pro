# Il codice dell'app resta intero (niente sorprese a runtime); R8 toglie solo il superfluo delle librerie.
-keep class com.marco90.mlfilemanager.** { *; }
-keep class com.marco.mltheme.** { *; }
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
-dontwarn org.slf4j.**
-keepattributes SourceFile,LineNumberTable,*Annotation*
