# 회의노트 (MeetingNote)

녹음 → 실시간 한국어 음성인식 → AI 회의록 생성을 **모두 기기 안에서** 처리하는 안드로이드 앱.
녹음은 외부로 전송되지 않는다(모델 다운로드에만 인터넷 사용).

## 대상 기기와 측정 결과

Lenovo Xiaoxin Pad Pro 12.7 (TB371FC) · Snapdragon 870 (1×A77 3.19GHz + 3×A77 2.42GHz + 4×A55) · Adreno 650 · RAM 8GB · Android 16

| 단계 | 구성 | 결과 |
|---|---|---|
| 음성인식 | whisper.cpp small **q8_0**, beam 5, 4스레드, Silero VAD | 실제 시간의 **0.26~0.36배** (15분 회의 → 약 5분, 녹음 중 병행 처리) |
| 요약 (고품질, 기본) | Qwen3-4B-Instruct-2507 Q4_0 | 프롬프트 30~38 tok/s, 생성 6.5~7.5 tok/s |
| 요약 (빠름) | EXAONE 4.0 1.2B Q4_K_M | 프롬프트 100 tok/s, 생성 21~30 tok/s |
| 1.5분 회의 전체 | 인식 + 회의록 | 약 87초 |
| 15분 회의 회의록 | Map-Reduce (구간 2개 + 종합) | 약 225초 (녹음 종료 후) |

### 검토했지만 채택하지 않은 것
- **GPU (Adreno 650 + llama.cpp OpenCL)**: 공식 지원은 Adreno 7xx 이상. 6xx 드라이버에는 커널 내장 함수가 없어 컴파일이 실패하고, 범용 경로는 드라이버 내부(`cl_a6x_cmdbuf_mgr_submit_ibs`)에서 크래시 → CPU 사용
- **Whisper base**: 0.17배로 빠르지만 문장 누락·숫자 오인식(9건→5건, 1일→2일)
- **Whisper small q5_0**: q8_0보다 오히려 느림 (A77에서 q5 커널이 비효율적)
- **Greedy 디코딩**: 빠르지만 한국어에서 대부분의 문장이 누락 → beam 5 유지
- **8스레드**: 4스레드보다 느림 (리틀코어가 병목) → 빅코어 4개에 고정

## 적용한 최적화
1. **빅코어 고정**: 최대 클럭이 높은 코어를 자동으로 골라 ggml 스레드풀을 고정 (생성 속도 5.8 → 6.7 tok/s)
2. **녹음 중 실시간 인식**: 25~30초 단위로 나눠 즉시 인식 → 회의가 끝나면 마지막 조각만 남음
3. **무음 지점 분할 + Silero VAD**: 단어가 잘리지 않게 가장 조용한 지점에서 자르고 VAD로 누락 방지
4. **무음 조각 생략**, 이전 문장을 initial prompt로 넘겨 문맥 유지
5. **메모리 순차 사용**: Whisper를 해제한 뒤 LLM을 불러와 8GB RAM에서 4B 모델 사용
6. **토큰 기반 균등 Map-Reduce**: 4096 컨텍스트 안에서 긴 회의도 처리, 구간 요약은 10줄 이내로 제한
7. Release 빌드 고정(`-O3`, `armv8.2-a+dotprod+fp16`), 심볼 strip, R8 → APK 17MB
8. 포그라운드 서비스와 partial wake lock으로 화면이 꺼져도 녹음·처리 지속

## 기능
- 녹음 중 실시간 녹취록, 종료 후 자동 회의록 생성, 알림창에서 바로 종료
- **외부 음성 파일 가져오기**: m4a/mp3/aac/ogg/wav, 동영상의 오디오 (MediaCodec 디코딩 → 16kHz 변환, 스트리밍)
- **용어 사전**: 회사명·인명·전문용어를 Whisper initial prompt와 회의록 프롬프트에 반영해 표기 교정
- **자동 제목**: 회의록 생성 후 "날짜 · 주제" 형식으로 제목 생성 (직접 수정하면 이후 유지)
- 제목·회의록 직접 편집, 공유(회의록 + 타임스탬프 녹취록)
- **안정성**: 녹취록 30초마다 저장, 녹음 중 비정상 종료 시 다음 실행에서 WAV 복구 → '다시 인식'으로 이어서 처리
- 조용한 목소리 보정: 가장 큰 100ms 구간 기준으로 무음 판단 + 최대 10배 자동 증폭

## 최대 녹음 길이
| 항목 | 한계 |
|---|---|
| 녹음 + 실시간 인식 | 제한 없음 (wake lock 무제한, 인식 속도 0.3배라 밀리지 않음). 실제 한계는 배터리·발열 |
| 파일 인식/가져오기 | 제한 없음 (스트리밍 처리, 힙 사용 약 12MB 일정) |
| WAV 형식 | 약 37시간 (32비트 부호 없는 길이 필드). 이후에도 실제 파일 크기 기준으로 읽음 |
| 저장 공간 | 1시간 ≈ 115MB |
| 회의록 작성 시간 (Qwen3-4B) | 15분 회의 ≈ 4분, 1시간 ≈ 15분, 3시간 ≈ 45분 (EXAONE은 3~4배 빠름) |

## 다른 기기
- `-march=armv8.2-a+dotprod+fp16` 빌드이므로 ARMv8.2 이상(대부분의 2019년 이후 SoC)에서 실행
- **Xiaoxin Pad Pro 12.7 2세대 (Dimensity 8300, Mali-G615)**: CPU(A715/A510)로 동작. 빅코어는 자동 선택됨.
  Mali GPU는 llama.cpp 최적화 대상이 아니므로 CPU가 주력. i8mm 빌드 옵션 추가로 추가 개선 여지 있음

## 설치

[Releases](../../releases/latest)에서 `MeetingNote-<버전>.apk`를 받아 설치한다 (arm64 기기, Android 9 이상).
첫 실행 시 음성인식·요약 모델(약 2.6GB, 빠름 모델만 쓰면 약 1.1GB)을 앱 안에서 내려받는다.

## 빌드

```bash
git clone --recurse-submodules <이 저장소>   # whisper.cpp, llama.cpp는 검증된 커밋으로 고정된 서브모듈
# 필요: JDK 17+, Android SDK, NDK 28.2.13676358
export JAVA_HOME=~/Android/jdk ANDROID_HOME=~/Android/Sdk
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

모델은 앱 첫 화면에서 받거나, 개발 중에는 직접 복사:
```bash
adb push <모델파일> /sdcard/Android/data/com.meetingnote.app/files/models/
```

테스트용 인텐트:
```bash
# 16kHz mono WAV를 새 회의로 인식+요약
adb shell am start -n com.meetingnote.app/.ui.MainActivity --es import_wav /sdcard/Android/data/com.meetingnote.app/files/x.wav
# 기존 회의의 회의록 다시 생성
adb shell am start -n com.meetingnote.app/.ui.MainActivity --es summarize_id <회의ID>
```

## 구조
```
app/              UI(Compose), 녹음, 실시간 인식 파이프라인, 요약(Map-Reduce), 포그라운드 서비스
native-whisper/   whisper.cpp JNI (libwhisper_jni.so)
native-llama/     llama.cpp JNI (libllama_jni.so)  ※ ggml 심볼을 숨겨 두 라이브러리 충돌 방지
native-common/    빅코어 선택/고정 유틸리티
```

## 라이선스
- 이 프로젝트 코드: [MIT](LICENSE)
- whisper.cpp, llama.cpp: MIT (서브모듈)
- 모델은 저장소·APK에 포함되지 않으며 첫 실행 시 각 배포처에서 내려받음. 모델별 라이선스는 아래 참고

### 주의
- EXAONE 4.0은 **비상업 라이선스**. 상업적으로 쓰려면 Qwen3(Apache-2.0)만 사용할 것
- 화자 분리는 아직 미지원 (녹취록에 화자 이름이 말로 언급될 때만 반영됨)
