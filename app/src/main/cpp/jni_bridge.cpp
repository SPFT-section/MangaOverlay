#include <jni.h>

#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "calc.hpp"
#include "wordguess.hpp"

// ทุกฟังก์ชัน: ไม่โยน exception ข้าม JNI, ตรวจ null/ขนาดก่อนใช้, คืนค่าล้มเหลวให้ Kotlin ใช้ทางสำรองเดิม
namespace {

// ล็อกอาร์เรย์แบบ critical (ไม่คัดลอก) ช่วงสั้น ๆ เท่านั้น ห้ามเรียก JNI อื่นระหว่างถือ
template <typename Arr>
struct Critical {
    JNIEnv* env; Arr arr; void* p; jint mode;
    Critical(JNIEnv* e, Arr a, jint m) : env(e), arr(a), p(a ? e->GetPrimitiveArrayCritical(a, nullptr) : nullptr), mode(m) {}
    ~Critical() { if (p) env->ReleasePrimitiveArrayCritical(arr, p, mode); }
    Critical(const Critical&) = delete;
    Critical& operator=(const Critical&) = delete;
};

bool pixelsOk(JNIEnv* env, jintArray a, jint w, jint h) {
    if (!a || w <= 0 || h <= 0) return false;
    return static_cast<jlong>(env->GetArrayLength(a)) >= static_cast<jlong>(w) * h;
}

std::mutex gGuessMu;
std::shared_ptr<const mo::WordGuess> gGuess;   // อ่านอย่างเดียวหลังโหลด -> ใช้ข้ามเธรดได้โดยไม่ล็อกระหว่างคำนวณ
std::shared_ptr<const mo::WordGuess> guessInstance() { std::lock_guard<std::mutex> l(gGuessMu); return gGuess; }

bool utf(JNIEnv* env, jstring s, std::string& out) {
    if (!s) return false;
    const char* c = env->GetStringUTFChars(s, nullptr);
    if (!c) return false;
    out.assign(c);
    env->ReleaseStringUTFChars(s, c);
    return true;
}

std::vector<std::string> splitLines(const std::string& s) {
    std::vector<std::string> v; size_t b = 0;
    while (b <= s.size() && v.size() < 2000) {
        size_t e = s.find('\n', b); if (e == std::string::npos) e = s.size();
        if (e > b) v.push_back(s.substr(b, e - b));
        b = e + 1;
    }
    return v;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_sunmind_mangaoverlay_Native_nGray(
    JNIEnv* env, jclass, jobject buf, jint limit, jint rowStride, jint pixelStride,
    jint w, jint h, jint q, jbyteArray out) {
    if (!buf || !out) return JNI_FALSE;
    auto* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buf));
    const jlong cap = env->GetDirectBufferCapacity(buf);
    if (!src || cap < 0) return JNI_FALSE;  // ไม่ใช่ direct buffer -> ใช้ Kotlin
    const jlong lim = limit < cap ? limit : cap;
    const jint outLen = env->GetArrayLength(out);
    try {
        Critical<jbyteArray> o(env, out, 0);
        if (!o.p) return JNI_FALSE;
        return mo::grayDownscale(src, lim, rowStride, pixelStride, w, h, q,
                                 static_cast<uint8_t*>(o.p), outLen) ? JNI_TRUE : JNI_FALSE;
    } catch (...) { return JNI_FALSE; }
}

// คืน 1 = true, 0 = false, -1 = ใช้ไม่ได้ (ให้ Kotlin คำนวณเอง)
JNIEXPORT jint JNICALL Java_com_sunmind_mangaoverlay_Native_nDivided(
    JNIEnv* env, jclass, jintArray px, jint w, jint h, jint x0, jint x1, jint y0, jint y1) {
    if (!pixelsOk(env, px, w, h)) return -1;
    try {
        Critical<jintArray> a(env, px, JNI_ABORT);
        if (!a.p) return -1;
        return mo::divided(static_cast<const uint32_t*>(a.p), w, h, x0, x1, y0, y1) ? 1 : 0;
    } catch (...) { return -1; }
}

// out5 = {left, top, right, bottom, argb}; คืน 1 = พบ, 0 = ไม่พบ, -1 = ใช้ไม่ได้
JNIEXPORT jint JNICALL Java_com_sunmind_mangaoverlay_Native_nFit(
    JNIEnv* env, jclass, jintArray px, jint w, jint h, jint l, jint t, jint r, jint b, jintArray out5) {
    if (!pixelsOk(env, px, w, h) || !out5 || env->GetArrayLength(out5) < 5) return -1;
    mo::Rect res{0, 0, 0, 0}; uint32_t bg = 0; bool ok;
    try {
        Critical<jintArray> a(env, px, JNI_ABORT);
        if (!a.p) return -1;
        ok = mo::fit(static_cast<const uint32_t*>(a.p), w, h, mo::Rect{l, t, r, b}, res, bg);
    } catch (...) { return -1; }
    if (!ok) return 0;
    const jint v[5] = {res.l, res.t, res.r, res.b, static_cast<jint>(bg)};
    env->SetIntArrayRegion(out5, 0, 5, v);
    return 1;
}

// คืนสี ARGB; ถ้าใช้ไม่ได้คืน 0 (โปร่งใส ซึ่งไม่เคยเป็นผลจริง เพราะผลจริงทึบเสมอ)
JNIEXPORT jint JNICALL Java_com_sunmind_mangaoverlay_Native_nBgOf(
    JNIEnv* env, jclass, jintArray px, jint w, jint h, jint l, jint t, jint r, jint b) {
    if (!pixelsOk(env, px, w, h)) return 0;
    try {
        Critical<jintArray> a(env, px, JNI_ABORT);
        if (!a.p) return 0;
        return static_cast<jint>(mo::bgOf(static_cast<const uint32_t*>(a.p), w, h, mo::Rect{l, t, r, b}));
    } catch (...) { return 0; }
}

// โหลดพจนานุกรมความถี่ ("word log10count" บรรทัดละคำ) คืนจำนวนคำ, 0 = ล้มเหลว
JNIEXPORT jint JNICALL Java_com_sunmind_mangaoverlay_Native_nGuessLoad(JNIEnv* env, jclass, jbyteArray data, jbyteArray known) {
    if (!data) return 0;
    const jsize n = env->GetArrayLength(data);
    const jsize kn = known ? env->GetArrayLength(known) : 0;
    if (n <= 0 || n > (16 << 20) || kn < 0 || kn > (32 << 20)) return 0;
    try {
        auto g = std::make_shared<mo::WordGuess>();
        size_t cnt;
        {
            // ล็อกทั้งสองอาร์เรย์พร้อมกัน (แบบ critical ซ้อนกันได้) ช่วงสั้น ๆ เท่านั้น
            Critical<jbyteArray> a(env, data, JNI_ABORT);
            Critical<jbyteArray> k(env, kn > 0 ? known : nullptr, JNI_ABORT);
            if (!a.p) return 0;
            cnt = g->load(static_cast<const char*>(a.p), static_cast<size_t>(n),
                          k.p ? static_cast<const char*>(k.p) : nullptr, k.p ? static_cast<size_t>(kn) : 0);
        }
        if (cnt == 0) return 0;
        std::lock_guard<std::mutex> l(gGuessMu);
        gGuess = std::move(g);
        return static_cast<jint>(cnt);
    } catch (...) { return 0; }
}

// คืน null = ไม่ต้องเปลี่ยน/ใช้ไม่ได้ (ผู้เรียกใช้ข้อความเดิม)
JNIEXPORT jstring JNICALL Java_com_sunmind_mangaoverlay_Native_nGuessFix(JNIEnv* env, jclass, jstring text, jstring protect) {
    const auto g = guessInstance();
    if (!g) return nullptr;
    try {
        std::string in, pr;
        if (!utf(env, text, in) || in.empty()) return nullptr;
        utf(env, protect, pr);
        const std::string out = g->fix(in, splitLines(pr));
        if (out == in) return nullptr;
        return env->NewStringUTF(out.c_str());
    } catch (...) { return nullptr; }
}

JNIEXPORT jfloat JNICALL Java_com_sunmind_mangaoverlay_Native_nGuessPlausible(JNIEnv* env, jclass, jstring text, jstring protect) {
    const auto g = guessInstance();
    if (!g) return -1.f;
    try {
        std::string in, pr;
        if (!utf(env, text, in)) return -1.f;
        utf(env, protect, pr);
        return g->plausibility(in, splitLines(pr));
    } catch (...) { return -1.f; }
}

JNIEXPORT jfloat JNICALL Java_com_sunmind_mangaoverlay_Native_nGuessSpell(JNIEnv* env, jclass, jstring text, jstring protect) {
    const auto g = guessInstance();
    if (!g) return -1.f;
    try {
        std::string in, pr;
        if (!utf(env, text, in)) return -1.f;
        utf(env, protect, pr);
        return g->spellScore(in, splitLines(pr));
    } catch (...) { return -1.f; }
}

}  // extern "C"
