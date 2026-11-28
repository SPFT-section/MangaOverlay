#include <jni.h>

#include <exception>

#include "calc.hpp"

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

}  // extern "C"
