# API 102 loads this class by name from META-INF/xposed/java_init.list.
-keep class io.github.zopulus.ffc.XposedMain { public *; }

# Keep useful hook failure stack traces.
-keepattributes SourceFile,LineNumberTable
