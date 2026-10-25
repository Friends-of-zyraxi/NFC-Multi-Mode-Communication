package io.github.zyraxi21.nfc.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.microsoft.fluentui.theme.ThemeMode
import com.microsoft.fluentui.theme.token.AliasTokens
import com.microsoft.fluentui.theme.token.FluentAliasTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * 深色模式可读性回归测试。
 *
 * 之前出问题的两处根因都在"颜色不随主题解析"上，这类问题编译期发现不了，
 * 只有把对比度写成断言才能挡住回归：
 *
 * 1. 顶栏底色取了 `brandColor[Color80]`。深色模式下 Fluent 真正该用的是
 *    `BrandBackground1`（dark = Color100 = #479EF5，亮蓝），白字压上去只有 2.9:1。
 * 2. 主题层用 `if (dark) Color.White else Color.Black` 提供前景色，
 *    深色模式下文字与深色背景撞在一起。
 *
 * 断言标准取自 WCAG 2.1：正文 AA 需要 4.5:1，大号文字与图形边界需要 3:1。
 * 断言直接读取**编译产物里的 Fluent alias token**，因此 Fluent 依赖升级导致
 * 色值变化时测试会立刻失败，而不是等到线上截图才发现。
 */
class ThemeContrastTest {

    // ---------------------------------------------------------------
    // 工具：复用生产代码里的同一套 WCAG 亮度公式，避免测试与实现各写一份而漂移
    // ---------------------------------------------------------------

    private fun contrast(foreground: Color, background: Color): Double {
        val l1 = relativeLuminance(foreground)
        val l2 = relativeLuminance(background)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    private val tokens = AliasTokens()

    private fun isDark(mode: ThemeMode) = mode == ThemeMode.Dark

    private fun neutralBackground(
        token: FluentAliasTokens.NeutralBackgroundColorTokens,
        mode: ThemeMode
    ): Color = tokens.neutralBackgroundColor[token].let { if (isDark(mode)) it.dark else it.light }

    private fun neutralForeground(
        token: FluentAliasTokens.NeutralForegroundColorTokens,
        mode: ThemeMode
    ): Color = tokens.neutralForegroundColor[token].let { if (isDark(mode)) it.dark else it.light }

    private fun brandBackground(
        token: FluentAliasTokens.BrandBackgroundColorTokens,
        mode: ThemeMode
    ): Color = tokens.brandBackgroundColor[token].let { if (isDark(mode)) it.dark else it.light }

    private fun brandForeground(
        token: FluentAliasTokens.BrandForegroundColorTokens,
        mode: ThemeMode
    ): Color = tokens.brandForegroundColor[token].let { if (isDark(mode)) it.dark else it.light }

    private fun dangerForeground(mode: ThemeMode): Color =
        tokens.errorAndStatusColor[FluentAliasTokens.ErrorAndStatusColorTokens.DangerForeground1]
            .let { if (isDark(mode)) it.dark else it.light }

    private fun canvas(mode: ThemeMode) =
        neutralBackground(FluentAliasTokens.NeutralBackgroundColorTokens.CanvasBackground, mode)

    private fun surface(mode: ThemeMode) =
        neutralBackground(FluentAliasTokens.NeutralBackgroundColorTokens.Background1, mode)

    private fun elevatedSurface(mode: ThemeMode) =
        neutralBackground(FluentAliasTokens.NeutralBackgroundColorTokens.Background3, mode)

    private fun textPrimary(mode: ThemeMode) =
        neutralForeground(FluentAliasTokens.NeutralForegroundColorTokens.Foreground1, mode)

    private fun textSecondary(mode: ThemeMode) =
        neutralForeground(FluentAliasTokens.NeutralForegroundColorTokens.Foreground2, mode)

    private fun textTertiary(mode: ThemeMode) =
        neutralForeground(FluentAliasTokens.NeutralForegroundColorTokens.Foreground3, mode)

    private fun assertContrast(
        label: String,
        foreground: Color,
        background: Color,
        minimum: Double
    ) {
        val ratio = contrast(foreground, background)
        assertTrue(
            "$label 对比度 ${"%.2f".format(ratio)}:1 低于要求的 $minimum:1" +
                "（前景 ${foreground.toHex()}，背景 ${background.toHex()}）",
            ratio >= minimum
        )
    }

    private fun Color.toHex(): String = "#%08X".format(toArgb())

    // ---------------------------------------------------------------
    // 浅色模式
    // ---------------------------------------------------------------

    @Test
    fun `浅色模式下正文与辅助文字都满足 WCAG AA`() {
        val backgrounds = listOf(
            "画布" to canvas(ThemeMode.Light),
            "表面" to surface(ThemeMode.Light),
            "顶栏" to elevatedSurface(ThemeMode.Light)
        )
        for ((name, background) in backgrounds) {
            assertContrast("主前景 / $name", textPrimary(ThemeMode.Light), background, 4.5)
            // AppTheme.textHint 用的就是次级前景，小号说明文字必须落在这一档
            assertContrast("次级前景 / $name", textSecondary(ThemeMode.Light), background, 4.5)
            assertContrast("危险前景 / $name", dangerForeground(ThemeMode.Light), background, 4.5)
        }
    }

    @Test
    fun `三级前景在浅色模式下只够做装饰元素`() {
        // Fluent 的 NeutralForeground3 浅色值是 #808080，压在 #F5F5F5 上只有 3.6:1，
        // 达不到 AA 的 4.5:1。这条断言把这个事实固定下来：如果哪天有人想用它渲染
        // 小号正文，先得让这里失败，从而被迫重新评估。
        for ((name, background) in listOf(
            "画布" to canvas(ThemeMode.Light),
            "表面" to surface(ThemeMode.Light)
        )) {
            val ratio = contrast(textTertiary(ThemeMode.Light), background)
            assertTrue(
                "三级前景 / $name 的对比度变为 ${"%.2f".format(ratio)}:1，已满足 AA，" +
                    "此时可以放宽 AppTheme.textTertiary 的使用限制",
                ratio < 4.5
            )
            assertContrast("三级前景（装饰下限）/ $name", textTertiary(ThemeMode.Light), background, 3.0)
        }
    }

    @Test
    fun `浅色模式下顶栏与底栏能和画布区分开`() {
        // 顶栏/底栏是白色，画布是 #F5F5F5，靠 1dp 描边分隔；
        // 这里保证两者确实是不同的颜色，描边才有意义。
        assertTrue(
            "浅色模式顶栏与画布颜色相同，将无法区分层级",
            elevatedSurface(ThemeMode.Light) != canvas(ThemeMode.Light)
        )
    }

    // ---------------------------------------------------------------
    // 深色模式
    // ---------------------------------------------------------------

    @Test
    fun `深色模式下正文与辅助文字都满足 WCAG AA`() {
        val backgrounds = listOf(
            "画布" to canvas(ThemeMode.Dark),
            "表面" to surface(ThemeMode.Dark),
            "顶栏" to elevatedSurface(ThemeMode.Dark)
        )
        for ((name, background) in backgrounds) {
            assertContrast("主前景 / $name", textPrimary(ThemeMode.Dark), background, 4.5)
            assertContrast("次级前景 / $name", textSecondary(ThemeMode.Dark), background, 4.5)
            assertContrast("三级前景 / $name", textTertiary(ThemeMode.Dark), background, 4.5)
            assertContrast("危险前景 / $name", dangerForeground(ThemeMode.Dark), background, 4.5)
        }
    }

    @Test
    fun `深色模式顶栏必须是深色而不是品牌亮蓝`() {
        val topBar = elevatedSurface(ThemeMode.Dark)
        assertTrue(
            "深色模式顶栏亮度 ${"%.3f".format(relativeLuminance(topBar))} 过高，" +
                "会导致顶栏刺眼（${topBar.toHex()}）",
            relativeLuminance(topBar) < 0.10
        )
        // 顶栏不能直接使用 BrandBackground1：Color100 是亮蓝，白字压上去仅约 2.9:1
        val brandOnTopBar = contrast(
            neutralForeground(
                FluentAliasTokens.NeutralForegroundColorTokens.ForegroundLightStatic,
                ThemeMode.Dark
            ),
            brandBackground(FluentAliasTokens.BrandBackgroundColorTokens.BrandBackground1, ThemeMode.Dark)
        )
        assertTrue(
            "该用例的前提已失效：BrandBackground1 在深色模式下不再是亮色（对比度 " +
                "${"%.2f".format(brandOnTopBar)}:1）",
            brandOnTopBar < 4.5
        )
        assertContrast(
            "顶栏标题 / 顶栏底色",
            textPrimary(ThemeMode.Dark),
            topBar,
            4.5
        )
    }

    /**
     * 顶栏采用"浅色模式品牌色 + 白字 / 深色模式中性色 + 浅字"的策略
     * （与本工程 `AppTheme.brandSurface` 一致，也与 Fluent 官方 `AppBarTokens`
     * 对 `FluentStyle.Brand` 的处理一致）。
     *
     * 这条用例同时守住两件事：
     * 1. 浅色模式的品牌底色必须够深，白字才读得出来；
     * 2. 深色模式**不能**继续用品牌底色——那里的 `BrandBackground1` 是亮色，白字会糊掉。
     */
    @Test
    fun `顶栏在两种模式下都能读出文字`() {
        val white = neutralForeground(
            FluentAliasTokens.NeutralForegroundColorTokens.ForegroundLightStatic,
            ThemeMode.Light
        )

        // 浅色模式：白字压品牌底色
        assertContrast(
            "顶栏白字 / 浅色品牌底色",
            white,
            brandBackground(FluentAliasTokens.BrandBackgroundColorTokens.BrandBackground1, ThemeMode.Light),
            4.5
        )

        // 深色模式：品牌底色会变成亮色，白字不可读——这正是必须改用中性色的原因
        val brandDark = brandBackground(
            FluentAliasTokens.BrandBackgroundColorTokens.BrandBackground1,
            ThemeMode.Dark
        )
        val whiteOnBrandDark = contrast(white, brandDark)
        assertTrue(
            "该用例的前提已失效：深色模式品牌底色 ${brandDark.toHex()} 上的白字对比度" +
                "已达 ${"%.2f".format(whiteOnBrandDark)}:1，可以重新考虑直接用品牌色做顶栏",
            whiteOnBrandDark < 4.5
        )

        // 深色模式实际使用的顶栏底色：中性表面 + 主前景
        assertContrast(
            "顶栏浅字 / 深色中性顶栏",
            textPrimary(ThemeMode.Dark),
            elevatedSurface(ThemeMode.Dark),
            4.5
        )
    }

    @Test
    fun `深色模式底栏未选中项背景必须与容器同色`() {
        // Fluent TabItem 未选中项的背景是 NeutralBackground1；
        // 底栏容器若使用别的颜色，深色模式下会在两侧露出色带。
        val tabBackground = neutralBackground(
            FluentAliasTokens.NeutralBackgroundColorTokens.Background1,
            ThemeMode.Dark
        )
        assertEquals(
            "底栏容器色与 TabItem 背景色不一致（容器 ${surface(ThemeMode.Dark).toHex()}，" +
                "TabItem ${tabBackground.toHex()}）",
            surface(ThemeMode.Dark),
            tabBackground
        )
        assertContrast(
            "底栏未选中项文字 / 底栏背景",
            textSecondary(ThemeMode.Dark),
            tabBackground,
            4.5
        )
        assertContrast(
            "底栏选中项图标 / 底栏背景",
            textPrimary(ThemeMode.Dark),
            tabBackground,
            4.5
        )
    }

    @Test
    fun `Snackbar 底色与文字在两种模式下都能读出`() {
        // Fluent 原生 Accent 样式使用品牌浅底和品牌文字。
        val snackbarSurfaceLight = brandBackground(
            FluentAliasTokens.BrandBackgroundColorTokens.BrandBackgroundTint,
            ThemeMode.Light
        )
        val snackbarSurfaceDark = brandBackground(
            FluentAliasTokens.BrandBackgroundColorTokens.BrandBackgroundTint,
            ThemeMode.Dark
        )

        assertContrast(
            "Snackbar 文字 / Snackbar 底色（浅色）",
            brandForeground(FluentAliasTokens.BrandForegroundColorTokens.BrandForegroundTint, ThemeMode.Light),
            snackbarSurfaceLight,
            4.5
        )
        assertContrast(
            "Snackbar 文字 / Snackbar 底色（深色）",
            brandForeground(FluentAliasTokens.BrandForegroundColorTokens.BrandForegroundTint, ThemeMode.Dark),
            snackbarSurfaceDark,
            4.5
        )

        // Snackbar 浮在画布之上，两者必须有可分辨的色差，否则看不出是浮层
        assertTrue(
            "浅色模式 Snackbar 底色 ${snackbarSurfaceLight.toHex()} 与画布" +
                "${canvas(ThemeMode.Light).toHex()} 过于接近",
            snackbarSurfaceLight != canvas(ThemeMode.Light)
        )
        assertTrue(
            "深色模式 Snackbar 底色 ${snackbarSurfaceDark.toHex()} 与画布" +
                "${canvas(ThemeMode.Dark).toHex()} 过于接近",
            snackbarSurfaceDark != canvas(ThemeMode.Dark)
        )
    }

    // ---------------------------------------------------------------
    // 品牌色阶
    // ---------------------------------------------------------------

    @Test
    fun `品牌强调色在两种模式下都能在对应底色上读出`() {
        // AppTheme.brand 取 BrandForeground1：浅色 Color80、深色 Color100
        assertContrast(
            "品牌强调色 / 浅色画布",
            brandForeground(FluentAliasTokens.BrandForegroundColorTokens.BrandForeground1, ThemeMode.Light),
            canvas(ThemeMode.Light),
            4.5
        )
        assertContrast(
            "品牌强调色 / 深色画布",
            brandForeground(FluentAliasTokens.BrandForegroundColorTokens.BrandForeground1, ThemeMode.Dark),
            canvas(ThemeMode.Dark),
            4.5
        )
    }

    /**
     * Monet 动态取色用的品牌色展开算法。
     *
     * 这些种子覆盖各种色相与明度，包括浅黄（最容易把白字冲掉的极端情况）。
     */
    private val seedCandidates = listOf(
        0xFF0F6CBD.toInt(), // Fluent 默认蓝
        0xFF6750A4.toInt(), // Material 基线紫
        0xFF386A20.toInt(), // 深绿
        0xFFB3261E.toInt(), // 红
        0xFFFFD54F.toInt(), // 浅黄（极端亮）
        0xFF001E30.toInt(), // 极深海军蓝
        0xFF9A9A9A.toInt(), // 中性灰
        0xFFFFFFFF.toInt(), // 纯白（极端）
        0xFF000000.toInt()  // 纯黑（极端）
    )

    @Test
    fun `任意种子色展开出的品牌色阶都是不透明的`() {
        for (seed in seedCandidates) {
            val ramp = generateBrandRamp(seed)
            assertEquals("色阶必须有 16 级", 16, ramp.size)
            for ((token, color) in ramp) {
                assertEquals(
                    "种子 ${"#%08X".format(seed)} 的 $token 不是不透明色",
                    1f,
                    color.alpha,
                    0.0001f
                )
            }
        }
    }

    @Test
    fun `品牌色阶整体从深到浅且相邻级别可区分`() {
        for (seed in seedCandidates) {
            val ramp = generateBrandRamp(seed)
            val luminances = FluentAliasTokens.BrandColorTokens.entries.map { token ->
                relativeLuminance(ramp.getValue(token))
            }
            assertTrue(
                "种子 ${"#%08X".format(seed)} 的色阶 Color10 应比 Color160 深",
                luminances.first() < luminances.last()
            )
            val steps = luminances.distinct().size
            assertTrue(
                "种子 ${"#%08X".format(seed)} 的色阶只有 $steps 个不同亮度，无法区分层级",
                steps >= 10
            )
        }
    }

    /**
     * 色阶按官方 Fluent 色阶的亮度分布生成，这是本工程与官方 `getBrandRamp` 的关键差别。
     *
     * 官方算法把种子颜色按比例混向黑/白，种子亮度一旦偏离中段（系统动态色很容易如此），
     * 得到的 Color80 / Color100 就会落到无法阅读的亮度上。改为逐级对齐官方亮度后，
     * 无论种子是什么，色阶在明暗两端的行为都与官方品牌色一致。
     */
    @Test
    fun `品牌色阶逐级命中官方 Fluent 色阶的亮度`() {
        // 与 Theme.kt 中 OFFICIAL_RAMP_LUMINANCE 相同的官方色阶亮度
        val official = doubleArrayOf(
            0.0078, 0.0154, 0.0251, 0.0401,
            0.0588, 0.0834, 0.1077, 0.1450,
            0.2278, 0.3239, 0.3832, 0.4450,
            0.5377, 0.6470, 0.7565, 0.8879
        )
        val tokens = FluentAliasTokens.BrandColorTokens.entries
        // 目标亮度超出该色相/饱和度可达上限时（黄色系的高亮端）会停在最亮端，
        // 因此只要求"不高于目标 + 容差"，其余级别要求贴近目标。
        val tolerance = 0.02
        for (seed in seedCandidates) {
            val ramp = generateBrandRamp(seed)
            tokens.forEachIndexed { index, token ->
                val actual = relativeLuminance(ramp.getValue(token))
                val target = official[index]
                assertTrue(
                    "种子 ${"#%08X".format(seed)} 的 $token 亮度 ${"%.4f".format(actual)} " +
                        "与官方基准 ${"%.4f".format(target)} 偏差过大",
                    actual <= target + tolerance
                )
                if (index <= 10) {
                    // 低中段任何色相都能达到目标亮度，必须精确命中
                    assertTrue(
                        "种子 ${"#%08X".format(seed)} 的 $token 亮度 " +
                            "${"%.4f".format(actual)} 未命中官方基准 ${"%.4f".format(target)}",
                        kotlin.math.abs(actual - target) < tolerance
                    )
                }
            }
        }
    }

    @Test
    fun `品牌色阶的 Color80 与 Color100 亮度不随种子变化`() {
        // 这是"换了系统主题色之后可读性不变"的根本保证。
        // 容差取 0.005：颜色是 8 位量化的，命中目标亮度后仍有约 0.002 的抖动。
        val reference = generateBrandRamp(seedCandidates.first())
        val ref80 = relativeLuminance(reference.getValue(FluentAliasTokens.BrandColorTokens.Color80))
        val ref100 = relativeLuminance(reference.getValue(FluentAliasTokens.BrandColorTokens.Color100))
        for (seed in seedCandidates.drop(1)) {
            val ramp = generateBrandRamp(seed)
            val l80 = relativeLuminance(ramp.getValue(FluentAliasTokens.BrandColorTokens.Color80))
            val l100 = relativeLuminance(ramp.getValue(FluentAliasTokens.BrandColorTokens.Color100))
            assertEquals(
                "种子 ${"#%08X".format(seed)} 的 Color80 亮度偏离基准",
                ref80, l80, 0.005
            )
            assertEquals(
                "种子 ${"#%08X".format(seed)} 的 Color100 亮度偏离基准",
                ref100, l100, 0.005
            )
        }
    }

    @Test
    fun `Monet 品牌色阶在实际用到的两端都满足对比度`() {
        // AppTheme.brand 取的是 BrandForeground1：
        // 浅色模式 → Color80，深色模式 → Color100。
        // 这两个色阶值必须分别在浅色表面与深色表面上都能读出来，
        // 否则换了系统主题色之后，底栏选中态、状态提示文字就会看不清。
        val lightSurface = surface(ThemeMode.Light)
        val darkSurface = surface(ThemeMode.Dark)
        for (seed in seedCandidates) {
            val ramp = generateBrandRamp(seed)
            val label = "种子 ${"#%08X".format(seed)}"

            val color80 = ramp.getValue(FluentAliasTokens.BrandColorTokens.Color80)
            val lightRatio = contrast(color80, lightSurface)
            assertTrue(
                "$label 展开出的 Color80 ${color80.toHex()} 在浅色表面上对比度仅 " +
                    "${"%.2f".format(lightRatio)}:1，低于图形元素要求的 3:1",
                lightRatio >= 3.0
            )

            val color100 = ramp.getValue(FluentAliasTokens.BrandColorTokens.Color100)
            val darkRatio = contrast(color100, darkSurface)
            assertTrue(
                "$label 展开出的 Color100 ${color100.toHex()} 在深色表面上对比度仅 " +
                    "${"%.2f".format(darkRatio)}:1，低于正文要求的 4.5:1",
                darkRatio >= 4.5
            )
        }
    }
}
