# 🧠 LONG-TERM MEMORY JOURNAL
## RemotePlay · Knowledge Base & Session Continuity

> Tệp này lưu trữ các quyết định quan trọng, bài học kinh nghiệm và trạng thái hệ thống qua các phiên làm việc.
> **BẮT BUỘC**: Ghi lại nhật ký sau mỗi task hoàn thành theo Section 11 của `rules.md`.

---

## 📅 NHẬT KÝ CHI TIẾT

### [2025-11-21] - Khởi tạo Hệ thống Trí nhớ & Đồng bộ Skills
**Người thực hiện**: Gemini (Autonomous Mode)
**Nội dung**:
- Thiết lập cấu trúc `.skills-INDEX.md` mới dựa trên codebase thực tế.
- Khởi tạo hệ thống Long-term Memory trong `.gemini/memory/`.
- Cập nhật `rules.md` với quy trình ghi chép bắt buộc.
**Bài học**: 
- Codebase sử dụng khung ClaudeKit Engineer với hệ thống Agent và Skills rất phức tạp.
- Cần đối chiếu kỹ giữa tài liệu (`GEMINI.md`) và file thực tế trong `.skills/`.
**Trạng thái**: Hoàn thành đồng bộ hóa chỉ mục kỹ năng.

---

### [2025-11-21] - Hoàn thành Phase 1: Hệ thống VR Head-Tracking & DataChannel
**Người thực hiện**: Gemini (Autonomous Mode)
**Nội dung**:
- Triển khai `VrSensorProvider` (:core:sensor) sử dụng `TYPE_ROTATION_VECTOR` để lấy dữ liệu 3DOF (Quaternion).
- Mở rộng `WebRtcManager` với DataChannel `vr_sensor` chuyên biệt cho dữ liệu cảm biến (UDP mode).
- Thiết kế Binary Protocol cho sensor: `[0x0A (1 byte)][x, y, z, w (16 bytes)]`.
- Tích hợp `DisplayMode` (FLAT/VR/AR) vào `StreamingViewModel` và cơ chế tự động start/stop tracking.
**Quyết định kiến trúc**: 
- Sử dụng **Unified Render Pipeline** (một Renderer duy nhất cho mọi mode) thay vì nhiều View khác nhau để đảm bảo chuyển đổi mượt mà.
- Chọn **Custom OpenGL Shader** thay vì Google Cardboard SDK để giảm phụ thuộc và tối ưu hiệu năng.
**Bài học**: 
- Dữ liệu cảm biến cần gửi ở tần suất cao (SENSOR_DELAY_GAME) nên việc dùng DataChannel không tin cậy (Unordered/No Retransmit) là tối quan trọng để tránh "lag tích tụ".
**Trạng thái**: Sẵn sàng cho Phase 2 (OpenGL Renderer & Shaders).

---

### [2025-11-21] - Hoàn thành Phase 2: Unified OpenGL Render Pipeline
**Người thực hiện**: Gemini (Autonomous Mode)
**Nội dung**:
- Triển khai `UnifiedStreamingRenderer` kế thừa `GLSurfaceView`, sử dụng OpenGL ES 2.0.
- Viết Vertex & Fragment Shader hỗ trợ đa chế độ:
    - `FLAT`: Render texture OES bình thường.
    - `VR (SBS)`: Chia đôi màn hình Side-by-Side và áp dụng **Barrel Distortion** thuật toán (k1, k2 coefficients).
- Tích hợp `OES Texture` trực tiếp từ WebRTC Video Decoder vào môi trường OpenGL (zero-copy).
- Cập nhật `StreamingScreen` sử dụng `AndroidView` wrapper cho Renderer mới và thêm nút chuyển đổi mode trên UI.
**Quyết định kiến trúc**: 
- Thực hiện Distortion ngay trên Fragment Shader của Video Stream để tối ưu số lần vẽ (pass), thay vì vẽ ra texture trung gian rồi mới bóp méo.
- Ẩn `CursorOverlay` (Compose) khi ở mode VR để chuẩn bị cho việc vẽ Cursor bằng chính OpenGL ở Phase sau.
**Bài học**: 
- `GLSurfaceView` cần được quản lý vòng đời cẩn thận qua `onRelease` của Compose để tránh leak `SurfaceTexture`.
- Việc dùng `RENDERMODE_WHEN_DIRTY` kết hợp `onFrameAvailable` giúp tiết kiệm pin đáng kể so với vẽ liên tục.
**Trạng thái**: Sẵn sàng cho Phase 3 (VR UX & Polish).

---

### [2025-11-21] - Hoàn thành Phase 3: VR UX, Stereo Cursor & Recenter
**Người thực hiện**: Gemini (Autonomous Mode)
**Nội dung**:
- Nâng cấp `VrSensorProvider` hỗ trợ cơ chế **Recenter** thông qua việc nhân Quaternion nghịch đảo để reset góc nhìn forward.
- Cải tiến `UnifiedStreamingRenderer`:
    - Tích hợp thêm Texture riêng cho Cursor.
    - Fragment Shader hiện đã vẽ Cursor 2 lần (cho 2 mắt) trong chế độ VR SBS.
    - Cursor được áp dụng cùng thuật toán **Barrel Distortion** với Video để khớp hoàn toàn trong không gian thấu kính.
- Cập nhật `StreamingScreen`:
    - Thêm nút **Recenter** (icon Center Focus) vào menu Settings khi ở mode VR.
    - Đồng bộ hóa dữ liệu Cursor từ `webRtcManager` trực tiếp vào bộ Renderer OpenGL.
**Quyết định kiến trúc**: 
- Thực hiện vẽ Cursor ngay trong Fragment Shader của Video pass để đảm bảo độ trễ thấp nhất và khớp hoàn hảo với lưới distortion, thay vì vẽ overlay riêng biệt.
- Sử dụng phím ảo trên UI cho Recenter, chuẩn bị cho việc mapping phím cứng (Volume Up) ở các bản cập nhật sau.
**Bài học**: 
- Việc render overlay (Cursor) trong VR SBS yêu cầu tọa độ cursor phải được ánh xạ lại vào không gian của từng mắt trước khi áp dụng distortion.
**Trạng thái**: Hoàn thành tính năng Mobile VR (3DOF) cơ bản. Sẵn sàng cho tối ưu hiệu năng sâu (Phase 4).

---
