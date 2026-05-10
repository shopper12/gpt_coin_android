# Crypto Trade Coach Android App

좋아요. 코드를 모르는 기준으로, **왜 계속 오류가 나는지**와 **어떻게 고치면 되는지**를 아주 기초부터 설명드릴게요.

---

## 0) 지금 당장 뭘 다운로드하면 되나요? (초보자용)

아래 3가지만 설치하면 됩니다.

1. **Android Studio (최신 안정 버전)**
   - 설치 시 기본 옵션 그대로 진행 (SDK/Emulator 포함)
2. **JDK 별도 설치는 일단 생략**
   - Android Studio의 Embedded JDK를 사용하면 됩니다.
3. **Git (선택)**
   - 소스 업데이트/버전관리용 (없어도 Android Studio로 열고 실행은 가능)

설치 후 바로 할 일:
- 이 프로젝트 폴더를 Android Studio로 열기
- Gradle Sync 완료 기다리기
- 에뮬레이터 생성 후 Run

---

## 0-1) 실행 전 최소 체크

- 인터넷 연결 가능
- 회사/학교망이면 `dl.google.com`, `maven.google.com`, `repo.maven.apache.org` 허용
- 디스크 여유공간 최소 15GB 이상 (SDK + 에뮬레이터)

---

## 1) 지금 오류의 핵심 원인 (한 줄 요약)

앱 코드가 틀려서가 아니라, **빌드할 때 필요한 파일(안드로이드 플러그인)을 인터넷에서 못 받아서** 실패하는 상황입니다.

쉽게 말하면:
- 레고 설명서는 있는데
- 레고 부품 배송(구글 저장소 접속)이 막혀서
- 조립이 멈추는 상태입니다.

---

## 2) 초보자용 개념 3개만 알면 됩니다

### (1) Android Studio
- 안드로이드 앱 만드는 프로그램(IDE)

### (2) Gradle
- 앱을 실제 실행파일로 조립해 주는 빌드 도구

### (3) 저장소(Repository)
- 조립에 필요한 재료(라이브러리/플러그인)를 다운로드하는 서버
- 안드로이드는 특히 `Google Maven` 접근이 매우 중요

---

## 3) 왜 내 환경에서 실패하나요?

이 프로젝트는 빌드 시 아래 주소로 접속해야 합니다.
- `https://dl.google.com/dl/android/maven2/`
- `https://repo.maven.apache.org/maven2/`

현재 실패 메시지의 본질은:
- `com.android.tools.build:gradle` 다운로드 실패
- 즉, 네트워크/방화벽/프록시 때문에 저장소 접근이 막힘

---

## 4) 진짜 해결 순서 (코드 몰라도 가능)

### Step A. Android Studio로 먼저 열기
1. Android Studio 실행
2. `File > Open`에서 이 프로젝트 폴더 선택
3. 오른쪽 아래 `Gradle Sync`가 끝날 때까지 기다리기

> 여기서 실패하면 대부분 네트워크 이슈입니다.

### Step B. 인터넷/회사망 확인
회사/학교망이면 관리자에게 아래를 요청하세요.

**요청 문구 예시**
> Android Studio 빌드를 위해 아래 도메인 아웃바운드 HTTPS(443) 허용이 필요합니다.
> - dl.google.com
> - repo.maven.apache.org
> - maven.google.com

### Step C. 프록시 사용하는 경우
Android Studio:
- `Settings > Appearance & Behavior > System Settings > HTTP Proxy`
- 회사 프록시 정보 입력
- `Check connection`으로 테스트

Gradle도 프록시가 필요하면(회사 환경):
`~/.gradle/gradle.properties`에 설정

```properties
systemProp.http.proxyHost=프록시주소
systemProp.http.proxyPort=포트
systemProp.https.proxyHost=프록시주소
systemProp.https.proxyPort=포트
```

### Step D. 다시 빌드
터미널에서:

```bash
gradle :app:assembleDebug
```

성공하면 APK가 생성됩니다.

---

## 5) 자주 막히는 포인트 (초보자 체크리스트)

1. **JDK 버전 문제**
   - Android Studio의 기본 JDK(Embedded JDK) 사용 권장
2. **오프라인 모드 켜짐**
   - Gradle Offline 모드가 켜져 있으면 다운로드 실패
3. **회사 보안 프로그램 차단**
   - SSL 검사/프록시 차단으로 Google Maven이 막힐 수 있음
4. **처음 빌드는 오래 걸림**
   - 의존성 첫 다운로드 때문에 5~15분 이상 걸릴 수 있음

---

## 6) 지금 프로젝트에서 이미 반영된 부분

- Android 플러그인 해석 보강 (`settings.gradle.kts`)
- AGP 버전 안정 라인 사용 (`build.gradle.kts`)
- 기본 Compose 앱 화면(시그널 카드/코칭 카드) 구현 완료

즉, 코드 뼈대는 준비되어 있고, **현재 1순위는 네트워크로 의존성 다운로드가 되게 만드는 것**입니다.

---

## 7) 그래도 안 되면 이렇게 보내주세요

아래 2개만 복사해서 보내주시면 다음 단계 정확히 잡아드릴 수 있어요.

1. Android Studio의 Build Output 마지막 30줄
2. 터미널 명령 결과:

```bash
gradle :app:assembleDebug --stacktrace
```

---

## 8) 자주 묻는 질문 (지금 질문하신 내용)

### Q1. 이 대화에 있는 파일을 전부 다운받아야 하나요?
**네, 이 프로젝트를 실행하려면 레포에 있는 파일 전체가 필요합니다.**

이유:
- 앱 코드는 `app/` 아래에 있고
- 빌드 설정은 루트의 `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`에 있으며
- 이 파일들이 함께 있어야 Gradle이 프로젝트를 올바르게 인식합니다.

단, 다운로드 방식은 2가지 중 하나면 충분합니다.
1. Git으로 레포 전체 받기 (권장)
2. ZIP으로 레포 전체 받기

부분 파일만 받으면 빌드가 깨질 가능성이 큽니다.

### Q2. 프로젝트 폴더는 어디에 만들어야 하나요?
**아무 위치나 가능**하지만, 초보자에게는 아래처럼 단순한 경로를 권장합니다.

- Windows: `C:\Projects\android_crypto`
- macOS: `~/Projects/android_crypto`
- Linux: `~/Projects/android_crypto`

주의:
- 바탕화면처럼 한글/공백/특수문자가 많은 경로는 피하세요.
- 회사 보안 폴더(동기화 폴더) 안은 빌드가 느려질 수 있습니다.

### Q3. 지금 이 대화 기준으로 내 환경에 맞는 폴더는?
현재 작업 경로 예시는 아래와 같습니다.
- `/workspace/android_crypto`

즉, Android Studio에서 **이 폴더 자체**를 열면 됩니다 (`File > Open`).

---

## 9) 이 대화에서 나온 파일을 한 번에 받는 방법

가장 쉬운 방법은 **레포 전체를 한 번에 받는 것**입니다.

### 방법 A) ZIP으로 한 번에 받기 (Git 몰라도 됨)
1. 레포 페이지에서 **Code > Download ZIP** 클릭
2. ZIP 압축 해제
3. 압축 해제된 폴더를 Android Studio에서 `File > Open`

### 방법 B) Git으로 한 번에 받기 (권장)
터미널에서:

```bash
git clone <레포주소> android_crypto
cd android_crypto
```

그다음 Android Studio에서 해당 폴더를 엽니다.

### 이미 폴더가 있는데 최신 파일만 받고 싶다면

```bash
git pull
```

---

## 10) Android Studio 사용법 (Gradle Sync에서 막힌 경우)

아예 처음 쓰는 기준으로, 버튼 위치까지 순서대로 설명합니다.

### Step 1. Android Studio 실행 후 프로젝트 열기
1. Android Studio 실행
2. 첫 화면에서 **Open** 클릭
3. 프로젝트 폴더(`android_crypto` 또는 `/workspace/android_crypto`) 선택
4. 우측 하단에 **Gradle Sync**가 자동 시작되는지 확인

### Step 2. Sync 상태 보는 법
- 하단 `Build` 또는 `Sync` 창에서 진행률 확인
- 성공 시 `Gradle sync finished` 메시지
- 실패 시 빨간 오류 로그 출력

### Step 3. Sync 실패 시 가장 먼저 할 것
1. 상단 메뉴 `File > Settings` (mac은 `Android Studio > Settings`)
2. `Build, Execution, Deployment > Build Tools > Gradle` 이동
3. 아래 2개 확인:
   - **Gradle JDK**: `Embedded JDK` 선택
   - **Offline work**: 체크 해제 (꺼짐)
4. Apply/OK 후 다시 Sync (`File > Sync Project with Gradle Files`)

### Step 4. 그래도 실패하면 (네트워크)
오류에 `Could not resolve` 또는 `com.android.tools.build:gradle`가 보이면
대부분 저장소 접속 문제입니다.

- 브라우저에서 아래 주소가 열리는지 확인
  - `https://dl.google.com/dl/android/maven2/`
  - `https://repo.maven.apache.org/maven2/`
- 회사망이면 IT에 HTTPS 443 허용 요청

### Step 5. 프록시 환경이면
1. `Settings > Appearance & Behavior > System Settings > HTTP Proxy`
2. 프록시 정보 입력
3. `Check connection` 테스트
4. 성공 후 다시 Sync

### Step 6. 마지막 확인
Sync 성공 후:
1. 우측 상단 기기 선택 (에뮬레이터/실기기)
2. Run(▶) 클릭
3. 앱 실행 확인

---

## 실행 명령어

```bash
gradle :app:assembleDebug
```
