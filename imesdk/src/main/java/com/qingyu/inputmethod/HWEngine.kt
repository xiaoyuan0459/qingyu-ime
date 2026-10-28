package com.qingyu.inputmethod

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import com.qingyu.handwritten.runtime.QingyuHwRecognizer
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.utils.HanziPinyin
import com.qingyu.imemodule.utils.LogUtil
import com.qingyu.imemodule.utils.thread.ThreadPoolUtils
import com.qingyu.inputmethod.core.Candidate
import com.qingyu.imemodule.callback.IHandWritingCallBack
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 手写识别引擎：单字闭集分类（27,664 类，NCNN int8）。
 *
 * 模型语料只用 OFL 字体合成，不含 CASIA/HWDB 派生数据，可随 GPL-3.0 应用分发；
 * 训练与资产化流程见 `tools/handwriting/README.md`。
 *
 * 数据流:
 *   1. HandwritingKeyboard 采集笔画,编码为 (x, y) 点对,笔画以 (-1, 0) 结束;
 *   2. 本引擎把笔画还原并渲染为白底黑笔画 Bitmap(硬二值,无 AA);
 *   3. QingyuHwRecognizer.preprocess 完成 bbox 裁切 + 等比缩放居中 → float[64*64];
 *   4. QingyuHwRecognizer.predict 返回 Top-K 候选,装配为 Candidate 上报。
 */
object HWEngine {

    private const val TAG = "HWEngine"

    /** 模型资产目录：随模型版本号走，回滚时整目录替换。 */
    private const val ASSET_DIR = "handwriting/qingyu-hw-image-v1"

    /** 渲染给模型的画布长边目标 px。 */
    private const val RENDER_TARGET_SIZE = 360

    /** 模型输入口径:笔宽约为画布长边的 4.4%,缩到 64×64 后约 2.5px。 */
    private const val RENDER_STROKE_RATIO = 0.044f

    /** 每次识别返回的候选数。 */
    private const val CANDIDATE_COUNT = 10

    /**
     * 模型句柄。首次识别时才加载 —— 输入法常驻进程，用户可能从不用手写，
     * 不应在启动阶段就为所有人占住这份内存（int8 模型约 8 MB，ncnn 运行时另需数 MB）。
     *
     * 识别在后台线程执行，初始化也放在同一线程，避免主线程首次调用时卡顿。
     * 用双检锁保证并发识别时只初始化一次；@Volatile 保证其他线程能看到已发布的实例。
     */
    @Volatile
    private var engine: QingyuHwRecognizer? = null

    private fun engine(): QingyuHwRecognizer? {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            return initRecognizer().also { engine = it }
        }
    }

    private fun initRecognizer(): QingyuHwRecognizer? {
        return try {
            QingyuHwRecognizer.loadNativeLibrary("qingyu_hw_jni")
            QingyuHwRecognizer(
                Launcher.instance.context.assets,
                "$ASSET_DIR/model.ncnn.param",
                "$ASSET_DIR/model.ncnn.bin",
                "$ASSET_DIR/charset.json"
            ).also { LogUtil.i(TAG, "initRecognizer", "手写模型已加载：${it.classCount()} 类") }
        } catch (e: Throwable) {
            // 字表哈希/类别数校验失败会走到这里：宁可让手写不可用，也不要带着错表输出错字
            LogUtil.e(TAG, "initRecognizer", "手写模型初始化失败: ${e.message}")
            null
        }
    }

    /**
     * 释放模型与 ncnn 运行时占用的原生内存。内存压力或输入法隐藏时可调用，
     * 之后首次识别会重新加载。
     */
    fun release() {
        synchronized(this) {
            engine?.close()
            engine = null
        }
    }

    fun recognitionData(
        strokes: MutableList<Short?>,
        sample: HandwritingSampleDraft? = null,
        recogResult: IHandWritingCallBack,
    ) {
        val data = strokes?.toList()
        if (data.isNullOrEmpty()) return
        // 推理放到后台线程执行,避免阻塞 IME 主线程；模型也在这里懒加载
        ThreadPoolUtils.executeSingleton {
            try {
                val engine = engine() ?: return@executeSingleton
                val gray = renderStrokesToGray(data) ?: return@executeSingleton
                val input = FloatArray(64 * 64)
                if (!engine.preprocess(gray.pixels, gray.width, gray.height, input)) {
                    return@executeSingleton
                }
                val results = engine.predict(input, CANDIDATE_COUNT)
                if (results.isEmpty()) return@executeSingleton
                val ordered = HandwritingReranker.rerank(
                    results.map { HandwritingReranker.Scored(it.text, it.probability) },
                    HandwritingLearning.counts()
                )
                val items = ArrayList<Candidate>(ordered.size)
                for (r in ordered) {
                    val text = r.text
                    val pinyin = HanziPinyin.ofText(text).ifEmpty { text }
                    items.add(Candidate(pinyin, text))
                }
                recogResult.onSucess(items.toTypedArray(), sample)
            } catch (e: Throwable) {
                LogUtil.e(TAG, "recognitionData", "手写识别失败: ${e.message}")
            }
        }
    }

    private class GrayImage(val pixels: ByteArray, val width: Int, val height: Int)

    /**
     * 把采集的笔画点集还原并渲染为白底黑笔画灰度图。
     *
     * mSBPoint 编码:按 (x, y) 成对排列,笔画以 (-1, 0) 结束
     * (坐标恒 >= 0,不会与真实点冲突)。渲染口径与 Ismantic/Handwritten
     * 保持一致(360 目标长边 + 笔宽 4.4% + 关闭 AA + lineTo 直线段),
     * 使 C 端预处理结果与训练 / Python demo 分布对齐。
     */
    private fun renderStrokesToGray(records: List<Short?>): GrayImage? {
        val strokes = ArrayList<MutableList<PointF>>()
        var stroke: MutableList<PointF>? = null
        var i = 0
        while (i < records.size - 1) {
            val x = records[i]?.toInt() ?: break
            val y = records[i + 1]?.toInt() ?: break
            if (x == -1) { // 笔画结束标记,跳过其后的 0
                stroke = null
                i += 2
                continue
            }
            if (stroke == null) {
                stroke = ArrayList()
                strokes.add(stroke)
            }
            stroke.add(PointF(x.toFloat(), y.toFloat()))
            i += 2
        }
        if (strokes.isEmpty()) return null

        // 计算所有笔画点的包围盒
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        for (s in strokes) {
            for (p in s) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
        }
        val contentW = maxX - minX
        val contentH = maxY - minY
        // 水平/垂直单笔（"一"、"丨"、点按）的点集包围盒宽或高为 0，直接缩放会得到
        // 零宽位图；按一笔线宽兜底，退化笔画仍能渲染成有宽度的线段送识别
        val strokePx = RENDER_TARGET_SIZE * RENDER_STROKE_RATIO
        val safeW = max(contentW, strokePx)
        val safeH = max(contentH, strokePx)

        // 长边等比缩放到目标尺寸,避免在高分辨率画布放大后再大幅缩小损失细节
        val scale = RENDER_TARGET_SIZE / max(safeW, safeH)
        val dstW = max(1, (safeW * scale).roundToInt())
        val dstH = max(1, (safeH * scale).roundToInt())

        val bmp = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = false // ★关 AA★,与 PIL ImageDraw.line(L mode)一致,hard binary
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT // PIL line cap 默认 butt
            strokeJoin = Paint.Join.ROUND // PIL joint='curve' 同 round
            strokeWidth = RENDER_TARGET_SIZE * RENDER_STROKE_RATIO
        }

        val path = Path()
        for (s in strokes) {
            if (s.size < 2) {
                // 单点(点按):画一个圆点
                val p = s[0]
                canvas.drawCircle((p.x - minX) * scale, (p.y - minY) * scale, paint.strokeWidth / 2f, paint)
                continue
            }
            path.reset()
            var first = true
            for (p in s) {
                val px = (p.x - minX) * scale
                val py = (p.y - minY) * scale
                if (first) {
                    path.moveTo(px, py)
                    first = false
                } else {
                    path.lineTo(px, py) // 直线段模拟数位板采样,与训练分布一致
                }
            }
            canvas.drawPath(path, paint)
        }

        // 转灰度(白底=255,笔画=0),交给 C 端 preprocess 做 bbox+缩放+归一化
        val pixels = IntArray(dstW * dstH)
        bmp.getPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
        val gray = ByteArray(dstW * dstH)
        for (idx in pixels.indices) {
            val c = pixels[idx]
            val lum = Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f
            gray[idx] = lum.roundToInt().toByte()
        }
        return GrayImage(gray, dstW, dstH)
    }
}