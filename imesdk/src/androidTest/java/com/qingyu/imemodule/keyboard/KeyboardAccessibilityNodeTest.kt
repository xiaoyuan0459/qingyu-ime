package com.qingyu.imemodule.keyboard

import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.qingyu.imemodule.R
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.entity.keyboard.SoftKey
import com.qingyu.imemodule.manager.InputModeSwitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 设备级：验证 TalkBack 读到的虚拟节点内容。
 *
 * 键盘是自绘画布，无障碍全靠 [KeyboardAccessibilityHelper] 生成虚拟节点树；这些文案在界面里
 * 看不见，只能靠断言守住：谁把「返回键盘」改成「删除」、或者让禁用键变成可点，这里先红。
 *
 * 节点回调是 public 的（见 helper 的类注释），测的是「填进去的节点长什么样」，与
 * `BaseKeyboardView` 把它们挂给 `ViewCompat.setAccessibilityDelegate` 是同一套数据。
 *
 * 全部键位为合成数据，不涉及用户输入。
 */
class KeyboardAccessibilityNodeTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun warmUpSingletons() {
        // 长按提示要查 AppPrefs / ThemeManager，这两条链的静态初始化依赖 Launcher 的 context。
        // 真机由 IME 服务完成，测试里补这一步，否则会撞 lateinit 未初始化。
        Launcher.instance.initData(context)
    }

    private fun key(code: Int, label: String, index: Int) = SoftKey(code, label).apply {
        mLeft = index * 100
        mRight = mLeft + 100
        mTop = 0
        mBottom = 100
    }

    private fun helperFor(
        keys: List<SoftKey>,
        enabled: (Int) -> Boolean = { true },
    ): KeyboardAccessibilityHelper {
        val host = View(context)
        host.layout(0, 0, 400, 100)
        return KeyboardAccessibilityHelper(host, { keys }, { _, _ -> true }, { false }, enabled)
    }

    @Test
    fun visibleVirtualViewsFollowKeyList() {
        val helper = helperFor(listOf(key(KeyEvent.KEYCODE_DEL, "⌫", 0), key(KeyEvent.KEYCODE_N, "n", 1)))

        val ids = mutableListOf<Int>()
        helper.getVisibleVirtualViews(ids)

        assertEquals(listOf(0, 1), ids)
    }

    @Test
    fun virtualKeysExposeReadableLabels() {
        val helper = helperFor(
            listOf(
                key(KeyEvent.KEYCODE_DEL, "⌫", 0),
                key(InputModeSwitcher.USER_KEYCODE_NUMBER, "123", 1),
                key(KeyEvent.KEYCODE_N, "n", 2),
            )
        )

        val delete = AccessibilityNodeInfoCompat.obtain()
        helper.onPopulateNodeForVirtualView(0, delete)
        assertEquals(context.getString(R.string.accessibility_key_delete), delete.contentDescription)
        assertTrue(delete.isClickable)

        val number = AccessibilityNodeInfoCompat.obtain()
        helper.onPopulateNodeForVirtualView(1, number)
        assertEquals(context.getString(R.string.accessibility_key_number), number.contentDescription)

        // 字母键走键面标签：读出来的必须就是键上那个字母，而不是 KEYCODE_N 这种内部名。
        val letter = AccessibilityNodeInfoCompat.obtain()
        helper.onPopulateNodeForVirtualView(2, letter)
        assertEquals("n", letter.contentDescription)
    }

    /** 禁用的键不该被读成可点击，否则 TalkBack 用户会被引导去点一个没反应的键。 */
    @Test
    fun disabledKeysAreNotClickable() {
        val helper = helperFor(listOf(key(KeyEvent.KEYCODE_ENTER, "←", 0)), enabled = { false })

        val node = AccessibilityNodeInfoCompat.obtain()
        helper.onPopulateNodeForVirtualView(0, node)

        assertEquals(false, node.isClickable)
        assertEquals(false, node.isEnabled)
    }

    /** 空格键长按说话时，长按动作要念出「语音输入」，不能只报「空格」。 */
    @Test
    fun longPressActionIsAnnounced() {
        val helper = helperFor(listOf(key(KeyEvent.KEYCODE_SPACE, "空格", 0)))

        val node = AccessibilityNodeInfoCompat.obtain()
        helper.onPopulateNodeForVirtualView(0, node)

        assertEquals(context.getString(R.string.accessibility_key_space), node.contentDescription)
        assertNotNull(node.actionList.any { it.label != null })
    }
}
