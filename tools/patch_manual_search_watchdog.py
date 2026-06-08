from pathlib import Path

path = Path("app/src/main/java/com/cryptotradecoach/ui/MainViewModel.kt")
text = path.read_text(encoding="utf-8")

if "import kotlinx.coroutines.delay" not in text:
    text = text.replace(
        "import kotlinx.coroutines.Dispatchers\n",
        "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.delay\n",
    )

if "private var manualAnalysisToken: Long = 0L" not in text:
    text = text.replace(
        "    private val manualEngine = SignalEngine()\n",
        "    private val manualEngine = SignalEngine()\n    private var manualAnalysisToken: Long = 0L\n",
    )

old = '            _uiState.value = _uiState.value.copy(manualMessage = "분석 중: $symbol", chartMessage = null)'
new = '''            val token = System.nanoTime()
            manualAnalysisToken = token
            _uiState.value = _uiState.value.copy(
                manualStrategy = null,
                strategyChart = null,
                manualMessage = "분석 중: $symbol · 최대 ${MANUAL_ANALYSIS_TIMEOUT_MS / 1000}초",
                chartMessage = null,
            )
            viewModelScope.launch {
                delay(MANUAL_ANALYSIS_TIMEOUT_MS + 1_000L)
                val stillLoading = _uiState.value.manualMessage?.startsWith("분석 중: $symbol") == true
                if (manualAnalysisToken == token && stillLoading) {
                    _uiState.value = _uiState.value.copy(
                        manualMessage = "수동 분석 시간 초과: $symbol. 업비트 응답 지연 또는 네트워크 문제입니다. 다시 Search를 누르세요.",
                        chartMessage = null,
                    )
                }
            }'''

if old not in text and "manualAnalysisToken = token" not in text:
    raise SystemExit("Missing expected manual loading message line")

if old in text:
    text = text.replace(old, new)

path.write_text(text, encoding="utf-8")
print("Patched manual search watchdog timeout feedback")
