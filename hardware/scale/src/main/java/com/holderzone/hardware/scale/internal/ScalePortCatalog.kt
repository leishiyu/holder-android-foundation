package com.holderzone.hardware.scale.internal

import com.holderzone.hardware.scale.ScalePortConfig
import com.holderzone.hardware.scale.ScaleVendor

/**
 * Scale SDK 内置串口候选表。
 *
 * 这里维护的是“SDK 默认愿意自动尝试的串口列表”，
 * 目的是让业务层在常规机型上不再手填串口号。
 *
 * 这些列表并不追求覆盖所有现场接线，只覆盖当前仓库里已经出现过、
 * 且在留样柜设备上相对常见的串口约定；遇到改线机器时，仍然可以通过
 * `ScaleConfig.portOverride` 显式覆盖。
 */
object ScalePortCatalog {

    /**
     * 返回指定厂商的默认串口探测顺序。
     */
    fun defaultCandidatesFor(vendor: ScaleVendor): List<ScalePortConfig> {
        return when (vendor) {
            ScaleVendor.JW -> jwDefaultCandidates
            ScaleVendor.LY -> lyDefaultCandidates
            ScaleVendor.SOHE -> soheDefaultCandidates
        }
    }

    /**
     * JW 称重在历史项目中出现过的常见串口顺序。
     *
     * 优先把旧项目中真实探测过的 `/dev/ttyS8` 放在第一位，
     * 其余端口按留样柜现场较常见的顺序继续兜底。
     */
    private val jwDefaultCandidates: List<ScalePortConfig> = listOf(
        ScalePortConfig("/dev/ttyS8", 9_600),
        ScalePortConfig("/dev/ttyS1", 9_600),
        ScalePortConfig("/dev/ttyS2", 9_600),
        ScalePortConfig("/dev/ttyS3", 9_600),
    )

    /**
     * LY 串口称重默认候选表。
     *
     * 当前代码基线中没有足够多的历史样本，因此先使用较保守的常见串口顺序。
     * 如果现场机型不匹配，业务侧可以显式传入 `portOverride`。
     */
    private val lyDefaultCandidates: List<ScalePortConfig> = listOf(
        ScalePortConfig("/dev/ttyS1", 9_600),
        ScalePortConfig("/dev/ttyS3", 9_600),
        ScalePortConfig("/dev/ttyS2", 9_600),
    )

    /**
     * 首衡暂不猜测现场串口路径。
     *
     * 首衡协议文档只描述了 TTL/RS232 和数据格式，没有给出设备在目标终端上的
     * 固定串口路径。调用方应通过 `ScaleConfig.portOverride` 显式传入端口和波特率。
     */
    private val soheDefaultCandidates: List<ScalePortConfig> = emptyList()
}
