package ai.closepaw.voice.semantics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Device shapes measured on Samsung Clock 12.5.60.27 / One UI 9 (SM-F731U1):
 *
 *  - empty timer tab: hour/minute/second pickers (`小时` `分钟` `秒`), a `开始` button and presets —
 *    there is NO `添加定时器` text and NO `N 个定时器正在运行` header;
 *  - one running timer: `30 分钟` (original duration, no `初始时长` prefix), `29 分钟 41 秒钟`
 *    (remaining), and `取消` + `暂停` buttons.
 *
 * Round 29 review P1 changed what a *count* may claim:
 *
 *   1. `timerCountFrom` is a total, so it only answers from a statement — an explicit
 *      `N 个定时器正在运行` header or an explicit empty sentence. A viewport scan of cards is a
 *      lower bound, never a total: the list can scroll, and cards can leave the visible tree while
 *      still running. `添加定时器` is only a create entry point, so it is not empty-state proof.
 *   2. The lower bound lives in `visibleTimerCards`, which is safe to speak as "at least N".
 *
 * Anything unrecognised stays -1 (unknown) in both cases.
 */
class TimerCountShapeTest {
    private fun count(vararg texts: String) = ClockControl.timerCountFrom(texts.toList())
    private fun lowerBound(vararg texts: String) = ClockControl.visibleTimerCards(texts.toList())

    // ── totals: only a statement counts ──

    @Test fun `an explicit running-count header is a total`() {
        assertThat(count("定时器", "2 个定时器正在运行", "30 分钟", "取消")).isEqualTo(2)
        assertThat(count("0 个定时器正在运行")).isEqualTo(0)
    }

    @Test fun `an explicit empty sentence is a total of zero`() {
        assertThat(count("定时器", "没有正在运行的定时器")).isEqualTo(0)
        assertThat(count("暂无定时器")).isEqualTo(0)
    }

    @Test fun `the create picker is not proof that no timer is running`() {
        // The picker only says the create entry is visible. Nothing in this shape enumerates the
        // running list, so the total is unknown — not zero.
        assertThat(count("定时器", "小时", "分钟", "秒", "开始", "00:10:00", "00:15:00", "00:30:00"))
            .isEqualTo(-1)
    }

    @Test fun `the add-timer entry point is not an empty state`() {
        assertThat(count("添加定时器")).isEqualTo(-1)
        assertThat(count("添加定时器", "取消")).isEqualTo(-1)
    }

    @Test fun `running cards in the viewport never become a total`() {
        assertThat(count("定时器", "30 分钟", "29 分钟 41 秒钟", "取消", "暂停")).isEqualTo(-1)
        assertThat(count("5 分钟", "4 分钟 10 秒钟", "取消", "暂停",
            "10 分钟", "9 分钟 01 秒钟", "取消", "继续")).isEqualTo(-1)
    }

    @Test fun `a truncated tree cannot claim a zero, but still trusts an explicit statement`() {
        // A header is a sentence the page states, so clipping the tree does not weaken it.
        assertThat(ClockControl.timerCountFrom(listOf("3 个定时器正在运行"), truncated = true)).isEqualTo(3)
        assertThat(ClockControl.timerCountFrom(listOf("没有正在运行的定时器"), truncated = true)).isEqualTo(-1)
        assertThat(ClockControl.timerCountFrom(listOf("0 个定时器正在运行"), truncated = true)).isEqualTo(0)
    }

    @Test fun `a page with neither statement nor card is unknown`() {
        assertThat(count("时钟", "闹钟")).isEqualTo(-1)
        assertThat(count()).isEqualTo(-1)
    }

    // ── lower bounds: safe to speak as "at least N" ──

    @Test fun `one fully attributed card is a lower bound of one`() {
        assertThat(lowerBound("定时器", "30 分钟", "29 分钟 41 秒钟", "取消", "暂停")).isEqualTo(1)
    }

    @Test fun `two fully attributed cards are a lower bound of two`() {
        assertThat(lowerBound("5 分钟", "4 分钟 10 秒钟", "取消", "暂停",
            "10 分钟", "9 分钟 01 秒钟", "取消", "继续")).isEqualTo(2)
    }

    @Test fun `cancel buttons without any duration marker have no lower bound`() {
        assertThat(lowerBound("取消", "暂停")).isEqualTo(-1)
    }

    @Test fun `an empty or unrecognised viewport has no lower bound`() {
        assertThat(lowerBound("小时", "分钟", "秒", "开始")).isEqualTo(-1)
        assertThat(lowerBound()).isEqualTo(-1)
    }
}
