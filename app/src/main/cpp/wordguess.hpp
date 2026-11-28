// MangaOverlay - แก้คำที่ OCR อ่านผิด ("เดาคำ") ด้วยพจนานุกรมความถี่ + ตารางตัวอักษรที่ OCR สับสนบ่อย
// ออกแบบให้ "ไม่แน่ใจ = ไม่แก้" (ชื่อตัวละคร/เสียงประกอบ/คำศัพท์ที่ผู้ใช้ตั้งไว้ต้องไม่ถูกเปลี่ยน)
#pragma once
#include <cstddef>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace mo {

class WordGuess {
public:
    /// data = บรรทัดละ "word log10(count)" คืนจำนวนคำที่โหลด (0 = ล้มเหลว) เรียกก่อนใช้งานแบบหลายเธรด
    /// known = (ไม่บังคับ) รายการคำเพิ่มเติม บรรทัดละคำ ใช้เป็น "คำที่รู้จัก" เท่านั้น (กันแก้คำถูกเป็นคำอื่น/ช่วยตัดสินว่าเป็นภาษาอังกฤษ)
    /// แต่ไม่ใช้เป็นตัวเลือกแก้คำ เพราะไม่มีความถี่ (คำหายากเดาผิดง่าย)
    size_t load(const char* data, size_t len, const char* known = nullptr, size_t knownLen = 0);
    bool ready() const { return !lg_.empty(); }

    /// แก้ข้อความทั้งก้อน ไบต์ที่ไม่ใช่ ASCII/ช่องว่าง/วรรคตอนคงเดิมทุกตัว protect = คำที่ห้ามแตะ (ตัวพิมพ์เล็กใหญ่ไม่สำคัญ)
    std::string fix(const std::string& text, const std::vector<std::string>& protect = {}) const;

    /// สัดส่วนคำ (>=3 ตัวอักษร) ที่เป็นคำรู้จัก 0..1; -1 = ข้อมูลน้อยเกินตัดสิน (< 3 คำ)
    float plausibility(const std::string& text, const std::vector<std::string>& protect = {}) const;

    /// คะแนนการสะกดของบรรทัดเดียว 0..1: สัดส่วนคำ (>=2 ตัวอักษร) ที่ "สะกดถูก" = อยู่ในพจนานุกรม/ศัพท์ผู้ใช้ หรือแก้ด้วยกฎ OCR แล้วได้คำในพจนานุกรม
    /// ตัวอักษรซ้ำล้วน (III, lll, ooo) นับเป็นขยะ; ต้องมีคำถูกอย่างน้อยหนึ่งคำยาว >=3 ตัวอักษร ไม่งั้นได้ 0; -1 = ไม่มีคำให้ตัดสิน
    float spellScore(const std::string& line, const std::vector<std::string>& protect = {}) const;

private:
    std::unordered_map<std::string, float> lg_;      // คำ -> log10(ความถี่)
    std::unordered_set<std::string> extra_;          // อุทาน/คำญี่ปุ่นที่พบบ่อยในมังงะ
    const float* find(const std::string& w) const;
    bool known(const std::string& w, const std::unordered_set<std::string>& pr) const;
    bool correct(const std::string& tok, bool allCaps, const std::unordered_set<std::string>& pr, std::string& out) const;
    bool best(const std::string& lw, bool allCaps, std::string& out) const;
};

}  // namespace mo
