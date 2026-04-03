# 🤖 AGENT REGISTRY & ORCHESTRATION REFERENCE

Tệp này là danh mục kỹ thuật bổ trợ cho `GEMINI.md`. Nó định nghĩa chi tiết vai trò của các Sub-agents và cách triệu hồi chúng thông qua hệ thống lệnh.

---

## 1. CHIẾN LƯỢC & QUẢN TRỊ (Management - The Brain)
*Dùng cho Phase 1 (Planning) và Phase 5 (Integration).*

| Agent | Lệnh triệu hồi | Vai trò cụ thể |
|:---|:---|:---|
| **`planner`** | `/ck:plan` | Thiết kế `plan.md` và chia nhỏ Phases. |
| **`researcher`** | `/ask` | Nghiên cứu công nghệ mới, thư viện bên thứ 3. |
| **`scout`** | `/scout` | Quét codebase để tìm liên kết logic và file liên quan. |
| **`project-manager`** | `/ck:journal` | Cập nhật Roadmap, Changelog và báo cáo tiến độ. |
| **`brainstormer`** | `/brainstorm` | Phân tích ưu/nhược điểm của các giải pháp kiến trúc. |

---

## 2. KIẾN TRÚC & LOGIC (The Backbone)
*Dùng cho Phase 2 (Implementation).*

| Agent | Lệnh triệu hồi | Vai trò cụ thể |
|:---|:---|:---|
| **`fullstack-dev`** | `/ck:cook` | Thực thi mã nguồn Backend/Frontend từ Plan. |
| **`database-admin`** | (Manual) | Thiết kế Schema, tối ưu SQL/NoSQL queries. |
| **`devops`** | `/fix:ci` | Quản lý Docker, CI/CD, Cloudflare và hạ tầng. |
| **`mcp-manager`** | `/use-mcp` | Kết nối các công cụ ngoài (DB, API, Search). |

---

## 3. TRẢI NGHIỆM & THẨM MỸ (The Soul)
*Dùng cho Phase 2 (Design) và UI Enhancements.*

| Agent | Lệnh triệu hồi | Vai trò cụ thể |
|:---|:---|:---|
| **`ui-ux-pro-max`** | `/ck:design` | Palette màu, Typography, Animation (Anime.js). |
| **`ui-ux-designer`** | (Manual) | Wireframes, thiết kế layout Responsive. |
| **`frontend-pro`** | `/ck:design:good` | Gen assets, xử lý ảnh thật, tối ưu độ mượt UI. |
| **`copywriter`** | `/content:good` | Viết nội dung thông điệp, hướng dẫn sử dụng. |

---

## 4. KIỂM SOÁT & BẢO MẬT (The Shield)
*Dùng cho Phase 3 (Testing) và Phase 4 (Review).*

| Agent | Lệnh triệu hồi | Vai trò cụ thể |
|:---|:---|:---|
| **`code-reviewer`** | `/review` | Kiểm tra tiêu chuẩn 500 dòng, bảo mật và logic. |
| **`tester`** | `/ck:test` | Viết Unit/UI Test (Junit, Espresso, KMP). |
| **`debugger`** | `/fix` | Phân tích Logcat/Sentry để truy tìm Root Cause. |
| **`docs-manager`** | `/docs:update` | Duy trì `README.md`, API Docs và hệ thống tài liệu. |
| **`git-manager`** | `/git:cm` | Quản lý Commits, PRs và Branching. |

---

## 5. CẤU TRÚC KẾ HOẠCH (Plan Structure)
Mọi Task phức tạp (>2h) đều phải lưu trữ tại `.skills/plans/` theo định dạng:
- `plan.md`: Tổng quan & Trạng thái.
- `phase-XX.md`: Chi tiết thực thi từng giai đoạn.
- `research/`: Kết quả từ Researcher Agent.

---

## 6. QUY TẮC PHỐI HỢP (Orchestration Rules)

1. **Sequential (Chuỗi)**: `planner` → `dev` → `tester` → `reviewer`.
2. **Parallel (Song song)**: Triển khai khi task tách biệt (ví dụ: Frontend và Backend).
3. **Commit Gate**: Luôn yêu cầu `tester` pass 100% trước khi `git-manager` thực hiện commit.
4. **Context Pass**: Khi chuyển giao task giữa các agent, phải đính kèm đường dẫn tới báo cáo (`research-report.md`).

---
**💡 Ghi chú cho Gemini 3:** Sử dụng tệp này như một "Menu" để chọn đúng Sub-agent hỗ trợ, giúp tối ưu hóa Token và chất lượng đầu ra.
