#include "wordguess.hpp"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>

namespace mo {
namespace {

inline bool isLetter(unsigned char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }
inline bool isDigit(unsigned char c) { return c >= '0' && c <= '9'; }
inline bool isAl(unsigned char c) { return isLetter(c) || isDigit(c) || c == '|'; }
inline bool isUp(unsigned char c) { return c >= 'A' && c <= 'Z'; }
inline char lowc(char c) { return isUp(static_cast<unsigned char>(c)) ? static_cast<char>(c + 32) : c; }
inline char upc(char c) { return (c >= 'a' && c <= 'z') ? static_cast<char>(c - 32) : c; }
std::string lower(std::string s) { for (auto& c : s) c = lowc(c); return s; }
std::string upper(std::string s) { for (auto& c : s) c = upc(c); return s; }

// ตัวอักษรที่ OCR ฟอนต์มังงะสับสนบ่อย (ตัวพิมพ์เล็ก) cost ต่ำ = สับสนง่าย
struct Rule { const char* from; const char* to; float cost; };
constexpr Rule kRules[] = {
    {"l", "i", 0.25f}, {"i", "l", 0.25f}, {"1", "i", 0.30f}, {"1", "l", 0.30f}, {"|", "i", 0.20f}, {"|", "l", 0.20f},
    {"0", "o", 0.25f}, {"5", "s", 0.30f}, {"8", "b", 0.40f}, {"6", "g", 0.40f}, {"2", "z", 0.40f}, {"4", "a", 0.50f},
    {"rn", "m", 0.30f}, {"m", "rn", 0.30f}, {"vv", "w", 0.30f}, {"w", "vv", 0.30f}, {"cl", "d", 0.35f}, {"d", "cl", 0.35f},
    {"li", "h", 0.50f}, {"h", "li", 0.50f}, {"b", "h", 0.50f}, {"h", "b", 0.50f}, {"u", "v", 0.50f}, {"v", "u", 0.50f},
    {"c", "e", 0.60f}, {"e", "c", 0.60f}, {"a", "o", 0.60f}, {"o", "a", 0.60f}, {"n", "u", 0.60f}, {"u", "n", 0.60f},
};

// อุทาน/เสียงประกอบ/คำญี่ปุ่นยอดนิยม: ถือว่าเป็นคำรู้จัก ไม่แก้
const char* const kExtra[] = {
    "ah", "ahh", "ahhh", "oh", "ohh", "uh", "uhh", "um", "umm", "hmm", "hmmm", "huh", "heh", "hehe", "haha", "hah", "ha", "hee", "eh", "ehh", "ugh",
    "argh", "aargh", "gah", "geez", "gee", "tsk", "yay", "yikes", "oops", "ow", "eek", "shh", "shhh", "psst", "bam", "boom", "thud", "slam", "bang",
    "whoosh", "zap", "pow", "sigh", "gasp", "gulp", "ding", "dong", "tch", "nngh", "ngh", "hnn", "mmm", "mhm", "yo", "ya", "ok", "okay", "kyaa", "kya",
    "senpai", "sempai", "sensei", "kouhai", "kohai", "onii", "oniichan", "onee", "oneechan", "neesan", "niisan", "nii", "nee", "chan", "kun", "san", "sama",
    "dono", "baka", "kawaii", "sugoi", "nani", "desu", "hai", "iie", "gomen", "arigato", "itadakimasu", "ganbare", "yamete", "urusai", "dame", "yare",
    "muda", "omae", "kisama", "chikushou", "yokai", "oni", "ninja", "shinobi", "jutsu", "chakra", "katana", "ramen", "manga", "anime", "otaku", "senpai",
    "tokyo", "kyoto", "osaka", "japan", "japanese",
};

// เขียนอัฒภาคหาย: dont -> don't (เฉพาะคำที่ไม่กำกวม)
struct Contr { const char* from; const char* to; };
constexpr Contr kContr[] = {
    {"dont", "don't"}, {"doesnt", "doesn't"}, {"didnt", "didn't"}, {"isnt", "isn't"}, {"arent", "aren't"}, {"wasnt", "wasn't"}, {"werent", "weren't"},
    {"couldnt", "couldn't"}, {"wouldnt", "wouldn't"}, {"shouldnt", "shouldn't"}, {"havent", "haven't"}, {"hasnt", "hasn't"}, {"hadnt", "hadn't"},
    {"cant", "can't"}, {"im", "i'm"}, {"ive", "i've"}, {"youre", "you're"}, {"theyre", "they're"}, {"thats", "that's"},
    {"whats", "what's"},
};

struct Tok { size_t b, e; bool edge; };

std::vector<Tok> tokenize(const std::string& s) {
    std::vector<Tok> v;
    const size_t n = s.size();
    size_t i = 0;
    while (i < n) {
        if (!isAl(static_cast<unsigned char>(s[i]))) { ++i; continue; }
        const size_t b = i;
        while (i < n && (isAl(static_cast<unsigned char>(s[i])) ||
                         (s[i] == '\'' && i > b && i + 1 < n && isAl(static_cast<unsigned char>(s[i + 1]))))) ++i;
        const bool hi = (b > 0 && (static_cast<unsigned char>(s[b - 1]) & 0x80)) || (i < n && (static_cast<unsigned char>(s[i]) & 0x80));
        v.push_back({b, i, hi});
    }
    return v;
}

// ข้อความตัวพิมพ์ใหญ่ล้วนหรือไม่ (ไม่นับ l/i/I เพราะ OCR สับสนกัน)
bool isAllCaps(const std::string& s) {
    int tot = 0, up = 0;
    for (char ch : s) {
        const unsigned char c = static_cast<unsigned char>(ch);
        if (!isLetter(c) || c == 'l' || c == 'i' || c == 'I') continue;
        ++tot; if (isUp(c)) ++up;
    }
    return tot >= 3 && up * 10 >= tot * 8;
}

enum class Pat { Lower, Title, Upper };

Pat patternOf(const std::string& tok, bool ctxCaps) {
    // I/i/l กลางคำสับสนกันใน OCR จึงไม่นับเป็นหลักฐานตัวพิมพ์ใหญ่/เล็ก (FinaIly = Finally)
    int idx = 0, L = 0, U = 0; bool firstUp = false;
    for (char ch : tok) {
        const unsigned char c = static_cast<unsigned char>(ch);
        if (!isLetter(c)) continue;
        if (idx == 0) firstUp = isUp(c);
        const bool neutral = idx > 0 && (c == 'I' || c == 'l' || c == 'i');
        if (!neutral) { ++L; if (isUp(c)) ++U; }
        ++idx;
    }
    if (idx == 0) return Pat::Lower;
    if (idx == 1) return U == 1 ? (ctxCaps ? Pat::Upper : Pat::Title) : Pat::Lower;
    if (L >= 2 && U * 10 >= L * 6) return Pat::Upper;
    if (firstUp && U == 1) return Pat::Title;
    return ctxCaps && U * 2 >= L ? Pat::Upper : Pat::Lower;
}

std::string applyCase(std::string w, Pat p) {
    if (p == Pat::Upper) w = upper(w);
    else if (p == Pat::Title && !w.empty()) w[0] = upc(w[0]);
    // "i" เดี่ยวและ "i'm/i'll/..." ต้องเป็น I เสมอ
    if (!w.empty() && w[0] == 'i' && (w.size() == 1 || w[1] == '\'')) w[0] = 'I';
    return w;
}

bool hasDigitOrBar(const std::string& s) {
    for (char c : s) if (isDigit(static_cast<unsigned char>(c)) || c == '|') return true;
    return false;
}

}  // namespace

size_t WordGuess::load(const char* data, size_t len, const char* known, size_t knownLen) {
    if (!data || len == 0) return 0;
    std::unordered_map<std::string, float> m;
    m.reserve(90000);
    const char* p = data; const char* end = data + len;
    while (p < end) {
        const char* nl = static_cast<const char*>(std::memchr(p, '\n', static_cast<size_t>(end - p)));
        const char* le = nl ? nl : end;
        const char* sp = static_cast<const char*>(std::memchr(p, ' ', static_cast<size_t>(le - p)));
        if (sp && sp > p && sp + 1 < le) {
            std::string w(p, static_cast<size_t>(sp - p));
            const std::string num(sp + 1, static_cast<size_t>(le - sp - 1));
            char* ep = nullptr;
            const float v = std::strtof(num.c_str(), &ep);
            if (ep != num.c_str() && v > 0.f && v < 20.f) m[lower(w)] = v;
        }
        p = nl ? nl + 1 : end;
    }
    if (m.size() < 1000) return 0;   // ไฟล์เสีย/ว่าง: ไม่เปิดใช้งาน
    lg_ = std::move(m);
    for (const char* w : kExtra) extra_.insert(w);
    if (known && knownLen) {
        const char* p2 = known; const char* e2 = known + knownLen;
        while (p2 < e2) {
            const char* nl = static_cast<const char*>(std::memchr(p2, '\n', static_cast<size_t>(e2 - p2)));
            const char* le = nl ? nl : e2;
            const size_t n = static_cast<size_t>(le - p2);
            if (n >= 4 && n <= 24) {
                std::string w; bool ok = true;
                for (const char* q = p2; q < le; ++q) { if (!isLetter(static_cast<unsigned char>(*q))) { ok = false; break; } w.push_back(lowc(*q)); }
                if (ok) extra_.insert(std::move(w));
            }
            p2 = nl ? nl + 1 : e2;
        }
    }
    return lg_.size();
}

const float* WordGuess::find(const std::string& w) const {
    auto it = lg_.find(w);
    return it == lg_.end() ? nullptr : &it->second;
}

bool WordGuess::known(const std::string& w, const std::unordered_set<std::string>& pr) const {
    return find(w) || extra_.count(w) || pr.count(w);
}

// เลือกคำที่น่าจะใช่ที่สุดสำหรับ lw (ตัวพิมพ์เล็ก ไม่รู้จัก) คืน false = ไม่แน่ใจ
bool WordGuess::best(const std::string& lw, bool allCaps, std::string& out) const {
    struct Cand { std::string w; float cost, lg, score; };
    std::unordered_map<std::string, float> st;
    st[lw] = 0.f;
    std::vector<std::string> frontier{lw};
    for (int depth = 0; depth < 2 && !frontier.empty(); ++depth) {
        std::vector<std::string> next;
        for (const auto& s : frontier) {
            const float base = st[s];
            for (const auto& r : kRules) {
                const size_t fl = std::strlen(r.from);
                size_t pos = 0;
                while ((pos = s.find(r.from, pos)) != std::string::npos) {
                    std::string t = s; t.replace(pos, fl, r.to);
                    const float c = base + r.cost;
                    if (c <= 0.95f) {
                        auto it = st.find(t);
                        if (it == st.end() || c < it->second) { st[t] = c; next.push_back(std::move(t)); }
                    }
                    ++pos;
                }
            }
        }
        if (st.size() > 4000) break;
        frontier.swap(next);
    }
    std::vector<Cand> cs;
    for (const auto& kv : st) {
        if (kv.first == lw || hasDigitOrBar(kv.first)) continue;
        const float* l = find(kv.first);
        if (!l) continue;
        const float minLg = kv.second <= 0.5f ? 2.7f : 4.0f;
        if (*l < minLg) continue;
        cs.push_back({kv.first, kv.second, *l, kv.second - 0.25f * *l});
    }
    // แก้ 1 ตัวอักษรแบบทั่วไป: เฉพาะคำยาว ตัวแรกต้องตรง และคำเป้าหมายต้องพบบ่อย (กันชื่อคนถูกแก้เป็นคำอื่น)
    if (lw.size() >= 5 && !allCaps && !hasDigitOrBar(lw)) {   // ตัวพิมพ์ใหญ่ล้วน: ชื่อตัวละครเสี่ยงโดนแก้ จึงใช้เฉพาะตารางสับสน
        const float minLg = 4.3f;
        std::unordered_set<std::string> seen;
        auto tryCand = [&](const std::string& t) {
            if (t.empty() || t[0] != lw[0] || t == lw || !seen.insert(t).second) return;
            const float* l = find(t);
            if (!l || *l < minLg) return;
            for (auto& c : cs) if (c.w == t) return;   // มีจากตารางสับสนแล้ว (ราคาถูกกว่า)
            cs.push_back({t, 1.0f, *l, 1.0f - 0.25f * *l});
        };
        const size_t n = lw.size();
        std::string t;
        for (size_t i = 0; i < n; ++i) {
            t = lw; t.erase(i, 1); tryCand(t);
            if (i + 1 < n) { t = lw; std::swap(t[i], t[i + 1]); tryCand(t); }
            for (char a = 'a'; a <= 'z'; ++a) {
                if (a != lw[i]) { t = lw; t[i] = a; tryCand(t); }
                t = lw; t.insert(i, 1, a); tryCand(t);
            }
        }
        for (char a = 'a'; a <= 'z'; ++a) { t = lw; t.push_back(a); tryCand(t); }
    }
    if (cs.empty()) return false;
    std::sort(cs.begin(), cs.end(), [](const Cand& a, const Cand& b) { return a.score < b.score; });
    if (cs.size() > 1 && cs[1].score - cs[0].score < 0.1f) return false;   // กำกวม -> ไม่แก้
    out = cs[0].w;
    return true;
}

bool WordGuess::correct(const std::string& s, bool allCaps, const std::unordered_set<std::string>& pr, std::string& out) const {
    if (s.empty()) return false;
    // --- มีเครื่องหมายอัฒภาค: แก้เฉพาะ "l'm", "1'll", "i'd" -> "I'm" ... ---
    const size_t ap = s.find('\'');
    if (ap != std::string::npos) {
        if (ap == 2 && (s[0] == 'l' || s[0] == '1' || s[0] == '|')) {        // lT'S -> IT'S, lt's -> It's
            const char c2 = lowc(s[1]);
            if (c2 == 't' || c2 == 'f' || c2 == 'n' || c2 == 's') { out = "I" + s.substr(1); return true; }
            return false;
        }
        if (ap != 1) return false;
        const char f = s[0];
        if (f != 'l' && f != 'L' && f != '1' && f != '|' && f != 'I' && f != 'i') return false;
        const std::string rest = lower(s.substr(2));
        if (rest != "m" && rest != "ll" && rest != "d" && rest != "ve") return false;
        bool capsRest = true; for (char c : s.substr(2)) if (isLetter(static_cast<unsigned char>(c)) && !isUp(static_cast<unsigned char>(c))) capsRest = false;
        std::string r = "I'" + (capsRest ? upper(rest) : rest);
        if (r == s) return false;
        out = r; return true;
    }
    int letters = 0; for (char c : s) if (isLetter(static_cast<unsigned char>(c))) ++letters;
    if (letters == 0) return false;
    const std::string lw = lower(s);
    const bool dg = hasDigitOrBar(s);
    if (s == "l" || s == "|") { out = "I"; return true; }
    if (isDigit(static_cast<unsigned char>(s[0])) && letters <= 2) return false;   // 2ND, 10AM, 3D
    // l/1/| ตามด้วย t f n s = It If In Is (OCR อ่านตัว I เป็น l)
    if (s.size() == 2 && (s[0] == 'l' || s[0] == '1' || s[0] == '|')) {
        const char c2 = lowc(s[1]);
        if (c2 == 't' || c2 == 'f' || c2 == 'n' || c2 == 's') {
            out = std::string("I") + ((isUp(static_cast<unsigned char>(s[1])) || allCaps) ? upc(c2) : c2);
            return true;
        }
    }
    for (const auto& c : kContr) {
        if (lw == c.from && !pr.count(lw)) { out = applyCase(c.to, patternOf(s, allCaps)); return true; }
    }
    if (!dg && known(lw, pr)) return false;
    // ชื่อเฉพาะ (Naruto) ในข้อความตัวพิมพ์เล็กผสม: ไม่แตะ
    const Pat pat = patternOf(s, allCaps);
    bool midI = false; for (size_t i = 1; i < s.size(); ++i) if (s[i] == 'I') midI = true;   // I กลางคำ = OCR อ่านผิด ไม่ใช่ชื่อ
    if (!dg && !allCaps && !midI && pat == Pat::Title && letters >= 3) return false;
    if (letters < 3 && !dg) return false;
    std::string w;
    if (!best(lw, allCaps, w)) return false;
    out = applyCase(w, pat);
    return out != s;
}

std::string WordGuess::fix(const std::string& text, const std::vector<std::string>& protect) const {
    if (!ready() || text.empty() || text.size() > 20000) return text;
    std::unordered_set<std::string> pr;
    for (const auto& p : protect) {
        std::string cur;
        for (char c : lower(p) + " ") {
            if (isAl(static_cast<unsigned char>(c)) || c == '\'') cur.push_back(c);
            else { if (cur.size() >= 3) pr.insert(cur); cur.clear(); }
        }
    }
    const bool caps = isAllCaps(text);
    const auto toks = tokenize(text);

    struct Seg { size_t b, e; std::string joined; bool isJoin; bool edge; };
    std::vector<Seg> segs;
    auto alphaOnly = [&](const Tok& t) {
        for (size_t i = t.b; i < t.e; ++i) if (!isLetter(static_cast<unsigned char>(text[i]))) return false;
        return true;
    };
    auto strong = [&](const std::string& w) { const float* l = find(w); return l && *l >= 4.7f && w.size() >= 2; };
    for (size_t i = 0; i < toks.size(); ++i) {
        if (i + 1 < toks.size() && !toks[i].edge && !toks[i + 1].edge &&
            toks[i + 1].b == toks[i].e + 1 && text[toks[i].e] == ' ' && alphaOnly(toks[i]) && alphaOnly(toks[i + 1])) {
            const std::string a = lower(text.substr(toks[i].b, toks[i].e - toks[i].b));
            const std::string b = lower(text.substr(toks[i + 1].b, toks[i + 1].e - toks[i + 1].b));
            const bool skip = a == "a" || a == "i" || b == "a" || b == "i" || (b.size() == 1 && (b == "s" || b == "t" || b == "m" || b == "d")) ||
                              pr.count(a) || pr.count(b);
            if (!skip && (!strong(a) || !strong(b))) {
                const float* l = find(a + b);
                if (l && *l >= 3.7f) {
                    const std::string both = text.substr(toks[i].b, toks[i + 1].e - toks[i].b);
                    segs.push_back({toks[i].b, toks[i + 1].e, applyCase(a + b, patternOf(both, caps)), true, false});
                    ++i; continue;
                }
            }
        }
        segs.push_back({toks[i].b, toks[i].e, "", false, toks[i].edge});
    }
    std::string res; res.reserve(text.size() + 8);
    size_t pos = 0;
    for (const auto& sg : segs) {
        res.append(text, pos, sg.b - pos);
        const std::string tok = text.substr(sg.b, sg.e - sg.b);
        std::string o;
        if (sg.isJoin) res += sg.joined;
        else if (!sg.edge && correct(tok, caps, pr, o)) {
            // ตัวเลขนำหน้าคำที่ขึ้นต้นประโยค (0pen -> Open)
            if (!caps && (isDigit(static_cast<unsigned char>(tok[0])) || tok[0] == '|') && !o.empty() && o[0] >= 'a' && o[0] <= 'z') {
                size_t k = sg.b; while (k > 0 && (text[k - 1] == ' ' || text[k - 1] == '"' || text[k - 1] == '\'')) --k;
                if (k == 0 || text[k - 1] == '.' || text[k - 1] == '!' || text[k - 1] == '?') o[0] = upc(o[0]);
            }
            res += o;
        }
        else res += tok;
        pos = sg.e;
    }
    res.append(text, pos, std::string::npos);
    return res;
}

float WordGuess::plausibility(const std::string& text, const std::vector<std::string>& protect) const {
    if (!ready() || text.empty() || text.size() > 20000) return -1.f;
    std::unordered_set<std::string> pr;
    for (const auto& p : protect) pr.insert(lower(p));
    int n = 0, k = 0;
    for (const auto& t : tokenize(text)) {
        std::string w;
        bool ok = true;
        for (size_t i = t.b; i < t.e; ++i) {
            const char c = text[i];
            if (c == '\'') break;                       // ตัดส่วน 's 't ออก
            if (!isLetter(static_cast<unsigned char>(c))) { ok = false; break; }
            w.push_back(lowc(c));
        }
        if (!ok || t.edge || w.size() < 3) continue;
        ++n;
        bool contr = false; for (const auto& c : kContr) if (w == c.from) contr = true;
        if (known(w, pr) || contr) ++k;
    }
    if (n < 3) return -1.f;
    return static_cast<float>(k) / static_cast<float>(n);
}

}  // namespace mo
