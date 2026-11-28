// MangaOverlay - ส่วนคำนวณพิกเซล (C++17 ล้วน ไม่พึ่ง JNI/OpenCV) ผลตรงกับ Kotlin เดิมทุกบิต
#pragma once
#include <cstddef>
#include <cstdint>

namespace mo {

struct Rect {
    int l, t, r, b;
    int w() const { return r - l; }
    int h() const { return b - t; }
};

/// ย่อ RGBA_8888 -> เทา 8 บิต โดยเฉลี่ย 2x2 ต่อจุด (stride เดิมจาก ImageReader)
/// limit = จำนวนไบต์ที่อ่านได้ของ buf, out ต้องมีอย่างน้อย (w/q)*(h/q) ไบต์
/// คืน false ถ้าพารามิเตอร์ไม่ถูกต้อง (ไม่เขียน/ไม่อ่านนอกขอบเขตเด็ดขาด)
bool grayDownscale(const uint8_t* buf, int64_t limit, int rowStride, int pixelStride,
                   int w, int h, int q, uint8_t* out, int64_t outSize);

/// มีเส้นขอบกรอบคำพูดคั่นระหว่างสองบล็อกไหม (px = ARGB w*h)
bool divided(const uint32_t* px, int w, int h, int x0, int x1, int y0, int y1);

/// หากรอบคำพูดรอบข้อความ t; คืน false = ไม่พบ/ไม่ใช่กรอบคำพูด
bool fit(const uint32_t* px, int w, int h, Rect t, Rect& outRect, uint32_t& outBg);

/// สีพื้นสำรอง: มัธยฐานจาก 8 จุดรอบข้อความ (ARGB ทึบ)
uint32_t bgOf(const uint32_t* px, int w, int h, Rect r);

}  // namespace mo
