package com.sunmind.mangaoverlay

import android.graphics.Rect
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

class Step(val s: Float, val tx: Float, val ty: Float, val ok: Boolean)

/** Anchor features -> Lucas-Kanade flow -> RANSAC similarity transform. */
class Tracker(private val sw: Int, private val sh: Int) {
    private var prev: Mat? = null

    // reset() ถูกเรียกจาก main thread ขณะ step() รันบน thread ของ Capture -> ต้องล็อก ไม่งั้น Mat ถูก release ซ้อน (native crash)
    @Synchronized fun reset() { prev?.release(); prev = null }

    private fun detect(g: Mat, m: Mat, pts: MatOfPoint) = Imgproc.goodFeaturesToTrack(g, pts, 250, 0.005, 6.0, m)

    /** นอกกรอบโอเวอร์เลย์ ภาพแทบไม่เปลี่ยน = หน้าจอนิ่ง (ไม่ใช่ "ตามไม่ทัน") */
    private fun still(p: Mat, cur: Mat, m: Mat): Boolean {
        val d = Mat()
        return try { Core.absdiff(p, cur, d); Core.mean(d, m).`val`[0] < 0.6 } finally { d.release() }
    }

    @Synchronized
    fun step(g: ByteArray, mask: List<Rect>): Step? {
        if (g.size < sw * sh) return null
        val cur = Mat(sh, sw, CvType.CV_8UC1); cur.put(0, 0, g)
        val p = prev; prev = cur
        if (p == null) return null
        val base = Mat(sh, sw, CvType.CV_8UC1, Scalar(255.0))
        Imgproc.rectangle(base, Point(0.0, 0.0), Point(sw.toDouble(), sh * 0.04), Scalar(0.0), -1)
        Imgproc.rectangle(base, Point(0.0, sh * 0.95), Point(sw.toDouble(), sh.toDouble()), Scalar(0.0), -1)
        val m = base.clone()
        val pts = MatOfPoint(); val p0 = MatOfPoint2f(); val p1 = MatOfPoint2f()
        val st = MatOfByte(); val er = MatOfFloat(); val inl = Mat()
        var M: Mat? = null
        try {
            for (r in mask) Imgproc.rectangle(m, Point(r.left.toDouble() / Q, r.top.toDouble() / Q),
                Point(r.right.toDouble() / Q, r.bottom.toDouble() / Q), Scalar(0.0), -1)
            // ตามไม่ได้ -> ถ้าหน้าจอนิ่งอยู่ ให้ถือว่าไม่ขยับ แทนที่จะสั่ง OCR ใหม่วนไปเรื่อย ๆ
            fun lost() = if (still(p, cur, m)) Step(1f, 0f, 0f, true) else Step(1f, 0f, 0f, false)
            detect(p, m, pts)
            if (pts.rows() < 8) detect(p, base, pts) // หน้าขาวจุดน้อย: ยอมใช้จุดบนข้อความไทยด้วย
            if (pts.rows() < 6) return lost()
            pts.convertTo(p0, CvType.CV_32FC2)
            Video.calcOpticalFlowPyrLK(p, cur, p0, p1, st, er, Size(25.0, 25.0), 4)
            val a0 = p0.toArray(); val a1 = p1.toArray(); val okf = st.toArray()
            val la = ArrayList<Point>(); val lb = ArrayList<Point>()
            for (i in okf.indices) if (okf[i].toInt() == 1) { la.add(a0[i]); lb.add(a1[i]) }
            if (la.size < 6) return lost()
            val A = MatOfPoint2f(); A.fromList(la)
            val B = MatOfPoint2f(); B.fromList(lb)
            val mm = Calib3d.estimateAffinePartial2D(A, B, inl, Calib3d.RANSAC, 3.0)
            A.release(); B.release()
            M = mm
            if (mm.empty()) return lost()
            val n = Core.countNonZero(inl)
            val s = Math.hypot(mm.get(0, 0)[0], mm.get(1, 0)[0]).toFloat()
            val good = n >= 6 && n >= la.size * 0.4 && s in 0.6f..1.6f
            if (!good) return lost()
            return Step(s, (mm.get(0, 2)[0] * Q).toFloat(), (mm.get(1, 2)[0] * Q).toFloat(), true)
        } finally {
            p.release(); base.release(); m.release(); pts.release(); p0.release(); p1.release(); st.release(); er.release(); inl.release()
            M?.release()
        }
    }
}
