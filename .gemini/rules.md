# 📜 RULES.md — BỘ QUY TẮC KHÔNG THỂ VI PHẠM
## RemotePlay · Code Governance Document V5

> Đây là **Luật tối cao** của dự án. Mọi AI, mọi developer, mọi agent đều phải tuân thủ.
> Vi phạm bất kỳ quy tắc nào trong Section 0–3 → **BLOCK commit ngay lập tức**.

---

## 0. NGUYÊN TẮC VÀNG (Inviolable Principles)

```
0. Mọi giao tếp và tài liệu ghi lại đều sử dụng Tiếng Việt làm ngôn ngữ chính
1. KHÔNG bao giờ để file Kotlin vượt quá 500 dòng
2. KHÔNG bao giờ hardcode: URL, API key, credential, port, server address
3. KHÔNG bao giờ dùng GlobalScope hoặc runBlocking trong production code
4. KHÔNG bao giờ bỏ trống catch block
5. KHÔNG bao giờ commit code có lỗi compile
6. KHÔNG bao giờ merge vào master không qua review
7. KHÔNG bao giờ ship UI mà không có @Preview
8. KHÔNG bao giờ để TODO/FIXME tồn tại trong code mới sau khi commit
```

---

## 1. QUY TẮC KIẾN TRÚC (Architecture Rules)

### 1.1 Layer Independence — Bất biến tuyệt đối

```
✅ Domain layer KHÔNG ĐƯỢC import bất kỳ thứ gì từ Android framework
✅ Presentation layer chỉ gọi qua ViewModel — không gọi trực tiếp UseCase
✅ Data layer implement interface từ Domain — không ngược lại
✅ Core module không được import từ Feature module

❌ Ví dụ vi phạm:
// WRONG — Domain biết về Android
class GetStreamUrlUseCase(
    private val context: Context  // ← CẤM TUYỆT ĐỐI
)

// WRONG — UI gọi trực tiếp repository
@Composable
fun StreamScreen(repository: StreamRepository) // ← CẤM
```

### 1.2 Use Case Rules

```kotlin
// ✅ CHUẨN — 1 UseCase = 1 class = 1 public function
class GetStreamUrlUseCase @Inject constructor(
    private val repository: StreamRepository
) {
    suspend operator fun invoke(serverId: String): Result<StreamUrl> {
        return repository.getStreamUrl(serverId)
    }
}

// ❌ WRONG — UseCase làm quá nhiều việc
class StreamUseCase {
    fun getUrl() { }
    fun connect() { }       // ← Tách thành ConnectStreamUseCase
    fun disconnect() { }    // ← Tách thành DisconnectStreamUseCase
}
```

### 1.3 ViewModel Rules

```kotlin
// ✅ CHUẨN
class StreamViewModel @HiltViewModel @Inject constructor(
    private val getStreamUrl: GetStreamUrlUseCase
) : ViewModel() {
    
    // Chỉ expose immutable state
    private val _uiState = MutableStateFlow<StreamUiState>(StreamUiState.Idle)
    val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()
    
    // Events là one-shot
    private val _events = Channel<StreamEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    
    fun connect(serverId: String) {
        viewModelScope.launch {
            _uiState.value = StreamUiState.Loading
            getStreamUrl(serverId)
                .onSuccess { _uiState.value = StreamUiState.Connected(it) }
                .onFailure { _uiState.value = StreamUiState.Error(it.message) }
        }
    }
}

// ❌ WRONG
class StreamViewModel : ViewModel() {
    var isLoading = false          // ← Dùng StateFlow, không phải var
    val data = MutableLiveData<>() // ← Không tạo LiveData mới, dùng StateFlow
    
    fun loadData() {
        GlobalScope.launch { }     // ← Dùng viewModelScope
    }
}
```

---

## 2. QUY TẮC NETWORK & WEBRTC (Network Rules)

### 2.1 WebRTC Lifecycle — Quan trọng nhất

```kotlin
// ✅ CHUẨN — Quản lý lifecycle đúng cách
class WebRtcService : LifecycleService() {
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Khởi tạo PeerConnection
        return super.onStartCommand(intent, flags, startId)
    }
    
    override fun onDestroy() {
        // BẮT BUỘC: Release tất cả resource
        peerConnection?.close()
        peerConnection?.dispose()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        eglBase?.release()
        super.onDestroy()
    }
}

// ❌ WRONG — Không release → Memory leak + Camera bị giữ
override fun onDestroy() {
    super.onDestroy()
    // Bỏ trống ← NGHIÊM CẤM với WebRTC
}
```

### 2.2 Error Handling cho Network Calls

```kotlin
// ✅ CHUẨN — Wrap Result, xử lý tất cả trường hợp
suspend fun getStreamUrl(serverId: String): Result<StreamUrl> {
    return try {
        val response = apiService.getStream(serverId)
        if (response.isSuccessful) {
            Result.success(response.body()!!.toDomain())
        } else {
            Result.failure(HttpException(response))
        }
    } catch (e: IOException) {
        Result.failure(NetworkException("No internet connection", e))
    } catch (e: Exception) {
        Result.failure(UnknownException(e))
    }
}

// ❌ WRONG — Không xử lý lỗi
suspend fun getStreamUrl(serverId: String): StreamUrl {
    return apiService.getStream(serverId).body()!! // ← NPE + crash risk
}
```

### 2.3 WebSocket Reconnection

```kotlin
// ✅ CHUẨN — Exponential backoff
class WebSocketManager {
    private var retryCount = 0
    private val maxRetries = 5
    
    private fun scheduleReconnect() {
        if (retryCount >= maxRetries) {
            notifyConnectionFailed()
            return
        }
        val delay = (2.0.pow(retryCount) * 1000).toLong().coerceAtMost(30_000L)
        retryCount++
        scope.launch {
            delay(delay)
            connect()
        }
    }
}
```

---

## 3. QUY TẮC UI/COMPOSE (Compose Rules)

### 3.1 State Hoisting — Bắt buộc

```kotlin
// ✅ CHUẨN — State hoist lên trên
@Composable
fun StreamScreen(
    uiState: StreamUiState,          // ← Nhận state từ ngoài
    onConnect: (String) -> Unit,     // ← Nhận lambda từ ngoài
    onDisconnect: () -> Unit
) { /* ... */ }

// ❌ WRONG — Tạo ViewModel trực tiếp trong deep composable
@Composable
fun StreamButton() {
    val vm: StreamViewModel = hiltViewModel() // ← Hoist lên parent
}
```

### 3.2 Performance — Không recompose không cần thiết

```kotlin
// ✅ CHUẨN — Dùng remember và key đúng cách
@Composable
fun VideoRenderer(streamUrl: String) {
    val exoPlayer = remember(streamUrl) { // key = streamUrl, tái tạo khi url đổi
        ExoPlayer.Builder(LocalContext.current).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
        }
    }
    
    // Cleanup khi rời khỏi composition
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
}

// ✅ CHUẨN — derivedStateOf cho computation
val isConnected by remember {
    derivedStateOf { uiState is StreamUiState.Connected }
}

// ❌ WRONG — Tính toán nặng trong recomposition
@Composable
fun BadComposable(items: List<Item>) {
    val sorted = items.sortedBy { it.name } // ← Mỗi recompose = sort lại
}
```

### 3.3 Preview bắt buộc

```kotlin
// ✅ BẮT BUỘC với mọi Composable screen
@Preview(name = "Light Mode", showBackground = true)
@Preview(name = "Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun StreamScreenPreview() {
    RemotePlayTheme {
        StreamScreen(
            uiState = StreamUiState.Connected(fakeStreamUrl),
            onConnect = {},
            onDisconnect = {}
        )
    }
}
```

### 3.4 Resources — Không hardcode

```kotlin
// ✅ CHUẨN
Text(text = stringResource(R.string.label_connect))
Box(modifier = Modifier.size(dimensionResource(R.dimen.button_size)))
Icon(tint = MaterialTheme.colorScheme.primary)

// ❌ WRONG
Text(text = "Kết nối")              // ← Không i18n
Box(modifier = Modifier.size(48.dp)) // ← Hardcode dimension
Icon(tint = Color(0xFF6200EE))       // ← Hardcode color
```

---

## 4. QUY TẮC CODE STYLE (Style Rules)

### 4.1 Naming Conventions

```kotlin
// Classes & Objects: PascalCase
class StreamRepository
object NetworkConstants

// Functions & Variables: camelCase
fun connectToServer() { }
val streamUrl: String

// Constants: SCREAMING_SNAKE_CASE (trong companion object)
companion object {
    const val MAX_RECONNECT_ATTEMPTS = 5
    const val WEBRTC_TIMEOUT_MS = 10_000L
}

// Packages: lowercase, no underscore
package com.reka.remoteplay.feature.streaming.domain.usecase

// Files: Tên file = tên class chính (PascalCase)
// StreamRepository.kt chứa class StreamRepository

// Resource IDs: snake_case với prefix
// Layouts: screen_stream.xml, component_video_player.xml
// Strings: label_connect, error_no_network, title_settings
// Drawables: ic_play_24dp, bg_video_overlay
```

### 4.2 File Organization

```kotlin
// Thứ tự trong file Kotlin:
// 1. Package declaration
// 2. Imports (auto-sorted by IDE)
// 3. File-level annotations
// 4. Class declaration
//    a. Companion object
//    b. Properties (DI injected, then private)
//    c. Init block (nếu cần)
//    d. Public functions
//    e. Private functions
// 5. Extension functions (nếu liên quan)
// 6. Preview functions (với @Preview annotation)
```

### 4.3 Import Rules

```kotlin
// ✅ Luôn dùng explicit import, không dùng wildcard import
import com.reka.remoteplay.feature.streaming.domain.model.StreamUrl

// ❌ WRONG
import com.reka.remoteplay.feature.streaming.domain.model.*
```

---

## 5. QUY TẮC DEPENDENCY INJECTION (Hilt Rules)

```kotlin
// ✅ CHUẨN — Scope đúng với lifecycle
@Module
@InstallIn(SingletonComponent::class)  // Dùng cho Network, DB
object NetworkModule {
    @Provides @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()
}

@Module
@InstallIn(ViewModelComponent::class)  // Dùng cho ViewModel-scoped deps
object StreamModule { }

// ✅ CHUẨN — Bind interface, không bind implementation
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton
    abstract fun bindStreamRepository(
        impl: StreamRepositoryImpl
    ): StreamRepository  // ← Bind vào interface
}

// ❌ WRONG — Provide trực tiếp implementation
@Provides
fun provideStreamRepository(): StreamRepositoryImpl // ← Expose impl detail
```

---

## 6. QUY TẮC TESTING (Testing Rules)

### 6.1 Naming Convention cho Tests

```kotlin
// Format: `when [điều kiện], [hành vi mong đợi]`
@Test
fun `when server id is invalid, returns failure result`() { }

@Test
fun `when network unavailable, emits error state`() { }

@Test
fun `when stream connected, updates ui state to connected`() { }
```

### 6.2 Test Structure — AAA Pattern

```kotlin
@Test
fun `when getStreamUrl called, returns success`() = runTest {
    // Arrange (Given)
    val serverId = "server-123"
    val expectedUrl = StreamUrl("rtc://fake-url")
    coEvery { repository.getStreamUrl(serverId) } returns Result.success(expectedUrl)
    
    // Act (When)
    val result = useCase(serverId)
    
    // Assert (Then)
    assertThat(result.isSuccess).isTrue()
    assertThat(result.getOrNull()).isEqualTo(expectedUrl)
    coVerify(exactly = 1) { repository.getStreamUrl(serverId) }
}
```

### 6.3 Fake vs Mock

```kotlin
// Dùng Fake (implementation giả) cho Repository
class FakeStreamRepository : StreamRepository {
    var shouldSucceed = true
    val connectedServers = mutableListOf<String>()
    
    override suspend fun getStreamUrl(id: String): Result<StreamUrl> {
        return if (shouldSucceed) Result.success(StreamUrl("fake://url"))
        else Result.failure(IOException("Fake network error"))
    }
}

// Dùng Mock (mockk) cho dependencies phức tạp hoặc ít dùng
val apiService = mockk<StreamApiService>()
```

---

## 7. QUY TẮC BẢO MẬT (Security Rules)

```
✅ Tất cả API keys phải lưu trong local.properties (gitignored)
✅ Tất cả sensitive config truy cập qua BuildConfig
✅ Certificate pinning bắt buộc cho production WebSocket connections
✅ Không log bất kỳ PII (Personal Identifiable Information) nào
✅ Không log WebRTC SDP/ICE candidates trong release build
✅ ProGuard/R8 phải được enable cho release builds

❌ NGHIÊM CẤM commit file chứa: API_KEY, SECRET, PASSWORD, TOKEN
❌ Không disable SSL verification dù chỉ để test
❌ Không lưu credentials trong SharedPreferences dạng plaintext
```

---

## 8. QUY TẮC .SKILLS (Skills System Rules)

### 8.1 Cách đọc Skills

Trước mỗi task, AI phải kiểm tra `.skills/INDEX.md`:

```bash
# Đọc index trước
cat .skills/INDEX.md

# Nếu có skill phù hợp, đọc skill đó
cat .skills/<skill-name>/SKILL.md

# Áp dụng hướng dẫn từ skill vào task
```

### 8.2 Cấu trúc .skills

```
.skills/
├── INDEX.md                  ← BẮT BUỘC ĐỌC TRƯỚC — Danh mục tất cả skills
├── docs/
│   ├── code-standards.md     ← Bổ sung chi tiết cho rules.md này
│   ├── system-architecture.md
│   └── webrtc-integration.md ← Hướng dẫn chi tiết WebRTC cho project này
├── plans/                    ← Lưu tất cả Plans (AGENTS.md Section 5)
├── research/                 ← Kết quả của researcher agent
├── templates/                ← Template code chuẩn
│   ├── Feature.md            ← Hướng dẫn tạo feature mới end-to-end
│   ├── usecase.kt
│   ├── viewmodel.kt
│   ├── repository_interface.kt
│   └── repository_impl.kt
└── checklists/               ← Checklist theo từng loại task
    ├── new-feature.md
    ├── bug-fix.md
    └── refactor.md
```

### 8.3 Khi nào dùng Skill nào

| Loại Task | Skill cần đọc |
|---|---|
| Tạo feature mới | `.skills/templates/Feature.md` |
| Implement Use Case | `.skills/templates/usecase.kt` |
| Implement ViewModel | `.skills/templates/viewmodel.kt` |
| Implement Repository | `.skills/templates/repository_impl.kt` |
| Debug WebRTC | `.skills/docs/webrtc-integration.md` |
| Code review | `.skills/checklists/` |
| Bất kỳ task nào | `.skills/INDEX.md` (đọc đầu tiên) |

### 8.4 Tạo Skill mới

Khi phát hiện pattern lặp lại > 2 lần, AI phải:
1. Tạo file skill tương ứng trong `.skills/`
2. Cập nhật `.skills/INDEX.md`
3. Commit với message: `docs(skills): add <skill-name> skill`

---

## 9. CHANGELOG & VERSIONING

### 9.1 Semantic Versioning

```
MAJOR.MINOR.PATCH
  │     │     └── Bug fixes (không breaking)
  │     └──────── Tính năng mới (không breaking)
  └────────────── Breaking changes
```

### 9.2 Cập nhật CHANGELOG.md

Mỗi khi merge vào master, cập nhật `CHANGELOG.md`:

```markdown
## [1.2.0] - 2025-XX-XX

### Added
- Tính năng adaptive bitrate cho WebRTC streaming

### Fixed  
- Lỗi reconnection khi chuyển từ WiFi sang 4G

### Changed
- Cải thiện UI cho màn hình kết nối

### Security
- Thêm certificate pinning cho WebSocket
```

---

## 10. CHECKLIST TRƯỚC KHI COMMIT

```
[ ] ./gradlew compileDebugKotlin → Không có lỗi
[ ] ./gradlew lintDebug → Không có lỗi critical
[ ] ./gradlew testDebugUnitTest → Tất cả tests pass
[ ] Không có file nào > 500 dòng
[ ] Không có hardcoded strings/colors/dimensions/URLs
[ ] Tất cả Composable screens có @Preview
[ ] Tất cả network calls có error handling
[ ] WebRTC resources được release trong onDestroy
[ ] Commit message theo Conventional Commits format
[ ] CHANGELOG.md đã được cập nhật
[ ] .skills/plans/ plan đã được cập nhật trạng thái
[ ] Ghi lại bài học và trạng thái vào .gemini/memory/JOURNAL.md

---

## 11. QUY TẮC TRÍ NHỚ DÀI HẠN (Memory Rules)

### 11.1 Ghi chép sau mỗi Task
Sau khi hoàn thành một công việc (Feature, Bug fix, Refactor), AI **PHẢI** cập nhật `.gemini/memory/JOURNAL.md` với các thông tin:
- Ngày tháng và Tên công việc.
- Các quyết định kiến trúc quan trọng đã đưa ra.
- Các vấn đề phát sinh và cách giải quyết (Lessons Learned).
- Trạng thái hiện tại của hệ thống để phiên làm việc tiếp theo có thể tiếp nối ngay lập tức.

### 11.2 Đọc trí nhớ khi bắt đầu
Khi bắt đầu một phiên làm việc mới (Bootstrap), AI **PHẢI** đọc 3-5 mục gần nhất trong `JOURNAL.md` để nắm bắt bối cảnh mà không cần hỏi lại người dùng.

---

*Phiên bản: V6*
*Liên kết: GEMINI.md | AGENTS.md | .skills/INDEX.md | .gemini/memory/JOURNAL.md*
*Nguyên tắc cốt lõi: Clean Code · Clean Architecture · Zero Bug Tolerance · Continuity*

