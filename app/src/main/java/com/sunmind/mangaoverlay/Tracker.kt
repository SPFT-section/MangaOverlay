package com.sunmind.mangaoverlay

import android.graphics.Rect
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** ผลการตามหนึ่งเฟรม: เลื่อน (tx,ty) พิกเซลจริงบนหน้าจอ, สเกล s, ok=ตามได้, still=หน้าจอนิ่ง */
class Step(val s: Float, val tx: Float, val ty: Float, val ok: Boolean, val still: Boolean = false)

/**
 * ตามการเลื่อนหน้าจอ: Lucas-Kanade + มัธยฐานของเวกเตอร์เลื่อน (แบบเลื่อนอย่างเดียว ทนทานกว่า similarity ที่สเกล/มุมเพี้ยนสะสม)
 * -> ถ้าไม่พอ ลอง similarity (กรณีซูม) -> ถ้ายังไม่พอ ใช้ phase correlation (ทนหน้าที่เส้นน้อย/พื้นขาว)
 * ตามไม่ได้ = ไม่ขยับ "เฟรมอ้างอิง" ครั้งต่อไปจึงเทียบข้ามช่วงที่ตามไม่ได้ และได้ระยะเลื่อนที่ขาดไปกลับมาครบ
 */
class Tracker(private val sw: Int, private val sh: Int) {
    private var prev: Mat? = null
    private var gap = 0
    private val hann = Mat()

    init { Imgproc.createHanningWindow(hann, Size(sw.toDouble(), sh.toDouble()), CvType.CV_32F) }

    // reset() ถูกเรียกจาก main thread ขณะ step() รันบน thread ของ Capture -> ต้องล็อก ไม่งั้น Mat ถูก release ซ้อน (native crash)
    @Synchronized fun reset() { prev?.release(); prev = null; gap = 0 }

    private fun detect(g: Mat, m: Mat, pts: MatOfPoint) = Imgproc.goodFeaturesToTrack(g, pts, 300, 0.004, 5.0, m)

    /** นอกกรอบโอเวอร์เลย์ ภาพแทบไม่เปลี่ยน = หน้าจอนิ่ง */
    private fun still(p: Mat, cur: Mat, m: Mat): Boolean {
        val d = Mat()
        return try { Core.absdiff(p, cur, d); Core.mean(d, m).`val`[0] < 0.6 } finally { d.release() }
    }

    @Synchronized
    fun step(g: ByteArray, mask: List<Rect>): Step? {
        if (g.size < sw * sh) return null
        val cur = Mat(sh, sw, CvType.CV_8UC1); cur.put(0, 0, g)
        Imgproc.GaussianBlur(cur, cur, Size(3.0, 3.0), 0.0)
        val p = prev
        if (p == null) { prev = cur; return null }
        val base = Mat(sh, sw, CvType.CV_8UC1, Scalar(255.0))
        Imgproc.rectangle(base, Point(0.0, 0.0), Point(sw.toDouble(), sh * 0.04), Scalar(0.0), -1)
        Imgproc.rectangle(base, Point(0.0, sh * 0.95), Point(sw.toDouble(), sh.toDouble()), Scalar(0.0), -1)
        val m = base.clone()
        var res: Step? = null
        try {
            for (r in mask) Imgproc.rectangle(m, Point(r.left.toDouble() / Q, r.top.toDouble() / Q),
                Point(r.right.toDouble() / Q, r.bottom.toDouble() / Q), Scalar(0.0), -1)
            if (still(p, cur, m)) { res = Step(1f, 0f, 0f, true, true) }
            else {
                res = lkTranslate(p, cur, m, base) ?: lkSimilarity(p, cur, m, base) ?: phase(p, cur)
            }
        } catch (e: Exception) { res = null }
        finally { base.release(); m.release() }
        if (res != null && res.ok) { p.release(); prev = cur; gap = 0 }
        else {
            // ตามไม่ได้: เก็บเฟรมอ้างอิงเดิมไว้เทียบข้ามช่องว่าง (สูงสุด 12 เฟรม แล้วค่อยยอมเลื่อนไปเฟรมใหม่)
            if (++gap > 12) { p.release(); prev = cur; gap = 0 } else cur.release()
        }
        return res ?: Step(1f, 0f, 0f, false)
    }

    private class Flow(val a: List<Point>, val b: List<Point>)

    private fun flow(p: Mat, cur: Mat, m: Mat, base: Mat): Flow? {
        val pts = MatOfPoint(); val p0 = MatOfPoint2f(); val p1 = MatOfPoint2f()
        val st = MatOfByte(); val er = MatOfFloat()
        try {
            detect(p, m, pts)
            if (pts.rows() < 8) detect(p, base, pts) // หน้าขาวจุดน้อย: ยอมใช้จุดบนข้อความไทยด้วย
            if (pts.rows() < 5) return null
            pts.convertTo(p0, CvType.CV_32FC2)
            Video.calcOpticalFlowPyrLK(p, cur, p0, p1, st, er, Size(21.0, 21.0), 4)
            val a0 = p0.toArray(); val a1 = p1.toArray(); val okf = st.toArray()
            val la = ArrayList<Point>(); val lb = ArrayList<Point>()
            for (i in okf.indices) if (okf[i].toInt() == 1) { la.add(a0[i]); lb.add(a1[i]) }
            return if (la.size < 5) null else Flow(la, lb)
        } finally { pts.release(); p0.release(); p1.release(); st.release(); er.release() }
    }

    private fun median(v: DoubleArray): Double { v.sort(); return if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2 }

    private fun lkTranslate(p: Mat, cur: Mat, m: Mat, base: Mat): Step? {
        val f = flow(p, cur, m, base) ?: return null
        val n = f.a.size
        val dx = DoubleArray(n) { f.b[it].x - f.a[it].x }; val dy = DoubleArray(n) { f.b[it].y - f.a[it].y }
        val mx = median(dx.copyOf()); val my = median(dy.copyOf())
        var cnt = 0; var sx = 0.0; var sy = 0.0
        for (i in 0 until n) if (hypot(dx[i] - mx, dy[i] - my) < 1.2) { cnt++; sx += dx[i]; sy += dy[i] }
        if (cnt < 5 || cnt < 0.55 * n) return null
        return Step(1f, (sx / cnt * Q).toFloat(), (sy / cnt * Q).toFloat(), true)
    }

    /** เผื่อผู้ใช้ซูมหน้า: similarity (สเกล+เลื่อน) */
    private fun lkSimilarity(p: Mat, cur: Mat, m: Mat, base: Mat): Step? {
        val f = flow(p, cur, m, base) ?: return null
        val A = MatOfPoint2f(); A.fromList(f.a)
        val B = MatOfPoint2f(); B.fromList(f.b)
        val inl = Mat()
        var mm: Mat? = null
        try {
            mm = Calib3d.estimateAffinePartial2D(A, B, inl, Calib3d.RANSAC, 2.0)
            if (mm.empty()) return null
            val n = Core.countNonZero(inl)
            val s = hypot(mm.get(0, 0)[0], mm.get(1, 0)[0]).toFloat()
            if (!(n >= 6 && n >= f.a.size * 0.45 && s in 0.6f..1.6f)) return null
            return Step(s, (mm.get(0, 2)[0] * Q).toFloat(), (mm.get(1, 2)[0] * Q).toFloat(), true)
        } finally { A.release(); B.release(); inl.release(); mm?.release() }
    }

    /** ใช้เมื่อจุดเด่นน้อย: ทั้งเฟรมเลื่อนตามกัน (โอเวอร์เลย์เลื่อนไปกับหน้าอยู่แล้ว จึงไม่รบกวน) */
    private fun phase(p: Mat, cur: Mat): Step? {
        val a = Mat(); val b = Mat()
        try {
            p.convertTo(a, CvType.CV_32F); cur.convertTo(b, CvType.CV_32F)
            val resp = DoubleArray(1)
            val sh = Imgproc.phaseCorrelate(a, b, hann, resp)
            if (resp[0] < 0.15) return null
            return Step(1f, (sh.x * Q).toFloat(), (sh.y * Q).toFloat(), true)
        } finally { a.release(); b.release() }
    }
}

/** ท่า (pose) สะสมของหน้า: screen = s*local + t ; epoch เพิ่มทุกครั้งที่ตั้งต้นใหม่ (ใช้ทิ้งผลเก่าที่ตกค้างในคิว) */
class Pose(val s: Float, val tx: Float, val ty: Float, val acc: Float, val epoch: Int)

/** ข้อมูลที่ส่งให้ UI ต่อหนึ่งเฟรม: ท่าที่แน่นอน + ความเร็ว (พิกเซล/มิลลิวินาที) สำหรับชดเชยความหน่วง */
class Disp(val pose: Pose, val vx: Float, val vy: Float, val ok: Boolean, val still: Boolean, val lost: Boolean, val moved: Boolean)

/**
 * เก็บท่าสะสมบน thread ของ Capture (ทุกเฟรมนับครบ ไม่ขึ้นกับว่า UI ว่างหรือไม่) แล้วให้ snapshot อ่านท่า "ของเฟรมนั้นพอดี"
 * ทำให้กรอบที่เพิ่มจาก OCR วางตรงตำแหน่งแม้ผู้ใช้เลื่อนหน้าระหว่างรอ OCR/แปล
 */
class PoseKeeper {
    private var s = 1f; private var tx = 0f; private var ty = 0f; private var acc = 0f
    private var vx = 0f; private var vy = 0f
    private var epoch = 0
    private var tGood = 0L      // เวลา (ns) ของเฟรมล่าสุดที่ตามได้
    private var tLast = 0L
    private var failSince = 0L
    private var lost = false

    @Synchronized fun reset(): Pose {
        s = 1f; tx = 0f; ty = 0f; acc = 0f; vx = 0f; vy = 0f; lost = false; failSince = 0L; tGood = 0L
        epoch++
        return Pose(s, tx, ty, acc, epoch)
    }

    @Synchronized fun now() = Pose(s, tx, ty, acc, epoch)

    @Synchronized fun subAcc(v: Float) { acc -= v }

    @Synchronized fun update(st: Step, ts: Long): Disp {
        var moved = false
        if (st.ok) {
            val dt = if (tGood == 0L) 16f else ((ts - tGood) / 1_000_000f).coerceIn(4f, 120f)
            if (!st.still) {
                tx = st.s * tx + st.tx; ty = st.s * ty + st.ty; s *= st.s; acc += st.ty
                moved = abs(st.tx) > 1.5f || abs(st.ty) > 1.5f
                vx = 0.5f * vx + 0.5f * (st.tx / dt); vy = 0.5f * vy + 0.5f * (st.ty / dt)
            } else { vx *= 0.3f; vy *= 0.3f }
            tGood = ts; failSince = 0L; lost = false
        } else {
            if (failSince == 0L) failSince = ts
            moved = true
            vx *= 0.8f; vy *= 0.8f
            // ตามไม่ได้ต่อเนื่องเกิน 350 ms = หลงตำแหน่งจริง ต้องสแกนใหม่
            if ((ts - failSince) / 1_000_000L > 350) lost = true
        }
        tLast = ts
        return Disp(Pose(s, tx, ty, acc, epoch), vx, vy, st.ok, st.still, lost, moved)
    }
}
