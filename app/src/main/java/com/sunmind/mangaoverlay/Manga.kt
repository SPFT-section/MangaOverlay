package com.sunmind.mangaoverlay

/**
 * สำนวนมังงะภาษาไทย
 * 1) phrase(): วลีสั้น/คำอุทาน/เสียงประกอบที่เจอบ่อยในมังงะ แปลจากตารางตรง ๆ (เร็ว ไม่ต้องใช้เน็ต และเป็นสำนวนเดียวกันทุกครั้ง)
 *    -- เครื่องแปลทั่วไปแปลคำสั้นพวกนี้แย่ที่สุด เช่น "Huh?" "Tch" "Damn it!"
 * 2) style(): ปรับผลจากเครื่องแปล (Google/Bing/ML Kit) ให้เป็นภาษาพูด ตัดคำสุภาพ/ทางการที่ใส่เกินมา
 */
object Manga {
    private val book: HashMap<String, String> = HashMap<String, String>(512).also { m ->
        fun add(keys: String, th: String) { for (k in keys.split('|')) m[k.trim()] = th }
        // คำอุทาน
        add("what", "อะไรนะ"); add("what the hell|what the heck|what in the world", "อะไรกันเนี่ย")
        add("huh|hah|hunh", "หา"); add("eh|eeh|ehh|eh eh", "เอ๊ะ"); add("hey|oi|oy|yo", "เฮ้ย")
        add("oh", "โอ้"); add("oh i see", "อ๋อ เข้าใจแล้ว"); add("aha|ahh i see|ooh", "อ๋อ")
        add("ah|aah|ack", "อ๊ะ"); add("wow", "ว้าว"); add("whoa|woah|whoah", "โอ้โห"); add("yay|hooray|hurray|woo hoo|woohoo", "เย้")
        add("ugh|urgh|sigh", "เฮ้อ"); add("argh|aargh|arrgh|agh|gah|aaargh|arghh", "อ๊าก"); add("tch|tsk|chi|ch|tchh", "ชิ")
        add("geez|jeez|gee", "โธ่เอ๊ย"); add("hmm|hmmm|hm|mm|mmm|hmph|humph", "อืม"); add("um|uh|er|erm|umm|uhh|err|uhm|ehm", "เอ่อ")
        add("well", "ก็นะ"); add("ow|ouch|owww|ow ow", "โอ๊ย"); add("oops|whoops", "อุ๊บส์"); add("phew|whew", "โล่งอก")
        add("ha|hah ha", "ฮะ"); add("haha|hahaha|ha ha|ha ha ha|hahahaha|hahah", "ฮ่าๆๆ"); add("hehe|heh|heheh|hehehe|he he", "เฮะๆ")
        add("kya|kyaa|kyaaa|kyaaaa|kyaaaah|eek|eep", "กรี๊ด"); add("aaah|aaaah|aaaaah|ahhh|ahhhh|aah aah", "อ๊าา")
        add("fufu|fufufu|ufufu", "ฟุๆๆ"); add("hnn|nn|nngh|ngh|guh", "อึก"); add("gulp", "เอื๊อก"); add("gasp", "เฮือก")
        add("sob|sniff|sniffle|waah|waaah|boohoo", "ฮือ"); add("giggle|tee hee", "คิกคัก"); add("yawn", "หาว")
        // ตอบรับ/ปฏิเสธ
        add("yes|yeah|yep|yup|uh huh", "ใช่"); add("no", "ไม่"); add("nope|nah", "ไม่เลย"); add("nooo|noo|nooooo|noooo", "ไม่นะ"); add("oh no", "ไม่นะ")
        add("no way|no way in hell", "ไม่จริงน่า"); add("okay|ok|okey|okie", "โอเค"); add("alright|all right", "ได้เลย")
        add("i see|i understand|i get it|got it|gotcha", "เข้าใจแล้ว"); add("understood|roger|roger that|affirmative", "รับทราบ")
        add("of course|naturally|obviously", "แน่นอนอยู่แล้ว"); add("maybe|perhaps", "อาจจะนะ"); add("probably", "คงจะงั้น")
        add("that's right|thats right|that's true|thats true|exactly|precisely", "ใช่เลย"); add("sure|sure thing", "ได้เลย")
        add("really|is that so|is that true", "จริงเหรอ"); add("seriously", "จริงดิ"); add("are you serious|you're kidding|youre kidding|you're joking|no kidding|you must be joking", "ล้อเล่นใช่ไหม")
        add("never mind|nevermind|forget it|forget about it", "ช่างมันเถอะ"); add("it's nothing|its nothing|it's nothing really", "ไม่มีอะไรหรอก")
        add("i'm fine|im fine|i'm okay|im okay|i'm ok|im ok|i'm alright", "ฉันไม่เป็นไร"); add("it's fine|its fine|it's okay|its okay|no problem|it's all right", "ไม่เป็นไรหรอก")
        add("impossible|that's impossible|thats impossible|that can't be|that cant be|this can't be", "เป็นไปไม่ได้"); add("i can't believe it|i cant believe it|unbelievable", "ไม่อยากจะเชื่อ")
        // คำถาม
        add("who", "ใคร"); add("who are you", "แกเป็นใคร"); add("who is it|who's there|whos there", "ใครน่ะ"); add("why", "ทำไม"); add("why not", "ทำไมจะไม่ได้ล่ะ")
        add("how", "ยังไง"); add("how come", "ทำไมถึงเป็นอย่างนั้นล่ะ"); add("where", "ที่ไหน"); add("when", "เมื่อไหร่"); add("which one", "อันไหน"); add("what is it|what's that|whats that|what is that", "อะไรน่ะ")
        add("what happened", "เกิดอะไรขึ้น"); add("what's wrong|whats wrong|what is wrong", "เป็นอะไรไป"); add("what are you doing", "ทำอะไรอยู่น่ะ")
        add("are you okay|are you ok|you okay|are you all right|are you alright|you ok|you alright", "เป็นอะไรรึเปล่า"); add("what do you mean|what does that mean", "หมายความว่ายังไง")
        // สั่ง/เตือน
        add("wait", "เดี๋ยวก่อน"); add("wait a minute|wait a second|hold on|hold it|just a moment|one moment", "เดี๋ยวก่อนสิ")
        add("stop|stop it|halt", "หยุดนะ"); add("don't move|dont move|freeze", "อย่าขยับ"); add("run|run away|run for it", "หนีไปเร็ว")
        add("look out|watch out|careful|be careful|look out behind you|heads up", "ระวัง"); add("hurry|hurry up|quick|quickly|faster", "เร็วเข้า")
        add("come on|c'mon|cmon", "เอาน่า"); add("come here", "มานี่สิ"); add("this way", "ทางนี้"); add("follow me", "ตามมา")
        add("let's go|lets go|let us go", "ไปกันเถอะ"); add("let's do this|lets do this", "ลุยกันเลย"); add("here we go", "ไปล่ะนะ")
        add("shut up|be quiet|quiet", "หุบปากซะ"); add("go away|get lost", "ไปให้พ้น"); add("get out|get out of here|out of my way", "ออกไป")
        add("get away from me|stay back|stay away|back off", "ถอยไปนะ"); add("help|help me|save me", "ช่วยด้วย"); add("somebody help|someone help|somebody help me|someone help me", "ใครก็ได้ ช่วยด้วย")
        add("please", "ขอร้องล่ะ"); add("leave it to me", "เรื่องนี้ฝากฉันเอง"); add("don't worry|dont worry|no worries", "ไม่ต้องห่วง")
        // ด่า/อารมณ์
        add("damn|damn it|dammit|goddamn it|god damn it|damn you|dang it|darn it", "บ้าเอ๊ย"); add("darn", "บ้าจริง"); add("shit|shoot", "เวร"); add("fuck|fucking hell", "เวรเอ๊ย"); add("crap", "แย่จริง")
        add("idiot|fool|moron|dummy", "ไอ้โง่"); add("stupid|how stupid", "โง่จริงๆ"); add("bastard|jerk", "ไอ้เลว"); add("asshole", "ไอ้บ้า"); add("baka", "บากะ")
        add("you're dead|youre dead|you're done for|you're finished", "แกตายแน่"); add("die", "ตายซะ"); add("i'll kill you|ill kill you|i will kill you", "ฉันจะฆ่าแก")
        add("i won't forgive you|i wont forgive you|i'll never forgive you", "ฉันไม่ยกโทษให้แน่")
        // ทักทาย/มารยาท
        add("sorry|i'm sorry|im sorry|my apologies|my bad", "ขอโทษนะ"); add("thanks|thank you|thank you very much|thanks a lot|thank you so much", "ขอบคุณ"); add("excuse me|pardon me|pardon", "ขอโทษนะ")
        add("hello", "สวัสดี"); add("hi|hiya", "หวัดดี"); add("good morning", "อรุณสวัสดิ์"); add("good evening", "สวัสดีตอนเย็น"); add("good night", "ราตรีสวัสดิ์")
        add("goodbye|bye|bye bye|farewell", "ลาก่อน"); add("see you|see you later|see ya|see you soon|see you tomorrow", "แล้วเจอกันนะ")
        add("i'm home|im home|i'm back|im back|i'm back home|i'm home now", "กลับมาแล้ว"); add("welcome back|welcome home", "ยินดีต้อนรับกลับบ้าน"); add("nice to meet you|pleased to meet you", "ยินดีที่ได้รู้จัก")
        add("good luck", "โชคดีนะ"); add("congratulations|congrats", "ยินดีด้วย"); add("happy birthday", "สุขสันต์วันเกิด"); add("bless you", "ขอให้โชคดี")
        // ชม/ความรู้สึก
        add("i did it|i made it", "ทำได้แล้ว"); add("we did it", "เราทำได้แล้ว"); add("i won|i win", "ฉันชนะแล้ว"); add("good job|well done|nice job|great job|nice work", "ทำได้ดีมาก")
        add("nice", "ดีเลย"); add("great", "ยอดเยี่ยม"); add("awesome", "สุดยอด"); add("amazing", "น่าทึ่งจริงๆ"); add("cool", "เท่จัง"); add("cute", "น่ารักจัง"); add("beautiful", "สวยจัง")
        add("delicious|yummy|tasty", "อร่อยจัง"); add("i'm hungry|im hungry", "หิวจัง"); add("i'm tired|im tired", "เหนื่อยจัง"); add("i'm scared|im scared", "กลัวจัง"); add("it hurts", "เจ็บ")
        add("it's cold|its cold", "หนาวจัง"); add("it's hot|its hot", "ร้อนจัง"); add("it's over|its over", "จบแล้ว"); add("it's not over|its not over|it isn't over", "ยังไม่จบหรอก")
        add("i love you", "ฉันรักเธอ"); add("i hate you", "ฉันเกลียดเธอ"); add("i like you", "ฉันชอบเธอ")
        // คำเรียก
        add("onii-chan|oniichan|onii chan|onii-chan|big brother", "โอนี่จัง"); add("onee-chan|oneechan|onee chan|big sister", "โอเนะจัง"); add("onii-san|onii san", "โอนี่ซัง"); add("onee-san|onee san", "โอเนะซัง")
        add("senpai", "เซมไป"); add("sensei", "เซนเซ"); add("dad|daddy|father", "พ่อ"); add("mom|mommy|mother|mama", "แม่")
        // จบตอน
        add("the end|end", "จบ"); add("to be continued|tbc", "โปรดติดตามตอนต่อไป")
        // เสียงประกอบ
        add("boom|kaboom", "ตูม"); add("bang", "ปัง"); add("bam|pow|wham", "พลั่ก"); add("crash", "โครม"); add("thud|thump|thunk", "ตึก"); add("slam", "ปัง")
        add("splash", "ซ่า"); add("whoosh|swoosh|swish", "ฟิ้ว"); add("crack", "แกร๊ก"); add("knock knock|knock", "ก๊อก ก๊อก"); add("ding dong", "ติ๊งหน่อง")
        add("beep|beep beep", "บี๊บ"); add("ring ring|ring", "กริ๊ง กริ๊ง"); add("tick tock", "ติ๊กต็อก"); add("thump thump|ba-dump|badump|ba dump", "ตุ้บ ตุ้บ"); add("rumble", "ครืน")
        add("slash", "ฉับ"); add("clang|clank", "แคร้ง"); add("stomp", "ตึง"); add("bzzt|zap", "ซ่า"); add("splat", "แปะ"); add("poof", "ฟุบ"); add("pop", "ป๊อก")
    }

    private val full = Regex("^([^A-Za-z]*)([A-Za-z][A-Za-z' ,\\-]*?)([\\s.!?…~]*)$")

    /** ถ้าทั้งกรอบเป็นวลีสั้นที่อยู่ในตาราง คืนคำไทย พร้อมเครื่องหมายท้ายประโยคตามต้นฉบับ */
    fun phrase(src: String): String? {
        val m = full.matchEntire(src.trim()) ?: return null
        val core = m.groupValues[2].lowercase().replace(",", "").replace(Regex("\\s+"), " ").trim()
        val th = book[core] ?: return null
        val lead = if (m.groupValues[1].contains('.') || m.groupValues[1].contains('…')) "…" else ""
        val tail = m.groupValues[3]
        val q = StringBuilder()
        for (c in tail) if ((c == '!' || c == '?') && q.length < 3) q.append(c)
        val p = if (q.isNotEmpty()) q.toString() else if (tail.contains('.') && tail.count { it == '.' } >= 2 || tail.contains('…')) "…" else ""
        return lead + th + p
    }

    private val politeCue = Regex("(?i)\\b(sir|ma'?am|madam|miss|mister|mr|mrs|please|thank|thanks|sorry|excuse|pardon|apolog|honou?r|master|mistress|lord|lady|majesty|highness|princess|prince|king|queen|customer|guest)\\b")
    private val tailPolite = Regex("\\s*(ครับผม|ครับ|ค่ะ|คะ|ขอรับ|เจ้าค่ะ|เจ้าคะ)(?=[\\s!?…,.~]*$)")
    private val rules: List<Pair<Regex, String>> = listOf(
        "กรุณา" to "ช่วย", "อย่างไร" to "ยังไง", "เพราะเหตุใด" to "ทำไม", "เหตุใด" to "ทำไม", "เช่นนั้น" to "แบบนั้น", "เช่นนี้" to "แบบนี้",
        "ไม่ใช่หรือ" to "ไม่ใช่เหรอ", "ใช่หรือไม่" to "ใช่รึเปล่า", "รับประทาน" to "กิน", "ข้าพเจ้า" to "ข้า", "ดิฉัน" to "ฉัน", "กระผม" to "ผม",
        "บิดา" to "พ่อ", "มารดา" to "แม่", "ขอบคุณมากค่ะ" to "ขอบคุณ", "เป็นอย่างมาก" to "มาก", "อย่างแน่นอน" to "แน่นอน", "ในที่สุดแล้ว" to "ในที่สุด",
        "ขอโทษด้วย" to "ขอโทษนะ", "ฉันขอโทษ" to "ขอโทษนะ", "ฉันรักคุณ" to "ฉันรักเธอ", "ฉันเกลียดคุณ" to "ฉันเกลียดเธอ"
    ).map { (a, b) -> Regex(Regex.escape(a)) to b }

    /** ปรับผลแปลของเครื่องให้เป็นภาษาพูดแบบมังงะ (ไม่ใช้กับผลจาก AI เพราะ AI ได้รับคำสั่งเรื่องสำนวนแล้ว) */
    fun style(en: String, th: String): String {
        if (!Prefs.mangaStyle) return th
        var t = th.trim()
        if (!politeCue.containsMatchIn(en)) t = t.replace(tailPolite, "")
        for ((r, b) in rules) t = r.replace(t, b)
        t = t.replace(Regex("\\s*([!?…]+)"), "$1").replace(Regex("\\s{2,}"), " ").trim()
        return if (t.isBlank()) th else t
    }
}
