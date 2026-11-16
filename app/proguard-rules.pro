# JNI: 네이티브 코드에서 이름으로 찾는 클래스/메서드 유지
-keep class com.meetingnote.llama.** { *; }
-keep class com.meetingnote.whisper.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# 오류 보고의 스택 트레이스에 줄 번호를 남김 (난독화 해제는 릴리스에 첨부한 mapping.txt로)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
