package com.qingyu.imemodule.ui.navigation

import android.os.Bundle
import androidx.annotation.IdRes
import androidx.navigation.NavController

/**
 * 设置页统一的导航入口。
 *
 * 除了集中维护转场，这里还会忽略已经失效的 action 和指向当前页的重复点击，
 * 避免用户快速连点列表项时重复创建 Fragment。
 * 转场保持即时切换：参考输入法的二级页进出没有可见动画，
 * 带缓动的 popEnter 会让返回首页时页面"缓慢出现"。
 */
fun NavController.navigateWithSettingsMotion(
    @IdRes routeId: Int,
    args: Bundle? = null,
): Boolean {
    val current = currentDestination ?: return false
    val destinationId = current.getAction(routeId)?.destinationId
        ?: graph.findNode(routeId)?.id
        ?: return false
    if (current.id == destinationId) return false

    navigate(routeId, args)
    return true
}
