# R8 rules for GAL's release build.
#
# Most of what needs keeping is covered by the libraries' own rules (Compose, Room, WorkManager, DataStore,
# kotlinx.serialization) and by the manifest (activities, services, receivers). The rules below are a safety net
# for the phrase catalogue, which is read from assets/phrases.json with kotlinx.serialization: if a serializer
# were stripped, every card would fall back to the one built-in line.

-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**

-keepclassmembers @kotlinx.serialization.Serializable class com.sangar.gal.** {
    *** Companion;
    static <fields>;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.sangar.gal.**$$serializer { *; }

# Readable crash reports from testers: keep file names and line numbers, hide the original source file name.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
