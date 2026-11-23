package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/** 量候选宽度要和画出来的一样：纠错候选量纠错那段，「打过的词」图标和画的同样大。 */
class CandidateItemWidthTest {
    @Test fun secondaryTextIsTheCommentWithoutCorrection() {
        assertEquals("yong", candidateSecondaryText("yong", "").text)
        assertEquals("", candidateSecondaryText("", "").text)
    }

    @Test fun secondaryTextIsTheCorrectionWhenThereIsOne() {
        // 注释不显示（如窄屏）时纠错照样画出来，宽度也要算上。
        assertEquals("yong hao", candidateSecondaryText("", "yogn=yong hao").text)
        assertEquals("yong", candidateSecondaryText("zong", "yogn=yong").text)
    }

    @Test fun learnedMarkScalesWithCandidateFont() {
        // 用户 11-23：角标太小看不清，放大到字高的 0.4（20sp 时 8sp），仍不占候选宽度。
        assertEquals(8f, learnedMarkSize(20.sp).value, 0.001f)
        // 跟着注释时字和注释之间让出角标：20sp 时 9dp，小字不少于原来的 3dp。
        assertEquals(9f, learnedCommentGap(20.sp).value, 0.001f)
        assertEquals(3f, learnedCommentGap(5.sp).value, 0.001f)
    }
}
