# JNI: 네이티브 코드에서 이름으로 찾는 클래스/메서드 유지
-keep class com.meetingnote.llama.** { *; }
-keep class com.meetingnote.whisper.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
