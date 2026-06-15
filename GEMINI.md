# 🌌 GEMINI OS — GIAO THỨC THỰC THI TỐI THƯỢNG
## RemotePlay · Android Client · Phiên bản V5-AUTONOMOUS

> Tệp này là **Hiến pháp AI** dành cho Gemini khi làm việc trên codebase RemotePlay.
> Đọc toàn bộ trước khi thực thi bất kỳ task nào. Không bỏ qua bất kỳ mục nào.

---

## 0. KHỞI ĐỘNG BẮT BUỘC (Bootstrap Protocol)

Trước khi làm bất kỳ việc gì, AI **PHẢI** thực hiện theo thứ tự:

```
1. Đọc: GEMINI.md          → Hiểu giao thức hiện tại
2. Đọc: AGENTS.md          → Biết danh sách sub-agents khả dụng
3. Đọc: rules.md           → Nắm vững quy tắc code cứng
4. Đọc: .skills/INDEX.md   → Tra cứu kỹ năng có sẵn
5. Đọc: .gemini/memory/JOURNAL.md → Tiếp nhận bối cảnh từ phiên trước
6. Scout codebase:         → grep/find để nắm ngữ cảnh thực tế
```

> ⚠️ **BỎ QUA BƯỚC NÀY = LỖI HỆ THỐNG**. Không giả định, không bịa đặt context.

---

## I. BẢN SẮC DỰ ÁN (Project Identity)

| Thuộc tính | Giá trị |
|---|---|
| **Tên** | RemotePlay |
| **Nền tảng** | Android (API 24+) |
| **Ngôn ngữ** | Kotlin (100%) |
| **UI** | Jetpack Compose |
| **Kiến trúc** | Clean Architecture + MVVM + Feature-based |
| **Network** | WebRTC · WebSocket · OkHttp · Retrofit |
| **Concurrency** | Coroutines + Flow |
| **DI** | Hilt |
| **Build** | Gradle KTS |

**Luồng dữ liệu bất biến:**
```
UI (Compose) → ViewModel → Use Case → Repository → Data Source (Network/Local)
```

---

## II. NĂNG LỰC CỐT LÕI (Core Capabilities)

### 1. 🧠 Deep Reasoning (Phân tích chiều sâu)
- **Kích hoạt**: Tự động với mọi task > 30 phút
- **Hành động**: Dùng `sequential-thinking` — phân rã vấn đề thành nguyên tử nhỏ nhất, tự phản biện trước khi thực thi
- **Cấm**: Không được đưa ra giải pháp đầu tiên mà không phân tích ít nhất 2 cách tiếp cận

### 2. 🔍 Context Synthesis (Tổng hợp ngữ cảnh)
- **Kích hoạt**: Trước mọi thay đổi code
- **Hành động**: Dùng `code_search` + `grep` để truy vết toàn bộ chuỗi logic: UI → ViewModel → UseCase → Repository → DataSource
- **Cấm**: Không được chỉnh sửa file mà không biết file đó ảnh hưởng đến module nào

### 3. 🤖 Autonomous Agency (Tự quản lý)
- **Kích hoạt**: Task > 2 giờ hoặc task đa module
- **Hành động**: Phối hợp `planner` + `fullstack-dev`. Tự động tạo plan tại `.skills/plans/` trước khi code
- **Cấm**: Không được tự ý commit mà không qua `tester` + `code-reviewer`

### 4. 🎨 Vision Synergy (Đồng bộ UI)
- **Kích hoạt**: Mọi task liên quan đến Compose UI
- **Hành động**: Review layout bằng screenshot hoặc preview trước khi bàn giao. Đối chiếu Code và UI
- **Cấm**: Không được ship UI mà chưa kiểm tra trên thiết bị thực hoặc emulator

---

## III. CẤU TRÚC THƯ MỤC CHUẨN (Canonical Structure)

```
app/src/main/java/com/reka/remoteplay/
├── core/
│   ├── network/          # OkHttp, WebRTC, WebSocket clients
│   ├── common/           # UI components dùng chung, Extensions
│   └── di/               # Hilt Modules
├── feature/
│   └── <feature_name>/
│       ├── data/
│       │   ├── repository/   # RepositoryImpl
│       │   ├── datasource/   # Remote/Local DataSource
│       │   └── dto/          # Data Transfer Objects
│       ├── domain/
│       │   ├── model/        # Domain Models
│       │   ├── repository/   # Repository Interfaces
│       │   └── usecase/      # Use Cases (1 class = 1 function)
│       └── presentation/
│           ├── screen/       # @Composable Screens
│           ├── viewmodel/    # ViewModels
│           └── component/    # Composable con của feature này
├── ui/
│   ├── theme/            # Color, Typography, Theme
│   └── component/        # Global shared Composables
└── service/              # Android Foreground Services
```

---

## IV. QUY TRÌNH VẬN HÀNH (SOPs 5.0)

### Phase 1 — Observe (Quan sát)
```bash
# Bắt buộc chạy trước khi code
find app/src -name "*.kt" | head -50          # Tổng quan file
grep -r "TODO\|FIXME\|HACK" app/src           # Nợ kỹ thuật hiện tại
cat .skills/plans/current-plan.md             # Plan đang active (nếu có)
```

### Phase 2 — Plan (Lập kế hoạch)
- Task < 30 phút: Comment inline, thực thi trực tiếp
- Task 30 phút – 2 giờ: Tạo checklist trong response
- Task > 2 giờ: Tạo bắt buộc `.skills/plans/plan-<feature>.md`

**Template plan:**
```markdown
# Plan: <Tên Feature>
**Trạng thái**: [ ] Chờ | [ ] Đang làm | [ ] Done
**Ước tính**: X giờ
**Phases**:
- [ ] Phase 1: ...
- [ ] Phase 2: ...
**Atomic Commits**: (liệt kê từng commit nhỏ)
**Rủi ro**: (edge cases, breaking changes)
```

### Phase 3 — Cook (Thực thi)
- Viết code theo đúng cấu trúc layer
- Mỗi file KHÔNG ĐƯỢC vượt quá **500 dòng** (Hard Limit)
- Sau mỗi module hoàn thành: chạy `./gradlew compileDebugKotlin`

### Phase 4 — Test & Review
- Gọi agent `tester` để viết Unit Test
- Gọi agent `code-reviewer` để review
- **Commit Gate**: Test pass 100% mới được commit

### Phase 5 — Integrate & Document
- Cập nhật `README.md` nếu thêm tính năng mới
- Gọi agent `docs-manager` để sync tài liệu
- Gọi agent `git-manager` để tạo commit chuẩn

---

## V. HỆ THỐNG LỆNH TẮT (Hyper-Commands)

| Lệnh | Hành động |
|---|---|
| `/ck:plan` | Khởi động Phase 1+2: Observe → Plan |
| `/ck:cook` | Khởi động Phase 3: Thực thi code |
| `/ck:test` | Khởi động Phase 4: Test + Review |
| `/ck:done` | Khởi động Phase 5: Integrate + Docs + Commit |
| `/ck:fix <mô tả>` | Debug mode: Phân tích lỗi từ Logcat/Sentry |
| `/ck:ui` | UI mode: Thiết kế/nâng cấp giao diện Compose |
| `/ck:refactor` | Refactor mode: Tái cấu trúc không phá vỡ logic |
| `/scout` | Quét codebase tìm file và logic liên quan |
| `/ask <câu hỏi>` | Gọi researcher tra cứu tài liệu/thư viện |

---

## VI. TÀI NGUYÊN CHIẾN LƯỢC (Strategic Resources)

```
.skills/
├── INDEX.md                          ← Danh mục tất cả kỹ năng (ĐỌC TRƯỚC)
├── docs/
│   ├── code-standards.md             ← Quy chuẩn code Kotlin/Compose
│   ├── system-architecture.md        ← Bản đồ hệ thống
│   └── webrtc-integration.md         ← Hướng dẫn WebRTC
├── plans/                            ← Lưu trữ tất cả Plans
├── templates/
│   ├── usecase.kt                    ← Template Use Case
│   ├── viewmodel.kt                  ← Template ViewModel
│   └── repository.kt                 ← Template Repository
└── research/                         ← Kết quả nghiên cứu từ Researcher agent
```

---

## VII. LỜI NHẮC TỰ THÂN (Self-Reflection Manifesto)

> **Đừng chỉ làm đúng — hãy làm xuất sắc.**
> Mỗi dòng code là một cam kết với người dùng cuối đang cầm thiết bị.

- **Context là tài sản**: Cập nhật `GEMINI.md` và `AGENTS.md` ngay khi có thay đổi hệ thống
- **Tài liệu là code**: `README.md` và tài liệu kỹ thuật là sản phẩm, không phải phụ lục
- **Fail fast, fix faster**: Phát hiện lỗi sớm ở compile-time hơn runtime
- **WebRTC là trái tim**: Mọi thay đổi liên quan đến streaming cần được test kỹ trên mạng thực tế
- **User experience là luật**: Lag, jank, freeze là bug nghiêm trọng — không kém gì crash

---

*Cập nhật lần cuối: Xem git log*
*Phiên bản: V5-AUTONOMOUS*
*Liên kết: AGENTS.md | rules.md | .skills/INDEX.md*
