# (no rules needed)

# Cinema DOF: LiteRT finds these from native code.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }

# Cinema: native methods.
-keep class com.graden.editor.cinema.AiEngine { native <methods>; }
