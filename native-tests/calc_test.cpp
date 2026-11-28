// รัน: g++ -std=c++17 -O1 -g -fsanitize=address,undefined -I app/src/main/cpp native-tests/calc_test.cpp app/src/main/cpp/calc.cpp -o /tmp/calc_test && /tmp/calc_test [preview.ppm]
#include <cstdio>
#include <cstdlib>
#include <random>
#include <vector>
#include "calc.hpp"

static int fails = 0;
#define CHECK(c) do { if (!(c)) { std::printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #c); ++fails; } } while (0)
static uint32_t rgb(int r, int g, int b) { return 0xFF000000u | (r << 16) | (g << 8) | b; }

struct Page { int w, h; std::vector<uint32_t> px; };
static void box(Page& p, int x0, int y0, int x1, int y1, uint32_t c) {
    for (int y = std::max(0, y0); y < std::min(p.h, y1); ++y) for (int x = std::max(0, x0); x < std::min(p.w, x1); ++x) p.px[(size_t)y * p.w + x] = c;
}

int main(int argc, char** argv) {
    // --- gray: ค่าตรงตามสูตร ---
    {
        const int w = 6, h = 6, rs = 6 * 4 + 8;
        std::vector<uint8_t> buf((size_t)rs * h, 100), out(4);
        CHECK(mo::grayDownscale(buf.data(), buf.size(), rs, 4, w, h, 3, out.data(), 4));
        const int e = (4 * ((100 * 77 + 100 * 150 + 100 * 29) >> 8)) >> 2;
        for (auto v : out) CHECK(v == e);
        CHECK(!mo::grayDownscale(buf.data(), buf.size(), rs, 4, w, h, 3, out.data(), 3));   // out เล็กไป
        CHECK(!mo::grayDownscale(buf.data(), buf.size(), 0, 4, w, h, 3, out.data(), 4));    // stride ผิด
        CHECK(!mo::grayDownscale(nullptr, 10, rs, 4, w, h, 3, out.data(), 4));
        CHECK(!mo::grayDownscale(buf.data(), buf.size(), rs, 4, w, h, 0, out.data(), 4));   // q=0
        std::vector<uint8_t> small(5, 255); uint8_t o1[4];                                  // buffer สั้น: ห้ามอ่านเกิน
        CHECK(mo::grayDownscale(small.data(), small.size(), rs, 4, w, h, 3, o1, 4));
    }
    // --- หน้ากระดาษจำลอง: กรอบวงรี + ข้อความ ---
    Page pg{700, 900, std::vector<uint32_t>(700 * 900, rgb(245, 245, 245))};
    const int cx = 350, cy = 400, rx = 160, ry = 100;
    for (int y = 0; y < pg.h; ++y) for (int x = 0; x < pg.w; ++x) {
        double nx = (x - cx) / (double)rx, ny = (y - cy) / (double)ry, d = nx * nx + ny * ny;
        if (d <= 1.0) pg.px[(size_t)y * pg.w + x] = d > 0.93 ? rgb(15, 15, 15) : rgb(255, 255, 255);
    }
    const mo::Rect text{290, 370, 410, 430};
    box(pg, text.l, text.t, text.r, text.b, rgb(20, 20, 20));
    mo::Rect fr{}; uint32_t bg = 0;
    CHECK(mo::fit(pg.px.data(), pg.w, pg.h, text, fr, bg));
    CHECK(fr.l <= text.l - 4 && fr.t <= text.t - 4 && fr.r >= text.r + 4 && fr.b >= text.b + 4);
    CHECK(bg == rgb(255, 255, 255));
    CHECK(mo::bgOf(pg.px.data(), pg.w, pg.h, text) == rgb(255, 255, 255));
    // กรอบเล็กเกินไป / รั่วทั้งหน้า -> ไม่พบ
    mo::Rect tmp{}; uint32_t tbg;
    CHECK(!mo::fit(pg.px.data(), pg.w, pg.h, mo::Rect{20, 20, 60, 50}, tmp, tbg));
    // divided: เส้นขอบคั่นแนวนอนกว้าง
    Page dv{200, 100, std::vector<uint32_t>(200 * 100, rgb(255, 255, 255))};
    CHECK(!mo::divided(dv.px.data(), 200, 100, 10, 190, 10, 80));
    box(dv, 0, 40, 200, 44, rgb(0, 0, 0));
    CHECK(mo::divided(dv.px.data(), 200, 100, 10, 190, 10, 80));
    // --- fuzz: พิกัดสุ่มสุดโต่ง ต้องไม่ crash/อ่านเกิน (ASan/UBSan จับให้) ---
    std::mt19937 rng(1);
    auto R = [&](int lo, int hi) { return lo + (int)(rng() % (unsigned)(hi - lo + 1)); };
    for (int i = 0; i < 20000; ++i) {
        mo::Rect r{R(-300, 900), R(-300, 1100), R(-300, 900), R(-300, 1100)};
        mo::Rect o{}; uint32_t c;
        mo::fit(pg.px.data(), pg.w, pg.h, r, o, c);
        mo::bgOf(pg.px.data(), pg.w, pg.h, r);
        mo::divided(pg.px.data(), pg.w, pg.h, R(-300, 900), R(-300, 900), R(-300, 1100), R(-300, 1100));
    }
    std::printf(fails ? "FAIL (%d)\n" : "ALL PASS\n", fails);
    // --- preview: PPM พร้อมกรอบ (น้ำเงิน=ข้อความที่ OCR เจอ, แดง=กรอบที่หาได้) ---
    if (argc > 1) {
        auto frame = [&](mo::Rect r, uint32_t c) {
            for (int t = 0; t < 3; ++t) {
                box(pg, r.l - t, r.t - t, r.r + t + 1, r.t - t + 1, c); box(pg, r.l - t, r.b + t, r.r + t + 1, r.b + t + 1, c);
                box(pg, r.l - t, r.t - t, r.l - t + 1, r.b + t + 1, c); box(pg, r.r + t, r.t - t, r.r + t + 1, r.b + t + 1, c);
            }
        };
        frame(fr, rgb(230, 30, 30)); frame(text, rgb(30, 90, 230));
        FILE* f = std::fopen(argv[1], "wb");
        if (f) { std::fprintf(f, "P6\n%d %d\n255\n", pg.w, pg.h); for (uint32_t c : pg.px) { unsigned char b[3] = {(unsigned char)(c >> 16), (unsigned char)(c >> 8), (unsigned char)c}; std::fwrite(b, 1, 3, f); } std::fclose(f); }
    }
    return fails ? 1 : 0;
}
