package com.holderzone.hardware.scale

import android.content.Context
import com.holderzone.hardware.scale.core.DefaultScaleFacade
import com.holderzone.hardware.scale.driver.jw.JwScaleDriverFactory
import com.holderzone.hardware.scale.driver.ly.LyScaleDriverFactory
import com.holderzone.hardware.scale.driver.sohe.SoheScaleDriverFactory

/**
 * Scale SDK facade 工厂。
 *
 * 对外只暴露统一创建入口，避免宿主直接感知内部 driver 组合细节。
 * 每次调用都会返回一个新的 [ScaleFacade] 实例，用于管理一次独立的称重会话。
 */
object ScaleFacadeFactory {

    /**
     * 创建一个新的 [ScaleFacade]。
     *
     * 返回的新实例默认注册 JW、LY 与 SOHE 三个驱动工厂，
     * 后续的自动探测和显式厂商选择都由 facade 内部状态机统一处理。
     */
    fun create(context: Context): ScaleFacade {
        return DefaultScaleFacade(
            context = context.applicationContext,
            driverFactories = listOf(
                JwScaleDriverFactory(),
                LyScaleDriverFactory(),
                SoheScaleDriverFactory(),
            ),
        )
    }
}
