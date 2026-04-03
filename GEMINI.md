markdown:GEMINI.md
# GEMINI.md - Hệ thống Kỹ năng Agent (Skill Map)

Tệp này tổng hợp toàn bộ các kỹ năng (Skills) hiện có trong hệ thống `.skills`, được thiết kế để Gemini có thể tra cứu nhanh và áp dụng vào quy trình phát triển.

---

## 1. Core & Thinking (Tư duy & Quy trình lõi)

| Kỹ năng | Mô tả |
|:---|:---|
| **`sequential-thinking`** | Áp dụng tư duy tuần tự, phân tích đa bước, có khả năng tự hiệu chỉnh và kiểm chứng giả thuyết cho các bài toán phức tạp. |
| **`problem-solving`** | Kỹ thuật giải quyết các nút thắt kiến trúc, đơn giản hóa sự phức tạp và vượt qua các rào cản sáng tạo. |
| **`planning`** | Lập kế hoạch kỹ thuật chi tiết (Lưu tại `.skills/plans/<YYYYMMDD-HHMM-slug>/plan.md`). |
| **`debugging`** | Khung gỡ lỗi hệ thống 4 giai đoạn: Điều tra nguyên nhân gốc rễ trước khi đưa ra bản sửa lỗi. |
| **`code-review`** | Quy trình kiểm duyệt mã nguồn nghiêm ngặt, thiết lập các cổng kiểm chứng (verification gates) trước khi hoàn tất. |

## 2. Development (Phát triển Phần mềm)

| Kỹ năng | Mô tả |
|:---|:---|
| **`mobile-development`** | Phát triển App hiện đại với Kotlin/Jetpack Compose, Swift/SwiftUI, React Native, Flutter. Tối ưu hiệu năng và UX mobile. |
| **`frontend-dev-guidelines`** | Hướng dẫn phát triển React/TS hiện đại: Suspense, lazy loading, tổ chức thư mục theo features, MUI v7. |
| **`backend-development`** | Xây dựng hệ thống robust với Node.js, Python, Go, Rust. Thiết kế API, Auth (OAuth 2.1), tối ưu DB và Microservices. |
| **`web-frameworks`** | Next.js (App Router, RSC), Turborepo (Monorepo), và quản lý icons. |
| **`databases`** | Làm việc chuyên sâu với PostgreSQL (Relational) và MongoDB (NoSQL), tối ưu hóa truy vấn và migration. |
| **`devops`** | Triển khai hạ tầng Cloud (Cloudflare, Docker, GCP), thiết lập CI/CD và tối ưu hóa chi phí. |

## 3. UI/UX & Design (Thiết kế Giao diện)

| Kỹ năng | Mô tả |
|:---|:---|
| **`ui-ux-pro-max`** | Trí tuệ thiết kế UI/UX với 50+ style, 21 bảng màu, và kiến thức về accessibility/typography chuyên sâu. |
| **`ui-styling`** | Sử dụng shadcn/ui, Tailwind CSS để tạo giao diện đẹp, responsive và hỗ trợ Dark Mode. |
| **`frontend-design`** | Tái tạo giao diện từ screenshot/image, trích xuất guideline thiết kế và thực thi mã nguồn chất lượng cao. |
| **`frontend-design-pro`** | Tạo giao diện "jaw-dropping" kết hợp với ảnh thật (Unsplash) hoặc prompt AI tạo ảnh chất lượng cao. |
| **`threejs`** | Xây dựng trải nghiệm 3D nhập vai trên Web với WebGL/WebGPU và các hiệu ứng post-processing nâng cao. |

## 4. Multimedia & AI (Đa phương tiện & AI)

| Kỹ năng | Mô tả |
|:---|:---|
| **`ai-multimodal`** | Tận dụng Gemini API để phân tích video (đến 6h), audio (9.5h), hình ảnh và tài liệu PDF phức tạp. |
| **`media-processing`** | Xử lý đa phương tiện với FFmpeg và ImageMagick (convert, streaming, filter, xóa nền AI). |

## 5. Specialized Integrations (Tích hợp Chuyên biệt)

| Kỹ năng | Mô tả |
|:---|:---|
| **`payment-integration`** | Tích hợp SePay (VietQR, ngân hàng VN) và Polar (SaaS toàn cầu, subscription). |
| **`better-auth`** | Triển khai xác thực đa phương thức (OAuth, 2FA, Passkeys) một cách an toàn và hiện đại. |
| **`shopify`** | Xây dựng Shopify App, Extension và Theme sử dụng GraphQL/Liquid. |
| **`chrome-devtools`** | Tự động hóa trình duyệt, web scraping và phân tích hiệu năng bằng Puppeteer. |
| **`mcp-management/builder`** | Quản lý và xây dựng các server Model Context Protocol (MCP) để mở rộng công cụ cho AI. |

## 6. Documentation & Tools (Tài liệu & Công cụ)

| Kỹ năng | Mô tả |
|:---|:---|
| **`research`** | Nghiên cứu kỹ thuật có hệ thống, tổng hợp báo cáo và so sánh các giải pháp công nghệ. |
| **`docs-seeker`** | Tìm kiếm thông tin chuyên sâu trong tài liệu kỹ thuật và GitHub repositories. |
| **`repomix`** | Đóng gói mã nguồn thành tệp AI-friendly để phân tích toàn bộ codebase hiệu quả. |
| **`document-skills`** | Xử lý chuyên sâu các định dạng tệp văn phòng: **PDF, DOCX, XLSX, PPTX**. |
| **`skill-creator`** | Hướng dẫn tạo mới hoặc tối ưu hóa các kỹ năng hiện có cho Agent. |

## 7. Quy ước lưu trữ & Triển khai (Storage & Execution)

| Quy tắc | Mô tả |
|:---|:---|
| **Lưu trữ kế hoạch** | Mọi kế hoạch kỹ thuật phải được ghi lại tại `.skills/plans/<YYYYMMDD-HHMM-slug>/plan.md`. |
| **Cấu trúc Plan** | Bao gồm: `Problem`, `Root Cause`, `Solution`, `Architecture`, `Phases` (liệt kê file cụ thể), và `Risk Assessment`. |
| **Tính kế thừa** | Trước khi lập kế hoạch mới, hãy kiểm tra các kế hoạch cũ trong `.skills/plans` để đảm bảo tính nhất quán. |

---
**Ghi chú:** Khi nhận yêu cầu, tôi sẽ tự động kích hoạt kỹ năng phù hợp nhất dựa trên bảng tra cứu này và tuân thủ các quy ước lưu trữ đã đề ra.