# 실행 먼저 보기

이 수정본은 시스템에 `gradle`이 설치되어 있지 않아도 `gradlew.bat`가 Gradle 8.6을 직접 내려받아 실행하도록 바꾼 버전입니다.

## Windows PowerShell

프로젝트 루트에서 실행:

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug
```

절대 아래 명령은 쓰지 마세요.

```powershell
gradle wrapper --gradle-version 8.14.4
gradle :app:assembleDebug
```

## 그래도 실패하면

회사망/프록시/방화벽이 아래 주소를 막는 경우입니다.

- https://services.gradle.org/distributions/gradle-8.6-bin.zip
- https://dl.google.com/dl/android/maven2/
- https://repo.maven.apache.org/maven2/
- https://plugins.gradle.org/m2/

그 경우 Android Studio에서 프로젝트를 열고 Gradle Sync를 하거나, 개인 인터넷/핫스팟에서 처음 한 번만 빌드하세요.
