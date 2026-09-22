package com.google.zxing.qrcode.detector

import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.common.BitMatrix

/**
 * 把 zxing 找到、但凑不齐三个的定位块候选拿出来。
 *
 * [FinderPatternFinder.find] 是包内可见，只能从这个包里调，所以这个文件故意放在
 * zxing 的包名下（Android 没有模块系统，同名包合法）。用处见
 * [com.xjtu.toolbox.qrlogin.QrFrameDecoder]：定位块破损的码靠剩下两个推出第三个。
 */
object FinderCandidates {
    fun of(matrix: BitMatrix, hints: Map<DecodeHintType, *>): List<FinderPattern> {
        val finder = FinderPatternFinder(matrix)
        try {
            finder.find(hints)
        } catch (_: NotFoundException) {
            // 意料之中：正是凑不齐三个才来找候选
        }
        return finder.possibleCenters.toList()
    }
}
