// รัน (argv[2] ไม่บังคับ = app/src/main/assets/dict_en_th.tsv): g++ -std=c++17 -O1 -g -fsanitize=address,undefined -I app/src/main/cpp native-tests/guess_test.cpp app/src/main/cpp/wordguess.cpp -o /tmp/guess_test && /tmp/guess_test app/src/main/assets/freq_en.txt
#include <cctype>
#include <chrono>
#include <cstdio>
#include <fstream>
#include <random>
#include <sstream>
#include <string>
#include <vector>
#include "wordguess.hpp"

static int fails = 0;
static mo::WordGuess G;
static void eq(const std::string& in, const std::string& want, const std::vector<std::string>& pr = {}) {
    const std::string got = G.fix(in, pr);
    if (got != want) { std::printf("FAIL  \"%s\" -> \"%s\" (want \"%s\")\n", in.c_str(), got.c_str(), want.c_str()); ++fails; }
}
static std::string up(std::string s) { for (auto& c : s) c = (char)std::toupper((unsigned char)c); return s; }
static std::vector<std::string> words(const std::string& s) { std::istringstream is(s); std::vector<std::string> v; std::string w; while (is >> w) v.push_back(w); return v; }

static const char* kClean[] = {
    "I will never forgive you for what you did to her.", "Wait, where are you going? It is dangerous out there!", "We have to hurry before the others find us.",
    "That is not what I meant and you know it.", "Everyone is waiting for you at the school gate.", "Please do not tell anyone about this secret.",
    "He said that the town would be destroyed tomorrow.", "Why would they hide the truth from us for so long?", "I thought you were stronger than that.",
    "The teacher wants to see all of us after class.", "Look at the sky, it is so beautiful tonight.", "You promised that we would stay together forever.",
    "Nobody believes me when I say the monster is real.", "What happened to the old library behind the mountain?", "Let me help you carry those heavy books.",
    "She was standing alone near the window, crying quietly.", "This is the last chance we will ever get, so listen carefully.",
    "How did you manage to survive the explosion without any injury?", "Do not worry, I will protect everyone no matter what happens.",
    "The captain ordered his men to attack the castle immediately.", "My brother used to tell me stories about the dragon king.",
    "Could you please explain why the door was locked all night?", "Thank you for saving my life back there, I owe you everything.",
    "Training starts early tomorrow morning, so get some sleep.", "There is something strange about this village and its people.",
    "Together we can defeat the enemy and bring peace to the world.", "Whatever you decide, I will always be on your side.",
    "Finally the battle is over and the whole kingdom is safe.", "Remember the day we first met under the cherry blossom tree.",
    "Are you really going to leave without saying goodbye to them?", "Somebody must have told the guards about our secret plan.",
    "The princess escaped through the forest during the storm.", "Hurry up, the train leaves in five minutes and we cannot miss it.",
    "I never imagined that my own father would betray the family.", "Perhaps the answer is hidden inside the ancient temple.",
    "Stop pretending to be strong, nobody is watching you now.", "The festival begins tonight and everyone will wear a mask.",
    "Our power comes from the people we want to protect.", "Maybe this is the reason why she disappeared without a word.",
    "Open the gate, we bring terrible news from the northern border.",
};
static const char* kNames[] = {"NARUTO", "SAKURA", "KENSHIN", "AKIRA", "HIKARI", "YUKI", "ASUKA", "KIRITO", "SENPAI", "TOKYO", "SHINJI", "MIKASA", "EREN", "LEVI",
    "ZORO", "LUFFY", "GOKU", "VEGETA", "ITACHI", "SASUKE", "TANJIRO", "NEZUKO", "ONII-CHAN", "SENSEI", "KOUHAI", "HINATA", "MADARA", "RYUUJI", "KAORI", "SUZUME",
    "Naruto", "Sakura", "Kenshin", "Tanjiro", "Hinata", "Kirigaya", "Yagami", "Light", "Ryuk", "BOOM", "KRAK", "THUD", "BAKA", "KAWAII", "SUGOI", "HMPH", "TCH", "NGH"};

int main(int argc, char** argv) {
    if (argc < 2) { std::puts("usage: guess_test freq_en.txt"); return 2; }
    std::ifstream f(argv[1], std::ios::binary); std::stringstream ss; ss << f.rdbuf(); const std::string d = ss.str();
    // argv[2] (ไม่บังคับ) = dict_en_th.tsv ใช้เป็นคำที่รู้จักเพิ่ม (กรองเหมือน Guess.kt: ตัวอักษรล้วน 4-24 ตัว)
    std::string known;
    if (argc > 2) {
        std::ifstream t(argv[2]); std::string l;
        while (std::getline(t, l)) {
            const size_t i = l.find('\t'); if (i == std::string::npos || i < 4 || i > 24) continue;
            bool ok = true; for (size_t k = 0; k < i; ++k) if (!std::isalpha((unsigned char)l[k])) { ok = false; break; }
            if (ok) { known += l.substr(0, i); known += '\n'; }
        }
        std::printf("extra known words: %zu bytes\n", known.size());
    }
    const size_t n = G.load(d.data(), d.size(), known.data(), known.size());
    std::printf("loaded %zu words\n", n);
    if (n < 50000) { std::puts("FAIL load"); return 1; }
    // ไม่โหลด/ไฟล์เสีย
    { mo::WordGuess e; if (e.load("abc\n", 4) != 0 || e.ready() || e.fix("HELLO") != "HELLO") { std::puts("FAIL empty"); ++fails; } }

    // --- กรณีที่ต้องแก้ ---
    eq("WlLL YOU STOP?", "WILL YOU STOP?");
    eq("lT'S TOO LATE", "IT'S TOO LATE");
    eq("l'm sorry", "I'm sorry");
    eq("L'M SORRY", "I'M SORRY");
    eq("l", "I");
    eq("lt is true", "It is true");
    eq("He11o there", "Hello there");
    eq("HE11O THERE", "HELLO THERE");
    eq("I will rnake it", "I will make it");
    eq("tbe end", "the end");
    eq("5ORRY ABOUT THAT", "SORRY ABOUT THAT");
    eq("TH E END", "THE END");
    eq("WH AT IS THIS", "WHAT IS THIS");
    eq("DONT GO", "DON'T GO");
    eq("I cant do it", "I can't do it");
    eq("im fine", "I'm fine");
    eq("Whatever you decide, I vvill help", "Whatever you decide, I will help");
    eq("STAY CLOSE TO ME", "STAY CLOSE TO ME");
    eq("FRlEND", "FRIEND");
    eq("0K", "0K");                      // ไม่กำกวมพอ: ไม่แตะ
    eq("0pen the door", "Open the door");
    eq("lt's fine", "It's fine");
    eq("FinaIly done", "Finally done");
    // --- ต้องไม่แตะ ---
    eq("NARUTO AND SAKURA", "NARUTO AND SAKURA");
    eq("Hello, Naruto-kun!", "Hello, Naruto-kun!");
    eq("3 PEOPLE ON THE 2ND FLOOR AT 10AM", "3 PEOPLE ON THE 2ND FLOOR AT 10AM");
    eq("caf\xc3\xa9 au lait", "caf\xc3\xa9 au lait");
    eq("\xe3\x81\x93\xe3\x82\x93 HELLO \xf0\x9f\x98\x80 WORLD", "\xe3\x81\x93\xe3\x82\x93 HELLO \xf0\x9f\x98\x80 WORLD");
    eq("", ""); eq("   ", "   "); eq("!!!???", "!!!???");
    eq("KIRIGAYA SAID SO", "KIRIGAYA SAID SO", {"kirigaya"});
    eq("A LOT OF PEOPLE", "A LOT OF PEOPLE"); eq("I AM HERE", "I AM HERE"); eq("IT'S MY TURN", "IT'S MY TURN");
    eq("GO ON", "GO ON"); eq("BE FORE", "BE FORE");
    // --- spellScore: บรรทัดที่ถูกบังบางส่วนต้อง "กู้" ได้ถ้าสะกดตรงพจนานุกรม และขยะต้องไม่ถูกกู้ ---
    auto sp = [&](const char* line, const std::vector<std::string>& pr = {}) { return G.spellScore(line, pr); };
    auto expectHi = [&](const char* line) { const float v = sp(line); if (!(v >= 0.75f)) { std::printf("FAIL spell-hi \"%s\" = %.2f\n", line, v); ++fails; } };
    auto expectLo = [&](const char* line, const std::vector<std::string>& pr = {}) { const float v = sp(line, pr); if (v >= 0.75f) { std::printf("FAIL spell-lo \"%s\" = %.2f\n", line, v); ++fails; } };
    expectHi("I will never forgive you");        // ข้อความปกติ
    expectHi("WE HAVE TO HURRY");                // ตัวพิมพ์ใหญ่ล้วน
    expectHi("He11o there");                     // OCR สับสน 1/l: แก้ด้วยกฎแล้วเป็นคำจริง
    expectHi("rnake the tbought");               // rn/m, b/h ในคำยาว
    expectHi("DONT GO");                         // อัฒภาคหาย
    expectLo("III");                             // เส้นที่อ่านเป็นตัวอักษร
    expectLo("lllll");
    expectLo("ooo");
    expectLo("xqzt wvkp");                       // ไม่ใช่คำ
    expectLo("a1 b2 c3");                        // ไม่มีคำยาว >= 3
    expectLo("Zxqv Kplm");                       // ชื่อ/ขยะ ไม่อยู่ในพจนานุกรม
    { const float v = sp("KIRIGAYA SAID", {"kirigaya"}); if (!(v >= 0.75f)) { std::printf("FAIL spell-protect %.2f\n", v); ++fails; } }
    if (sp("") != -1.f || sp("!!! ???") != -1.f || sp("\xe3\x81\x93\xe3\x82\x93") != -1.f) { std::puts("FAIL spell-none"); ++fails; }
    { std::string big(30000, 'x'); if (G.spellScore(big) != -1.f) { std::puts("FAIL spell-big"); ++fails; } }
    { std::string bin; for (int i = 0; i < 4000; ++i) bin.push_back((char)(i * 37 % 256)); (void)G.spellScore(bin); }
    // บรรทัดสะอาดทั้งหมดต้องได้คะแนนสูง (กันกู้ผิดพลาดกับข้อความจริง), ข้อความที่สร้างจากพยัญชนะสุ่มต้องต่ำ
    { int lowClean = 0; for (const char* c : kClean) if (!(sp(c) >= 0.75f)) { ++lowClean; std::printf("  LOW SPELL: %s = %.2f\n", c, sp(c)); } if (lowClean) ++fails; }
    { std::mt19937 r2(7); int hiJunk = 0; for (int i = 0; i < 500; ++i) { std::string j; const int nw = 2 + r2() % 3; for (int w = 0; w < nw; ++w) { if (w) j += ' '; const int L = 3 + r2() % 5; for (int k = 0; k < L; ++k) j.push_back("bcdfgjklmnpqvwxz"[r2() % 16]); } if (sp(j.c_str()) >= 0.75f) { ++hiJunk; std::printf("  JUNK PASSED: %s\n", j.c_str()); } } std::printf("random junk rescued: %d/500\n", hiJunk); if (hiJunk > 0) ++fails; }
    // --- เติมตัวอักษรที่ถูกบัง (กรณีจริง: SLIMOPOLIS ถูกกรงเล็บบัง อ่านได้ "SLIMEP LIS") ---
    { const std::vector<std::string> sl = {"slimopolis"};
      eq("(THE DOCK DISTRICT OF SLIMEP LIS IS LIBERATED)", "(THE DOCK DISTRICT OF SLIMOPOLIS IS LIBERATED)", sl);   // E<->O สับสน + ขาด O
      eq("THE DOCK DISTRICT OF SLIMOP LIS IS LIBERATED", "THE DOCK DISTRICT OF SLIMOPOLIS IS LIBERATED", sl);       // ขาด O ตรงรอยต่อ
      eq("SLIMOP?LIS", "SLIMOPOLIS", sl);                 // มีสัญลักษณ์แทนที่รอยบัง
      eq("SLIMEPLIS", "SLIMOPOLIS", sl);                  // คำเดียว
      eq("SLIMOPOLI", "SLIMOPOLIS", sl);                  // ท้ายคำถูกบัง
      eq("slimop lis", "slimopolis", sl);
      eq("Slimep Lis", "Slimopolis", sl);
      eq("SLIMOPOLIS", "SLIMOPOLIS", sl);                 // อ่านครบอยู่แล้ว: ไม่แตะ
      eq("SLIMEP LIS", "SLIMEP LIS");                     // ไม่มีศัพท์: ไม่เดา
      eq("WE WILL BE SLIM OR NOT", "WE WILL BE SLIM OR NOT", sl);    // คำจริงที่ติดกันไม่ถูกรวม
      eq("THE POLIS IS HERE", "THE POLIS IS HERE", sl);
      eq("SLIMEPOLIS", "SLIMOPOLIS", {"slimopolis", "slimxxxxxx"});
      eq("SLIMEPOLIS", "SLIMEPOLIS", {"slimopolis", "slimcpolis"});  // เสมอกัน (e<->o, e<->c) = ไม่เดา
      if (!(sp("SLIMEP LIS", sl) >= 0.75f) || sp("SLIMEP LIS") >= 0.75f) { std::puts("FAIL spell-gap"); ++fails; }
    }
    // ไม่มีศัพท์ผู้ใช้: ห้ามเติมจากพจนานุกรมจนชื่อเพี้ยน (LUFFY->FLUFFY) — ครอบด้วย kNames ด้านล่างอยู่แล้ว
    // ข้อความยาวผิดปกติ/ไบต์แปลก ไม่ crash และไม่เปลี่ยน
    { std::string big(30000, 'x'); if (G.fix(big) != big) { std::puts("FAIL big"); ++fails; } }
    { std::string bin; for (int i = 0; i < 4000; ++i) bin.push_back((char)(i * 37 % 256)); (void)G.fix(bin); (void)G.plausibility(bin); }

    // --- ความถูกต้องต่อข้อความสะอาด: ต้องไม่เปลี่ยนเลย ---
    int cleanTotal = 0, cleanChanged = 0;
    for (const char* s : kClean) for (int caps = 0; caps < 2; ++caps) {
        const std::string in = caps ? up(s) : std::string(s); ++cleanTotal;
        const std::string out = G.fix(in);
        if (out != in) { ++cleanChanged; std::printf("  FALSE-POSITIVE: \"%s\" -> \"%s\"\n", in.c_str(), out.c_str()); }
    }
    int nameChanged = 0;
    for (const char* s : kNames) { const std::string out = G.fix(s); if (out != s) { ++nameChanged; std::printf("  NAME CHANGED: %s -> %s\n", s, out.c_str()); } }
    std::printf("clean sentences changed: %d/%d, names changed: %d/%zu\n", cleanChanged, cleanTotal, nameChanged, sizeof(kNames) / sizeof(kNames[0]));
    if (cleanChanged > 0 || nameChanged > 0) ++fails;

    // --- จำลอง OCR ผิด: เทียบก่อน/หลังแก้ ---
    std::mt19937 rng(42);
    struct Sub { const char* a; const char* b; };
    const Sub subs[] = {{"i", "l"}, {"I", "l"}, {"o", "0"}, {"O", "0"}, {"m", "rn"}, {"w", "vv"}, {"h", "b"}, {"d", "cl"}, {"s", "5"}, {"i", "1"}, {"l", "I"}, {"a", "o"}, {"e", "c"}};
    int bad = 0, fixed = 0, left = 0, wrong = 0;
    for (const char* s : kClean) for (int caps = 0; caps < 2; ++caps) for (int rep = 0; rep < 6; ++rep) {
        const std::string base = caps ? up(s) : std::string(s);
        auto ws = words(base); std::string corrupted;
        std::vector<bool> isBad(ws.size(), false);
        for (size_t i = 0; i < ws.size(); ++i) {
            std::string w = ws[i];
            if (w.size() >= 4 && rng() % 3 == 0) {
                for (int tries = 0; tries < 8; ++tries) {
                    const Sub& sb = subs[rng() % (sizeof(subs) / sizeof(subs[0]))];
                    std::string a = caps ? up(sb.a) : sb.a; std::string b = sb.b;
                    if (caps && (b == "rn" || b == "vv" || b == "cl")) b = up(b);
                    const size_t pos = w.find(a);
                    if (pos != std::string::npos) { w.replace(pos, a.size(), b); isBad[i] = true; break; }
                }
            }
            corrupted += (i ? " " : "") + w;
        }
        const auto cw = words(corrupted); const auto fw = words(G.fix(corrupted));
        if (fw.size() != ws.size() || cw.size() != ws.size()) { ++fails; std::printf("FAIL token count: %s\n", corrupted.c_str()); continue; }
        for (size_t i = 0; i < ws.size(); ++i) if (isBad[i]) {
            ++bad;
            if (fw[i] == ws[i]) ++fixed; else if (fw[i] == cw[i]) ++left; else { ++wrong; if (wrong <= 8) std::printf("  WRONG: %s -> %s (want %s)\n", cw[i].c_str(), fw[i].c_str(), ws[i].c_str()); }
        }
    }
    std::printf("OCR errors injected %d: fixed %d (%.1f%%), left unchanged %d (%.1f%%), wrongly changed %d (%.1f%%)\n",
                bad, fixed, 100.0 * fixed / bad, left, 100.0 * left / bad, wrong, 100.0 * wrong / bad);
    if (100.0 * fixed / bad < 55.0 || 100.0 * wrong / bad > 3.0) ++fails;

    // --- ความเร็ว ---
    std::string blk = "WlLL YOU STOP TALK1NG ABOUT THAT SECRET PLAN NOW THE GUARDS ARE COM1NG";
    auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < 2000; ++i) (void)G.fix(blk);
    const double us = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - t0).count() / 2000;
    std::printf("fix() 1 ข้อความ ~%.0f us\n", us);
    std::puts(fails ? "FAIL" : "ALL PASS");
    return fails ? 1 : 0;
}
