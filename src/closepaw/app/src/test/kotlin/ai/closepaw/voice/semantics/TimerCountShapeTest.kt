package ai.closepaw.voice.semantics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Device shapes measured this round on Samsung Clock 12.5.60.27 / One UI 9 (SM-F731U1):
 *
 *  - empty timer tab: hour/minute/second pickers (`小时` `分钟` `秒`), a `开始` button and presets —
 *    there is NO `添加定时器` text and NO `N 个定时器正在运行` header, which is why the old parser
 *    returned -1 (reported as "打不开页面") on a perfectly readable page;
 *  - one running timer: `30 分钟` (original duration, no `初始时长` prefix), `29 分钟 41 秒钟`
 *    (remaining), and `取消` + `暂停` buttons.
 *
 * The count must never be guessed: an unrecognised shape stays -1 (unknown), and a running card is
 * only counted when its duration marker and controls are both present.
 */
class TimerCountShapeTest {
    private fun count(vararg texts: String) = ClockControl.timerCountFrom(texts.toList())

    @Test fun `the create picker is the empty state on this device`() {
        assertThat(count("定时器", "小时", "分钟", "秒", "开始", "00:10:00", "00:15:00", "00:30:00")).isEqualTo(0)
    }

    @Test fun `one running card with duration and controls counts as one`() {
        assertThat(count("定时器", "30 分钟", "29 分钟 41 秒钟", "取消", "暂停")).isEqualTo(1)
    }

    @Test fun `two running cards count as two`() {
        assertThat(count("5 分钟", "4 分钟 10 秒钟", "取消", "暂停",
            "10 分钟", "9 分钟 01 秒钟", "取消", "继续")).isEqualTo(2)
    }

    @Test fun `a legacy header still wins`() {
        assertThat(count("2 个定时器正在运行", "取消")).isEqualTo(2)
        assertThat(count("添加定时器", "取消")).isEqualTo(0)
    }

    @Test fun `cancel buttons without any duration marker stay unknown`() {
        assertThat(count("取消", "暂停")).isEqualTo(-1)
    }

    @Test fun `a page with neither picker nor card is unknown`() {
        assertThat(count("时钟", "闹钟")).isEqualTo(-1)
        assertThat(count()).isEqualTo(-1)
    }
}
