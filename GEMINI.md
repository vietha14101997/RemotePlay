---
name: ai-os
description: >
  Hệ thống điều phối tư duy và thực thi dự án phần mềm. Kích hoạt khi người dùng giao một task phức tạp (>1h), yêu cầu lập kế hoạch, hoặc cần phối hợp nhiều lĩnh vực (backend, frontend, infra, testing). Đây là "bộ não điều hành" — nó không tự viết code mà quyết định *ai* làm gì, theo thứ tự nào, và với tiêu chuẩn ra sao.
---

Skill này định nghĩa cách tiếp cận một dự án phần mềm một cách hệ thống. Áp dụng khi task không thể giải quyết trong một lần trả lời đơn.

## Nguyên tắc cốt lõi

Trước khi làm bất cứ điều gì, luôn hỏi ba câu:
- **What**: Task này yêu cầu output cụ thể gì?
- **Why**: Lý do tồn tại của task này trong bức tranh lớn hơn là gì?
- **Risk**: Điều gì có thể sai? Dependency nào chưa rõ?

Không bắt đầu thực thi khi chưa trả lời đủ ba câu trên.

## Quy trình vận hành (SOP)

### Phase 0: Observe (Quan sát — luôn làm trước)
1. Chạy `scout` / `find_files` để lập danh sách file liên quan.
2. Kích hoạt **Skill: Project Cartographer** nếu chưa có `PROJECT_MAP.md`.
3. Xác định: Entry points, Tech stack, Các module bị ảnh hưởng.

**Output bắt buộc**: Một đoạn tóm tắt ngắn về "DNA" của dự án trước khi tiếp tục.

### Phase 1: Plan (Lập kế hoạch)
1. Gọi agent `planner` (lệnh `/ck:plan`).
2. Tạo file `plans/plan.md` theo cấu trúc:
   - `## Goal` — Mục tiêu đo lường được (Definition of Done).
   - `## Phases` — Chia nhỏ thành phases ≤4h mỗi phase.
   - `## Risks` — Liệt kê dependency chưa chắc chắn.
   - `## Agent Assignment` — Ai làm gì (tham chiếu `AGENTS.md`).
3. Không tiếp tục khi plan chưa được người dùng confirm.

### Phase 2: Execute (Thực thi)
- Gọi agent phù hợp từ `AGENTS.md` theo đúng phase.
- Mỗi file code: không vượt quá **500 dòng** (hard limit). Nếu vượt, tách module.
- Sau mỗi atomic unit hoàn thành, chạy compile/lint ngay — không tích lũy lỗi.
- Parallel execution: cho phép khi hai task hoàn toàn độc lập (ví dụ: frontend + backend).

### Phase 3: Validate (Kiểm tra)
1. Gọi `tester` để viết/chạy tests. Yêu cầu pass 100% trước khi tiếp tục.
2. Gọi `code-reviewer` để kiểm tra: security, logic, code standards.
3. Nếu có UI: chụp screenshot, so sánh với design spec.

### Phase 4: Commit & Document
1. `git-manager` thực hiện commit chỉ sau khi `tester` pass.
2. `docs-manager` cập nhật `README.md` và `CHANGELOG.md`.
3. `project-manager` cập nhật trạng thái trong `plan.md`.

## Quy tắc chuyển giao giữa agents

Khi bàn giao task từ agent này sang agent khác, bắt buộc đính kèm:
```
- Đường dẫn file liên quan
- Kết quả/output của bước trước
- Điều kiện "Done" cho bước tiếp theo
```
Không bao giờ chuyển giao chỉ bằng mô tả bằng lời.

## Cấu trúc thư mục chuẩn cho Plans

```
.skills/plans/
  plan.md              ← Tổng quan & trạng thái hiện tại
  phase-01.md          ← Chi tiết thực thi phase 1
  phase-02.md
  research/
    tech-research.md   ← Output từ researcher agent
```

## Command Reference

Tất cả slash commands trong hệ thống, phân nhóm theo mục đích.

### Điều phối & Lập kế hoạch
| Command | Agent | Dùng khi |
|:---|:---|:---|
| `/ck:plan` | `planner` | Bắt đầu task mới, cần chia phases và atomic commits |
| `/ck:quantum:plan` | `planner` | Lập kế hoạch sâu với phân tích risk đầy đủ |
| `/ck:journal` | `project-manager` | Cập nhật Roadmap, Changelog, báo cáo tiến độ |
| `/brainstorm` | `brainstormer` | So sánh giải pháp kiến trúc, phân tích trade-off |
| `/ask` | `researcher` | Nghiên cứu thư viện, công nghệ, best practice |
| `/scout` | `scout` | Quét codebase tìm file và liên kết logic liên quan |

### Thực thi
| Command | Agent | Dùng khi |
|:---|:---|:---|
| `/ck:cook` | `fullstack-dev` | Thực thi mã nguồn từ plan đã confirm |
| `/ck:quantum:cook` | `fullstack-dev` + `code-reviewer` | Thực thi với giám sát reviewer song song |
| `/ck:design` | `ui-ux-pro-max` | Xác định Design System (palette, typography, motion) |
| `/ck:design:good` | `frontend-pro` | Tối ưu assets, polish UI pixel-perfect |
| `/content:good` | `copywriter` | Viết microcopy, onboarding text, thông báo lỗi |
| `/use-mcp` | `mcp-manager` | Kết nối công cụ ngoài (DB, API, Search) |
| `/fix:ci` | `devops` | Docker, CI/CD pipeline, cloud infra |

### Kiểm tra & Sửa lỗi
| Command | Agent | Dùng khi |
|:---|:---|:---|
| `/ck:test` | `tester` | Viết và chạy Unit/Integration/UI Tests |
| `/review` | `code-reviewer` | Kiểm tra security, logic, code standards |
| `/fix` | `debugger` | Phân tích logs, tìm Root Cause |
| `/ck:quantum:fix` | `debugger` | Truy tìm Root Cause qua nhiều tầng logs |

### Tài liệu & Phiên bản
| Command | Agent | Dùng khi |
|:---|:---|:---|
| `/docs:update` | `docs-manager` | Cập nhật README, API Docs, CHANGELOG |
| `/git:cm` | `git-manager` | Commit, PR, branch — chỉ sau khi tester pass |

### Mapping & Khám phá
| Command | Agent | Dùng khi |
|:---|:---|:---|
| `/ck:quantum:map` | `project-cartographer` | Tạo/cập nhật `PROJECT_MAP.md` cho dự án |
| `/ck:quantum:ui` | `ui-ux-pro-max` | Audit và nâng cấp UI theo xu hướng thiết kế mới |

---

## Tham chiếu

- Agent registry và lệnh triệu hồi: `AGENTS.md`
- Chuẩn code: `.skills/docs/code-standards.md`
- Bản đồ dự án: `PROJECT_MAP.md`
- Skill lập bản đồ: `project-cartographer-skill.md`
