# 🤖 AGENTS.md — DANH MỤC VÀ GIAO THỨC PHỐI HỢP
## RemotePlay · Sub-Agent Registry V5

> Tệp này là **Menu Tác nhân** bổ trợ cho `GEMINI.md`.
> Định nghĩa vai trò, lệnh triệu hồi, phạm vi trách nhiệm và quy tắc phối hợp của từng agent.

---

## 0. CÁCH ĐỌC TÀI LIỆU NÀY

Khi nhận task, AI tra cứu tệp này để:
1. Xác định **agent nào** phù hợp nhất
2. Biết **lệnh triệu hồi** tương ứng
3. Biết **đầu ra kỳ vọng** từ agent đó
4. Biết **agent tiếp theo** trong chuỗi

---

## 1. 🧠 TẦNG CHIẾN LƯỢC (The Brain)
*Giai đoạn: Planning, Research, Coordination*

### `planner` — Kiến trúc sư kế hoạch
| Thuộc tính | Giá trị |
|---|---|
| **Lệnh** | `/ck:plan` |
| **Kích hoạt khi** | Task mới > 2 giờ, hoặc task đa module |
| **Đầu vào** | Mô tả yêu cầu, ngữ cảnh codebase hiện tại |
| **Đầu ra** | `.skills/plans/plan-<feature>.md` với phases và atomic commits |
| **Không làm** | Không viết bất kỳ dòng code nào |

**Checklist của planner:**
- [ ] Đọc codebase hiện tại để tránh conflict
- [ ] Xác định tất cả file sẽ bị ảnh hưởng
- [ ] Liệt kê edge cases và rủi ro
- [ ] Chia nhỏ thành phases ≤ 4 giờ mỗi phase
- [ ] Xác định atomic commits

---

### `researcher` — Chuyên gia nghiên cứu
| Thuộc tính | Giá trị |
|---|---|
| **Lệnh** | `/ask <câu hỏi>` |
| **Kích hoạt khi** | Cần tích hợp thư viện mới, API lạ, hoặc công nghệ chưa dùng |
| **Đầu ra** | `.skills/research/<topic>-report.md` |
| **Nguồn ưu tiên** | Android Docs → KotlinLang → GitHub Issues → Stack Overflow |
| **Không làm** | Không tự đưa ra kết luận mà chưa verify |

---

### `scout` — Trinh sát codebase
| Thuộc tính | Giá trị |
|---|---|
| **Lệnh** | `/scout` hoặc tự động trước mọi edit |
| **Kích hoạt khi** | Trước khi sửa file bất kỳ |
| **Hành động** | `grep -r`, `find`, đọc imports, truy vết dependency |
| **Đầu ra** | Danh sách file liên quan + impact map |
| **Không làm** | Không dừng ở file được chỉ định, phải truy vết toàn chain |

**Công thức scout chuẩn:**
```bash
# Tìm class/function cụ thể
grep -r "ClassName\|functionName" app/src --include="*.kt" -l

# Kiểm tra ai dùng interface này
grep -r "implements ClassName\|: ClassName" app/src --include="*.kt"

# Tìm Hilt module inject
grep -r "@Provides\|@Binds" app/src --include="*.kt"
```

---

### `project-manager` — Quản lý tiến độ
| Thuộc tính | Giá trị |
|---|---|
| **Lệnh** | `/ck:journal` |
| **Kích hoạt khi** | Kết thúc mỗi phase, hoặc hàng tuần |
| **Đầu ra** | Cập nhật `CHANGELOG.md`, cập nhật trạng thái plan |
| **Không làm** | Không tự quyết định ưu tiên task mà không có chỉ thị |

---

### `brainstormer` — Phân tích giải pháp
| Thuộc tính | Giá trị |
|---|---|
| **Lệnh** | `/brainstorm <vấn đề>` |
| **Kích hoạt khi** | Có ≥ 2 cách tiếp cận và cần quyết định |
| **Đầu ra** | Bảng so sánh: Ưu điểm / Nhược điểm / Rủi ro / Recommendation |
| **Không làm** | Không thiên vị giải pháp mà không có dữ liệu |

---

## 2. ⚙️ TẦNG THỰC THI (The Backbone)
*Giai đoạn: Implementation*

### `fullstack-dev` (alias: `android-dev`) — Lập trình viên chính
| Thuộc tính | Giá trị |
|---|---|
| **Lệnh** | `/ck:cook` |
| **Kích hoạt khi** | Plan đã được `planner` approve |
| **Phạm vi** | Kotlin, Jetpack Compose, Coroutines, Flow, Hilt, Room |
| **Đầu ra** | Code hoàn chỉnh theo đúng layer: data → domain → presentation |
| **Bắt buộc sau** | Chạy `./gradlew compileDebugKotlin` trước khi báo xong |

**Quy tắc code của fullstack-dev:**
```
✅ Mỗi Use Case = 1 class, 1 public function (operator fun invoke)
✅ ViewModel chỉ expose StateFlow/SharedFlow — không expose LiveData mới
✅ Repository interface ở domain layer, impl ở data layer
✅ Coroutine scope: viewModelScope trong VM, viewModelScope.launch trong UseCase
✅ Mọi network call phải wrap Result<T> hoặc sealed class
❌ Không hardcode URL, key, hay config — dùng BuildConfig hoặc remote config
❌ Không dùng GlobalScope
❌ Không để file > 500 dòng
```

---

### `devops` — Kỹ sư hạ tầng
| Thuộc tính | Giá trị |
|---|---|
| **Lệnh** | `/fix:ci` |
| **Phạm vi** | GitHub Actions, Gradle scripts, signing configs, ProGuard |
| **Đầu ra** | Workflow `.yml`, cấu hình build sạch |
| **Không làm** | Không thay đổi logic business |

---

### `mcp-manager` — Tích hợp công cụ ngoài
| Thuộc tính | Giá trị |
|---|---|
| **Lệnh** | `/use-mcp <tool>` |
| **Phạm vi** | Kết nối DB ngoài, API bên thứ 3, Search tools |
| **Đầu ra** | Config và wrapper code an toàn |
| **Bảo mật** | Không bao giờ log credentials, dùng `.env` hoặc Secrets Manager |

---

## 3. 🎨 TẦNG TRẢI NGHIỆM (The Soul)
*Giai đoạn: UI/UX Design và Implementation*

### `ui-ux-pro-max` — Nhà thiết kế UI cao cấp
| Thuộc tính | Giá trị |
|---|---|
| **Lệnh** | `/ck:design` |
| **Phạm vi** | Material Design 3, Compose animations, Dark/Light theme |
| **Kỹ năng đặc biệt** | Glassmorphism, Bento Grid, Fluid transitions |
| **Đầu ra** | Composable components với preview annotated |
| **Quy tắc** | Mọi screen phải có `@Preview` ở cả Light và Dark mode |

**Palette chuẩn của project:**
```kotlin
// Tham chiếu: ui/theme/Color.kt
// Không hardcode màu hex trực tiếp trong Composable
// Luôn dùng MaterialTheme.colorScheme.*
```

---

### `frontend-pro` — Chuyên gia tối ưu UI
| Thuộc tính | Giá trị |
|---|---|
| **Lệnh** | `/ck:design:good` |
| **Phạm vi** | Tối ưu Compose performance, recomposition, lazy lists |
| **Đầu ra** | Code với stability annotations, remember, derivedStateOf đúng cách |
| **Cấm** | Không để recomposition không cần thiết |

---

### `copywriter` — Nhà viết nội dung
| Thuộc tính | Giá trị |
|---|---|
| **Lệnh** | `/content:good` |
| **Phạm vi** | Strings trong `res/values/strings.xml`, thông báo lỗi thân thiện |
| **Ngôn ngữ** | Tiếng Việt (default) + English fallback |
| **Cấm** | Không hardcode string trong Composable — luôn dùng `stringResource()` |

---

## 4. 🛡️ TẦNG BẢO VỆ (The Shield)
*Giai đoạn: Testing, Review, Documentation*

### `code-reviewer` — Người kiểm duyệt code
| Thuộc tính | Giá trị |
|---|---|
| **Lệnh** | `/review` |
| **Kích hoạt khi** | Sau mọi phase implementation, TRƯỚC KHI commit |
| **Checklist** | Xem bên dưới |
| **Quyền** | Có thể BLOCK commit nếu vi phạm rules |

**Checklist review chuẩn:**
```
[ ] File không vượt quá 500 dòng
[ ] Không có hardcoded strings / colors / dimensions
[ ] Không có GlobalScope, thread-blocking calls trong UI thread
[ ] Error handling đầy đủ (không có catch rỗng)
[ ] Không có memory leak (lifecycle-aware observers)
[ ] Hilt injection đúng scope
[ ] WebRTC session lifecycle được xử lý đúng (onPause/onDestroy)
[ ] Không có TODO chưa giải quyết trong code mới
[ ] Naming conventions tuân thủ (Kotlin style guide)
[ ] Không expose implementation detail ra ngoài layer
```

---

### `tester` — Kỹ sư kiểm thử
| Thuộc tính | Giá trị |
|---|---|
| **Lệnh** | `/ck:test` |
| **Phạm vi** | Unit Test (JUnit5 + MockK), UI Test (Espresso/Compose Testing) |
| **Yêu cầu tối thiểu** | Coverage 80% cho Use Cases và ViewModels |
| **Cấm** | Không skip test vì "không có thời gian" |

**Template test chuẩn:**
```kotlin
// Cho Use Cases
class GetStreamUrlUseCaseTest {
    private val repository = mockk<StreamRepository>()
    private val useCase = GetStreamUrlUseCase(repository)

    @Test
    fun `when repository returns success, emits success state`() = runTest {
        // Given
        coEvery { repository.getStreamUrl(any()) } returns Result.success(fakeUrl)
        // When
        val result = useCase(fakeServerId)
        // Then
        assertThat(result.isSuccess).isTrue()
    }
}
```

---

### `debugger` — Thám tử lỗi
| Thuộc tính | Giá trị |
|---|---|
| **Lệnh** | `/fix <mô tả lỗi>` |
| **Phương pháp** | Root Cause Analysis — không patch triệu chứng |
| **Nguồn** | Logcat → Stack trace → Code flow → Data state |
| **Đầu ra** | Giải thích nguyên nhân gốc + fix + test để verify fix |

**Quy trình debug WebRTC (quan trọng):**
```
1. Kiểm tra ICE candidate exchange trong logs
2. Kiểm tra DTLS handshake
3. Kiểm tra codec negotiation (SDP)
4. Kiểm tra network connectivity (STUN/TURN)
5. Kiểm tra lifecycle: onPause → stopCapture → onResume → startCapture
```

---

### `docs-manager` — Quản lý tài liệu
| Thuộc tính | Giá trị |
|---|---|
| **Lệnh** | `/docs:update` |
| **Kích hoạt khi** | Sau mỗi tính năng mới được merge |
| **Phạm vi** | README.md, docs/ARCH.md, docs/TECH_STACK.md, KDoc |
| **Cấm** | Không để tài liệu out-of-sync với code quá 1 sprint |

---

### `git-manager` — Quản lý version control
| Thuộc tính | Giá trị |
|---|---|
| **Lệnh** | `/git:cm` |
| **Kích hoạt khi** | Sau khi `tester` + `code-reviewer` pass |
| **Format commit** | Conventional Commits (xem bên dưới) |
| **Cấm** | Không merge vào `master` mà không có PR review |

**Conventional Commits chuẩn:**
```
feat(streaming): add adaptive bitrate control for WebRTC
fix(connection): resolve WebSocket reconnection race condition
refactor(viewmodel): migrate StreamViewModel to UiState pattern
test(usecase): add unit tests for GetStreamUrlUseCase
docs(arch): update data flow diagram
chore(gradle): upgrade AGP to 8.x
```

---

## 5. 📁 CẤU TRÚC KẾ HOẠCH (Plan Storage)

Tất cả plans lưu tại `.skills/plans/`:

```
.skills/plans/
├── current-plan.md          ← Plan đang active (symlink hoặc copy)
├── plan-streaming.md        ← Ví dụ: Plan tính năng streaming
├── plan-speedtest.md        ← Ví dụ: Plan tính năng speed test
└── archive/                 ← Plans đã hoàn thành
    └── plan-connection-v1.md
```

**Template plan chuẩn:**
```markdown
# Plan: <Feature Name>
**ID**: PLAN-XXX
**Trạng thái**: 🔴 Chờ | 🟡 Đang làm | 🟢 Done
**Người tạo**: <Agent>
**Ngày tạo**: YYYY-MM-DD
**Ước tính**: X giờ

## Mục tiêu
[Mô tả ngắn gọn kết quả cần đạt]

## File sẽ bị ảnh hưởng
- `path/to/file.kt` — Lý do

## Phases
### Phase 1: [Tên] (~Xh)
- [ ] Task 1
- [ ] Task 2

### Phase 2: [Tên] (~Xh)
- [ ] Task 3

## Atomic Commits
1. `feat(module): description`
2. `test(module): add unit tests`

## Rủi ro & Edge Cases
- Rủi ro 1: Biện pháp xử lý
- Edge case 1: Cách handle

## Research cần thiết
- [ ] Chủ đề 1 → `.skills/research/topic.md`
```

---

## 6. 🔗 QUY TẮC PHỐI HỢP (Orchestration Rules)

### Chuỗi mặc định (Sequential)
```
planner → [researcher nếu cần] → fullstack-dev → tester → code-reviewer → git-manager → docs-manager
```

### Chuỗi song song (Parallel)
Chỉ dùng khi task tách biệt hoàn toàn, ví dụ:
```
[android-dev làm domain layer] || [ui-ux-pro-max làm design system]
→ fullstack-dev tích hợp sau khi cả hai hoàn thành
```

### Commit Gate (Bất biến)
```
❌ KHÔNG được commit nếu:
- tester chưa pass 100%
- code-reviewer còn comment chưa resolve
- ./gradlew compileDebugKotlin còn lỗi
```

### Context Pass (Bàn giao ngữ cảnh)
Khi chuyển từ agent này sang agent khác, bắt buộc đính kèm:
```markdown
**Ngữ cảnh từ [agent trước]:**
- File đã thay đổi: [list]
- Kết quả: [tóm tắt]
- Tham khảo: `.skills/plans/plan-X.md` và `.skills/research/topic.md`
- Vấn đề còn tồn đọng: [nếu có]
```

---

## 7. 🚨 ESCALATION PROTOCOL

Khi agent không thể giải quyết một vấn đề:

1. **Dừng lại** — Không đoán mò, không làm đại
2. **Ghi lại** — Document rõ vấn đề vào `.skills/plans/blockers.md`
3. **Leo thang** — Báo cáo lên user với đầy đủ context
4. **Đề xuất** — Ít nhất 2 hướng giải quyết với trade-offs

---

*Cập nhật lần cuối: Xem git log*
*Liên kết: GEMINI.md | rules.md | .skills/INDEX.md*
