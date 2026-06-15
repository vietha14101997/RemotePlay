---
name: agent-registry
description: >
  Danh mục agent và lệnh triệu hồi cho hệ thống điều phối dự án. Tham chiếu file này khi cần xác định agent nào phù hợp cho một task cụ thể. Không phải skill tự thực thi — đây là "menu" để AI-OS và người dùng chọn đúng chuyên gia.
---

Khi nhận được một task, tra cứu bảng dưới đây để xác định agent phù hợp. Mỗi agent có phạm vi trách nhiệm rõ ràng — không giao task ngoài phạm vi đó.

## Nguyên tắc sử dụng

- **Đúng agent, đúng lúc**: `planner` không viết code. `fullstack-dev` không lập kế hoạch chiến lược.
- **Commit gate**: `tester` phải pass 100% trước khi `git-manager` commit bất cứ thứ gì.
- **Context pass**: Khi chuyển giao, luôn kèm đường dẫn file và output của bước trước.

---

## 1. Chiến lược & Quản trị
*Phase 1 (Planning) và Phase 5 (Integration)*

| Agent | Lệnh | Khi nào dùng |
|:---|:---|:---|
| `planner` | `/ck:plan` | Task mới cần chia nhỏ thành phases và atomic commits |
| `researcher` | `/ask` | Cần đánh giá thư viện bên thứ ba, công nghệ mới, hoặc best practice |
| `scout` | `/scout` | Cần tìm file liên quan, truy vết logic trong codebase hiện có |
| `project-manager` | `/ck:journal` | Cập nhật Roadmap, Changelog, báo cáo tiến độ cuối phase |
| `brainstormer` | `/brainstorm` | So sánh ≥2 giải pháp kiến trúc, cần phân tích trade-off rõ ràng |

---

## 2. Kiến trúc & Logic
*Phase 2 (Implementation)*

| Agent | Lệnh | Khi nào dùng |
|:---|:---|:---|
| `fullstack-dev` | `/ck:cook` | Thực thi mã nguồn Backend/Frontend từ plan đã được confirm |
| `database-admin` | (manual) | Thiết kế/tối ưu Schema, viết migration scripts, query optimization |
| `devops` | `/fix:ci` | Docker, CI/CD pipeline, cloud infra, Cloudflare config |
| `mcp-manager` | `/use-mcp` | Kết nối công cụ ngoài: Database, third-party API, Search |

**Giới hạn cứng**: Mọi file code do agents này tạo ra không được vượt **500 dòng**. Nếu vượt, yêu cầu tách module trước khi tiếp tục.

---

## 3. Trải nghiệm & Thẩm mỹ
*Phase 2 (Design) và UI Enhancements*

| Agent | Lệnh | Khi nào dùng |
|:---|:---|:---|
| `ui-ux-pro-max` | `/ck:design` | Xác định Design System: palette màu, typography, motion guidelines |
| `ui-ux-designer` | (manual) | Wireframes và layout responsive — trước khi viết code |
| `frontend-pro` | `/ck:design:good` | Tối ưu assets, xử lý ảnh, polish UI theo pixel-perfect standard |
| `copywriter` | `/content:good` | Viết microcopy, onboarding text, thông báo lỗi thân thiện |

**Quy tắc**: Luôn có wireframe hoặc design spec được duyệt trước khi `frontend-pro` viết code. Không viết UI từ mô tả bằng lời.

---

## 4. Kiểm soát & Bảo mật
*Phase 3 (Testing) và Phase 4 (Review)*

| Agent | Lệnh | Khi nào dùng |
|:---|:---|:---|
| `code-reviewer` | `/review` | Kiểm tra security, business logic, code standards sau mỗi phase |
| `tester` | `/ck:test` | Viết và chạy Unit/Integration/UI Tests — bắt buộc trước commit |
| `debugger` | `/fix` | Phân tích Logcat/Sentry/logs để tìm Root Cause — không đoán mò |
| `docs-manager` | `/docs:update` | Cập nhật README, API Docs, CHANGELOG sau mỗi release |
| `git-manager` | `/git:cm` | Commit, PR, branch management — chỉ khi tester đã pass |

---

## 5. Luồng phối hợp chuẩn

### Sequential (task phụ thuộc nhau)
```
planner → fullstack-dev → tester → code-reviewer → git-manager
```

### Parallel (task độc lập)
```
fullstack-dev (Backend API)  ──┐
                               ├─→ tester → git-manager
frontend-pro (UI Components) ──┘
```

### Hotfix (sửa lỗi khẩn)
```
debugger (Root Cause) → fullstack-dev (Fix) → tester → git-manager
```

---

## 6. Template chuyển giao context

Khi bàn giao từ agent này sang agent khác, dùng template:

```markdown
## Handoff: [agent-nguồn] → [agent-đích]

**Task**: [Mô tả ngắn]
**Output của bước trước**: [Đường dẫn file / kết quả cụ thể]
**Files liên quan**: [Danh sách path]
**Definition of Done**: [Điều kiện hoàn thành đo lường được]
**Rủi ro cần lưu ý**: [Nếu có]
```
