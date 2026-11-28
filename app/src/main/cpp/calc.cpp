#include "calc.hpp"

#include <algorithm>
#include <cstdlib>
#include <vector>

namespace mo {
namespace {

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline int lumOf(uint32_t c) {
    return static_cast<int>((77u * ((c >> 16) & 255u) + 150u * ((c >> 8) & 255u) + 29u * (c & 255u)) >> 8);
}
struct Img {
    const uint32_t* px; int w, h;
    uint32_t p(int x, int y) const { return px[static_cast<size_t>(clampi(y, 0, h - 1)) * w + clampi(x, 0, w - 1)]; }
    int lum(int x, int y) const { return lumOf(p(x, y)); }
};

// ---- android.graphics.Rect ที่จำเป็น (พฤติกรรมเดียวกัน) ----
inline bool isEmpty(const Rect& a) { return a.l >= a.r || a.t >= a.b; }
inline void unionWith(Rect& a, const Rect& o) {
    if (isEmpty(o)) return;
    if (isEmpty(a)) { a = o; return; }
    a.l = std::min(a.l, o.l); a.t = std::min(a.t, o.t);
    a.r = std::max(a.r, o.r); a.b = std::max(a.b, o.b);
}
inline bool intersectWith(Rect& a, const Rect& o) {
    if (a.l < o.r && o.l < a.r && a.t < o.b && o.t < a.b) {
        a.l = std::max(a.l, o.l); a.t = std::max(a.t, o.t);
        a.r = std::min(a.r, o.r); a.b = std::min(a.b, o.b);
        return true;
    }
    return false;
}

constexpr int ST = 2;    // ลดความละเอียดตอนหากรอบ
constexpr int TOL = 28;  // ความต่างความสว่างที่ยังนับเป็นพื้นเดียวกัน

}  // namespace

bool grayDownscale(const uint8_t* buf, int64_t limit, int rs, int ps, int w, int h, int q,
                   uint8_t* out, int64_t outSize) {
    if (!buf || !out || q < 1 || w <= 0 || h <= 0 || rs <= 0 || ps <= 0 || limit <= 0) return false;
    const int sw = w / q, sh = h / q;
    if (sw <= 0 || sh <= 0 || outSize < static_cast<int64_t>(sw) * sh) return false;
    const int64_t last = limit - 1;
    const int64_t rowOff = q > 1 ? rs : 0;
    // ดัชนีไบต์สูงสุดที่จะอ่าน (ใช้ตัดสินว่าใช้ทางเร็วไร้การตรวจต่อจุดได้ไหม)
    const int64_t maxI = static_cast<int64_t>(sh - 1) * q * rs + rowOff + (static_cast<int64_t>(sw - 1) * q + 1) * ps + 2;
    const bool safe = maxI <= last;
    for (int y = 0; y < sh; ++y) {
        const int64_t r0 = static_cast<int64_t>(y) * q * rs;
        const int64_t r1 = r0 + rowOff;
        uint8_t* o = out + static_cast<size_t>(y) * sw;
        for (int x = 0; x < sw; ++x) {
            const int64_t xo = static_cast<int64_t>(x) * q * ps;
            const int64_t idx[4] = {r0 + xo, r0 + xo + ps, r1 + xo, r1 + xo + ps};
            int sum = 0;
            for (int k = 0; k < 4; ++k) {
                const int64_t i = idx[k];
                if (!safe && (i < 0 || i + 2 > last)) continue;  // เหมือนเดิม: จุดนอกขอบเขตนับเป็น 0
                sum += (buf[i] * 77 + buf[i + 1] * 150 + buf[i + 2] * 29) >> 8;
            }
            o[x] = static_cast<uint8_t>(sum >> 2);
        }
    }
    return true;
}

bool divided(const uint32_t* px, int w, int h, int x0, int x1, int y0, int y1) {
    if (!px || w <= 0 || h <= 0) return false;
    if (y1 - y0 < 3) return false;
    const Img img{px, w, h};
    int hits = 0;
    for (int k = 1; k <= 3; ++k) {
        const int x = x0 + (x1 - x0) * k / 4;
        const int ref = img.lum(x, y0 + 1);
        int run = 0; bool hit = false;
        for (int y = y0 + 1; y < y1; ++y) {
            if (std::abs(img.lum(x, y) - ref) > 70) { if (++run >= 2) { hit = true; break; } }
            else run = 0;
        }
        if (hit) ++hits;
    }
    return hits >= 2;
}

bool fit(const uint32_t* px, int w, int h, Rect t, Rect& outRect, uint32_t& outBg) {
    if (!px || w <= 0 || h <= 0) return false;
    const int m = clampi(static_cast<int>(std::max(t.w(), t.h()) * 0.8f), 48, 360);
    const int cl = std::max(0, t.l - m), ct = std::max(0, t.t - m);
    const int cr = std::min(w, t.r + m), cb = std::min(h, t.b + m);
    const int gw = (cr - cl) / ST, gh = (cb - ct) / ST;
    if (gw < 12 || gh < 12) return false;
    const size_t n = static_cast<size_t>(gw) * gh;

    thread_local std::vector<uint8_t> lum, seen;
    thread_local std::vector<int> q;
    lum.resize(n); seen.assign(n, 0); q.resize(n);
    for (int y = 0; y < gh; ++y) {
        const uint32_t* row = px + static_cast<size_t>(ct + y * ST) * w + cl;
        uint8_t* lo = lum.data() + static_cast<size_t>(y) * gw;
        for (int x = 0; x < gw; ++x) lo[x] = static_cast<uint8_t>(lumOf(row[x * ST]));
    }

    const int tl = (t.l - cl) / ST, tt = (t.t - ct) / ST, tr = (t.r - cl) / ST, tb = (t.b - ct) / ST;
    const int k = 3, cx = (tl + tr) / 2, cy = (tt + tb) / 2;
    const int seeds[8][2] = {{cx, tt - k}, {cx, tb + k}, {tl - k, cy}, {tr + k, cy},
                             {tl - k, tt - k}, {tr + k, tt - k}, {tl - k, tb + k}, {tr + k, tb + k}};
    bool have = false;
    int best[5] = {0, 0, 0, 0, 0};
    uint32_t bestColor = 0xFFFFFFFFu;
    const uint8_t* L = lum.data();
    uint8_t* S = seen.data();
    int* Q = q.data();
    for (const auto& sd : seeds) {
        const int sx = sd[0], sy = sd[1];
        if (sx < 0 || sy < 0 || sx >= gw || sy >= gh || S[static_cast<size_t>(sy) * gw + sx]) continue;
        const int ref = L[static_cast<size_t>(sy) * gw + sx];
        size_t qh = 0, qt = 0;
        Q[qt++] = sy * gw + sx; S[static_cast<size_t>(sy) * gw + sx] = 1;
        int x0 = sx, x1 = sx, y0 = sy, y1 = sy, area = 0;
        while (qh < qt) {
            const int c = Q[qh++]; const int x = c % gw, y = c / gw;
            ++area;
            if (x < x0) x0 = x;
            if (x > x1) x1 = x;
            if (y < y0) y0 = y;
            if (y > y1) y1 = y;
            if (x > 0      && !S[c - 1]  && std::abs(L[c - 1]  - ref) <= TOL) { S[c - 1]  = 1; Q[qt++] = c - 1; }
            if (x < gw - 1 && !S[c + 1]  && std::abs(L[c + 1]  - ref) <= TOL) { S[c + 1]  = 1; Q[qt++] = c + 1; }
            if (y > 0      && !S[c - gw] && std::abs(L[c - gw] - ref) <= TOL) { S[c - gw] = 1; Q[qt++] = c - gw; }
            if (y < gh - 1 && !S[c + gw] && std::abs(L[c + gw] - ref) <= TOL) { S[c + gw] = 1; Q[qt++] = c + gw; }
        }
        if (!have || area > best[4]) {
            have = true;
            best[0] = x0; best[1] = y0; best[2] = x1; best[3] = y1; best[4] = area;
            bestColor = px[static_cast<size_t>(ct + sy * ST) * w + cl + sx * ST];
        }
    }
    if (!have) return false;
    const int sides = (best[0] <= 0) + (best[1] <= 0) + (best[2] >= gw - 1) + (best[3] >= gh - 1);
    if (sides >= 3 || best[4] > 0.92f * gw * gh) return false;  // รั่วออกนอกกรอบ
    const int bw = best[2] - best[0] + 1, bh = best[3] - best[1] + 1;
    const Rect bounds{cl + best[0] * ST, ct + best[1] * ST, cl + (best[2] + 1) * ST, ct + (best[3] + 1) * ST};
    if (bounds.w() < t.w() || bounds.h() < t.h()) return false;
    const float k2 = (static_cast<float>(best[4]) / static_cast<float>(bw * bh) > 0.86f) ? 0.94f : 0.78f;
    const int hw = static_cast<int>(bounds.w() / 2.0f * k2), hh = static_cast<int>(bounds.h() / 2.0f * k2);
    const int bcx = (bounds.l + bounds.r) >> 1, bcy = (bounds.t + bounds.b) >> 1;  // Rect.centerX/Y
    Rect r{bcx - hw, bcy - hh, bcx + hw, bcy + hh};
    unionWith(r, Rect{t.l - 4, t.t - 4, t.r + 4, t.b + 4});
    if (!intersectWith(r, bounds)) return false;
    outRect = r;
    outBg = 0xFF000000u | (bestColor & 0x00FFFFFFu);
    return true;
}

uint32_t bgOf(const uint32_t* px, int w, int h, Rect r) {
    if (!px || w <= 0 || h <= 0) return 0xFFFFFFFFu;
    const Img img{px, w, h};
    const int xs[3] = {r.l - 5, (r.l + r.r) / 2, r.r + 5};
    const int ys[3] = {r.t - 5, (r.t + r.b) / 2, r.b + 5};
    uint32_t l[8]; int n = 0;
    for (int xi = 0; xi < 3; ++xi)
        for (int yi = 0; yi < 3; ++yi) {
            if (xi == 1 && yi == 1) continue;
            l[n++] = img.p(xs[xi], ys[yi]);
        }
    std::stable_sort(l, l + n, [](uint32_t a, uint32_t b) { return lumOf(a) < lumOf(b); });
    return 0xFF000000u | (l[n / 2] & 0x00FFFFFFu);
}

}  // namespace mo
