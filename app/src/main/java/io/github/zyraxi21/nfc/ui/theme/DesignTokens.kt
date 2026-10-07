package io.github.zyraxi21.nfc.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.microsoft.fluentui.theme.FluentTheme
import com.microsoft.fluentui.theme.ThemeMode
import com.microsoft.fluentui.theme.token.FluentAliasTokens
import com.microsoft.fluentui.theme.token.FluentColor
import com.microsoft.fluentui.theme.token.FluentGlobalTokens
import com.microsoft.fluentui.theme.token.StateBrush
import com.microsoft.fluentui.theme.token.StateColor
import com.microsoft.fluentui.theme.token.controlTokens.BasicCardInfo
import com.microsoft.fluentui.theme.token.controlTokens.BasicCardTokens
import com.microsoft.fluentui.theme.token.controlTokens.ButtonInfo
import com.microsoft.fluentui.theme.token.controlTokens.ButtonStyle
import com.microsoft.fluentui.theme.token.controlTokens.ButtonTokens
import com.microsoft.fluentui.theme.token.controlTokens.MenuInfo
import com.microsoft.fluentui.theme.token.controlTokens.MenuTokens
import com.microsoft.fluentui.theme.token.controlTokens.TextFieldInfo
import com.microsoft.fluentui.theme.token.controlTokens.TextFieldTokens
import com.microsoft.fluentui.tokenized.controls.BasicCard
import com.microsoft.fluentui.tokenized.controls.Button
import com.microsoft.fluentui.tokenized.controls.TextField
import com.microsoft.fluentui.tokenized.menu.Menu
import com.microsoft.fluentui.tokenized.notification.SnackbarState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fluent 2 设计令牌在本工程的唯一入口。
 *
 * 所有颜色都必须来自 [FluentTheme] 的 alias token，或由 [AppTheme] 暴露的语义色。
 * 禁止在界面代码里写 `Color.Black` / `Color.White` / `Color.Gray` / `Color.Red`
 * 这类"固定色"——它们不会随浅色/深色模式切换，是深色模式下文字与背景融为一体的根因。
 *
 * 依据：
 * - Fluent 2 Color：颜色分 neutral / shared / brand 三个色板，深色模式使用反转后的
 *   neutral 色阶，前景色必须用 NeutralForeground* 系列而非固定黑白。
 * - Fluent 2 Layout：全局间距阶梯以 4 为基础单位，Android 侧以 dp 计量；布局需要
 *   明确的外边距（margin）/ 槽宽（gutter），内容不得贴屏。
 * - Fluent 2 Typography：字号/行高/字重三件套必须成对取用，不允许只改 fontSize。
 * - Fluent 2 Elevation：阴影按 2/4/8/16/28/64 的模糊半径分档，卡片用 shadow4，
 *   对话框用 shadow64。
 * - Fluent 2 Shapes：控件 4dp 圆角，卡片/对话框 8dp 圆角。
 * - Fluent 2 Motion：交互反馈使用快时长（100~200ms），页面切换使用中等时长（300ms）。
 * - Android edge-to-edge：系统栏区域由内容自行通过 WindowInsets 处理，窗口本身
 *   不再绘制系统栏底色。
 */
object FluentSpacing {
    val none: Dp = 0.dp
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sNudge: Dp = 6.dp
    val s: Dp = 8.dp
    val sPlus: Dp = 10.dp
    val m: Dp = 12.dp
    val mPlus: Dp = 16.dp
    val l: Dp = 20.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 28.dp
    val xxxl: Dp = 32.dp
    val huge: Dp = 40.dp
    val massive: Dp = 48.dp

    /**
     * Snackbar 与底部导航栏之间的间距。
     *
     * Scaffold 会把 `snackbarHost` 紧贴在底栏上沿，不留空隙，视觉上像粘在一起；
     * 这里让出 16dp 让两者分开。
     */
    val snackbarBottomSpacing: Dp = mPlus
}

/**
 * 页面外边距（Fluent 2 Layout 的 margin）。
 *
 * 任何文字、图标、控件的边界都不得越过这条线；它同时决定了顶栏/底栏的左右内边距，
 * 保证所有页面在视觉上共用一个竖向基准。
 */
object PageMetrics {
    /**
     * 手机竖屏外边距。
     *
     * 取 24dp（Fluent size240）而不是 Android 默认的 16dp：16dp 时文字与控件几乎贴到
     * 屏幕边缘，视觉上很挤，也让左右两侧缺少呼吸感。24dp 是 Fluent 在移动端常用的
     * 页面边距档位，同时仍给正文留出足够宽度。
     */
    val compactGutter: Dp = FluentSpacing.xl

    /** 中等宽度（横屏、折叠屏展开、平板竖屏）：再加大到 32dp。 */
    val mediumGutter: Dp = FluentSpacing.xxxl

    /** 大屏（平板横屏、桌面窗口）：40dp 起步，避免内容被拉得过宽。 */
    val expandedGutter: Dp = FluentSpacing.huge

    /** 正文单列最大宽度，保证行长可读（Fluent 2 Layout 的 manuscript grid）。 */
    val maxContentWidth: Dp = 680.dp

    /** 触发宽屏布局的阈值。 */
    val mediumBreakpoint: Dp = 600.dp
    val expandedBreakpoint: Dp = 1000.dp

    /** Android 与 Fluent 共同要求的最小触摸目标，菜单行等高必须满足。 */
    val minTouchTarget: Dp = 48.dp

    /** 按可用宽度选择页面外边距。 */
    fun gutterFor(availableWidth: Dp): Dp = when {
        availableWidth >= expandedBreakpoint -> expandedGutter
        availableWidth >= mediumBreakpoint -> mediumGutter
        else -> compactGutter
    }
}

/** 自定义的页面宽度信息，避免在多处重复 BoxWithConstraints。 */
val LocalPageGutter = staticCompositionLocalOf { PageMetrics.compactGutter }

/**
 * 形状令牌（Fluent 2 Shapes）。
 *
 * 全应用**只有一个圆角值**：16dp（Fluent `CornerRadius160`）。
 * 按钮、输入框、下拉选择框、卡片、菜单浮层、对话框、Snackbar 全部取 [radius]。
 *
 * 保持单一值而不是分档，是因为同一屏里出现 12dp 与 16dp 反而显得不齐；
 * 统一成一个值后，任何新控件只要引用 [radius] 就自动与既有界面一致。
 * 48dp 高的控件用 16dp 圆角仍在矩形控件范围内，不会趋近胶囊形
 * （胶囊形需要半径达到高度的一半，即 24dp）。
 */
object FluentShapes {
    /** 全局唯一的圆角值。 */
    val radius: Dp = FluentGlobalTokens.CornerRadiusTokens.CornerRadius160.value

    /** 兼容旧调用点：控件圆角，等同于 [radius]。 */
    val control: Dp get() = radius

    /** 兼容旧调用点：容器圆角，等同于 [radius]。 */
    val container: Dp get() = radius
}

/** 高度令牌（Fluent 2 Elevation）。 */
object FluentElevation {
    val flat: Dp = FluentGlobalTokens.ShadowTokens.Shadow00.value
    val control: Dp = FluentGlobalTokens.ShadowTokens.Shadow02.value
    val card: Dp = FluentGlobalTokens.ShadowTokens.Shadow04.value
    val raised: Dp = FluentGlobalTokens.ShadowTokens.Shadow08.value
    val flyout: Dp = FluentGlobalTokens.ShadowTokens.Shadow16.value
    val sheet: Dp = FluentGlobalTokens.ShadowTokens.Shadow28.value
    val dialog: Dp = FluentGlobalTokens.ShadowTokens.Shadow64.value
}

/** 动效令牌（Fluent 2 Motion）。 */
object FluentMotion {
    /** 交互反馈（按下、悬停、选中）使用快时长。 */
    const val DURATION_ULTRA_FAST = 50
    const val DURATION_FASTER = 100
    const val DURATION_FAST = 150
    const val DURATION_NORMAL = 200

    /** 中等时长用于元素进出场与页面切换。 */
    const val DURATION_GENTLE = 250
    const val DURATION_SLOW = 300
}

// =======================================================================
// 圆角统一：Fluent 的控件圆角都由各自的 *Tokens 提供，且没有直接传 shape 的参数，
// 因此这里为每个用到的控件准备一个"只改圆角"的令牌实例。
//
// 之所以不用自定义 ControlTokens 整套替换主题：FluentTheme 的文档明确写着
// "若同时显式提供 aliasTokens 与 controlTokens，FluentTheme 不再触发 Fluent Control 的更新"，
// 那会牺牲运行期换肤能力。按控件传 tokens 是官方支持、影响面最小的方式。
// =======================================================================

/** 全局按钮圆角：[FluentShapes.radius]。 */
@Composable
private fun appButtonTokens(): ButtonTokens = remember {
    object : ButtonTokens() {
        @Composable
        override fun cornerRadius(buttonInfo: ButtonInfo): Dp = FluentShapes.radius
    }
}

/** 全局卡片圆角：[FluentShapes.radius]。 */
@Composable
private fun appCardTokens(): BasicCardTokens = remember {
    object : BasicCardTokens() {
        @Composable
        override fun cornerRadius(basicCardInfo: BasicCardInfo): Dp = FluentShapes.radius
    }
}

/** 全局菜单浮层圆角：[FluentShapes.radius]。 */
@Composable
private fun appMenuTokens(): MenuTokens = remember {
    object : MenuTokens() {
        @Composable
        override fun cornerRadius(menuInfo: MenuInfo): Dp = FluentShapes.radius
    }
}

/**
 * 应用统一的 Snackbar 宿主。
 *
 * 为什么不用 Fluent 的 `Snackbar` 组件：
 * 1. 它的圆角在内部**硬编码为 `RoundedCornerShape(8.dp)`**，`SnackBarTokens` 里
 *    没有任何圆角令牌，无法通过 token 改成统一的 16dp；
 * 2. 负责时长与超时逻辑的 `NotificationContainer` 是 `internal`，外部拿不到，
 *    因此也不能靠"自己画外层、复用内层"来绕过。
 *
 * 好在 `SnackbarState.currentSnackbar` 与 `SnackbarMetadata` 都是**公开** API，
 * 于是这里直接用自己的 Surface 渲染，圆角、间距、配色都走本工程的语义令牌。
 * 时长与无障碍超时沿用 Fluent 的 `NotificationDuration.convertToMillis`，行为不退化。
 *
 * 位置由 Scaffold 决定：`snackbarHost` 槽位会被摆放在底栏**上方**
 * （`layoutHeight - snackbarHeight - bottomBarHeight`），因此这里只需再让出
 * 与底栏之间的间距 [snackbarBottomSpacing]，以及页面左右外边距。
 */
@Composable
fun FluentSnackbarHost(state: SnackbarState?) {
    val metadata = state?.currentSnackbar ?: return
    val scope = rememberCoroutineScope()
    val accessibilityManager = LocalAccessibilityManager.current

    // 超时自动关闭：时长换算与 Fluent 实现保持一致，含无障碍推荐时长
    LaunchedEffect(metadata) {
        delay(
            metadata.duration.convertToMillis(
                hasIcon = metadata.icon != null,
                hasAction = metadata.actionText != null,
                accessibilityManager = accessibilityManager
            )
        )
        metadata.timedOut(scope)
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    // 比内容页 gutter（24dp）稍窄，Snackbar 视觉上更宽但仍留边距
                    start = FluentSpacing.mPlus,
                    end = FluentSpacing.mPlus,
                    bottom = FluentSpacing.snackbarBottomSpacing
                ),
            shape = RoundedCornerShape(FluentShapes.radius),
            color = AppTheme.snackbarSurface,
            shadowElevation = FluentElevation.raised
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = FluentSpacing.mPlus,
                        vertical = FluentSpacing.l
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    FluentText(
                        text = metadata.message,
                        style = FluentTextStyle.Body2Strong,
                        color = AppTheme.snackbarText,
                        centered = false
                    )
                    metadata.subTitle?.takeIf { it.isNotBlank() }?.let { subTitle ->
                        FluentText(
                            text = subTitle,
                            style = FluentTextStyle.Body2,
                            color = AppTheme.snackbarText,
                            centered = false
                        )
                    }
                }

                metadata.actionText?.takeIf { it.isNotBlank() }?.let { actionText ->
                    Spacer(modifier = Modifier.width(FluentSpacing.s))
                    Button(
                        onClick = { metadata.clicked(scope) },
                        text = actionText,
                        style = ButtonStyle.TextButton,
                        buttonTokens = appButtonTokens()
                    )
                }
            }
        }
    }
}

/**
 * 统一圆角的应用按钮。
 *
 * 包一层是为了让全应用所有按钮共用一个圆角来源——直接写 `Button(...)` 会拿到 Fluent
 * 默认的 4dp，与本工程的圆角不一致。
 */
@Composable
fun FluentButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Button,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        text = text,
        modifier = modifier,
        style = style,
        enabled = enabled,
        buttonTokens = appButtonTokens()
    )
}

/** 统一圆角的应用卡片。 */
@Composable
fun FluentCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    BasicCard(modifier = modifier, basicCardTokens = appCardTokens(), content = content)
}

/**
 * 统一圆角的应用下拉浮层，并且**宽度跟随触发控件**。
 *
 * Fluent 的 `Menu` 浮层宽度来自内容固有宽度（`MenuContent` 内部是
 * `Modifier.width(IntrinsicSize.Max)`），默认会明显窄于整行宽的触发按钮，视觉上对不齐；
 * 同时它自带的圆角是 8dp。这里同时修掉这两点：[width] 由调用方测量触发控件后传入。
 *
 * @param width 浮层宽度，通常传触发按钮实测宽度；为 `null` 时退回内容固有宽度。
 */
@Composable
fun FluentDropdownMenu(
    opened: Boolean,
    onDismissRequest: () -> Unit,
    width: Dp?,
    content: @Composable () -> Unit
) {
    Menu(
        opened = opened,
        onDismissRequest = onDismissRequest,
        modifier = if (width != null) Modifier.width(width) else Modifier,
        menuTokens = appMenuTokens(),
        content = content
    )
}

/**
 * 下拉菜单里的一行选项。
 *
 * 统一 48dp 最小高度（Fluent 与 Android 共同要求的触摸目标），文字居中。
 * 涟漪色显式来自 [AppTheme.ripple]，不能依赖 `LocalIndication`（原因见该属性的说明）。
 */
@Composable
fun FluentDropdownItem(text: String, onClick: () -> Unit) {
    val rippleColor = AppTheme.ripple
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = ripple(color = rippleColor),
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            )
            .heightIn(min = PageMetrics.minTouchTarget)
            .padding(horizontal = FluentSpacing.mPlus, vertical = FluentSpacing.s),
        contentAlignment = Alignment.Center
    ) {
        FluentText(text = text, style = FluentTextStyle.Body1)
    }
}

/**
 * 下拉选择框（触发按钮 + 浮层），**浮层宽度与触发按钮严格对齐**。
 *
 * 为什么需要专门封装：Fluent 的 `Menu` 浮层宽度取自内容固有宽度，默认会明显窄于
 * `fillMaxWidth()` 的触发按钮，视觉上对不齐。这里用 `onGloballyPositioned` 量出按钮
 * 实际宽度，再把它作为浮层宽度——这样无论屏幕多宽、页面外边距多大都自动对齐，
 * 不需要在任何地方硬编码宽度。
 */
@Composable
fun FluentDropdown(
    text: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<String>,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var triggerWidth by remember { mutableStateOf<Dp?>(null) }
    // 主题/密度取值必须在组合期完成，不能写进 onGloballyPositioned 这类非组合 lambda
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val measured = with(density) { coordinates.size.width.toDp() }
                if (measured != triggerWidth) triggerWidth = measured
            }
    ) {
        FluentButton(
            onClick = { onExpandedChange(true) },
            style = ButtonStyle.OutlinedButton,
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PageMetrics.minTouchTarget)
        )
        FluentDropdownMenu(
            opened = expanded,
            onDismissRequest = { onExpandedChange(false) },
            width = triggerWidth
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, label ->
                    FluentDropdownItem(
                        text = label,
                        onClick = { onOptionSelected(index) }
                    )
                }
            }
        }
    }
}

/** 排版令牌（Fluent 2 Typography 的 12 级字阶）。 */
enum class FluentTextStyle {
    Display,
    LargeTitle,
    Title1,
    Title2,
    Title3,
    Body1Strong,
    Body1,
    Body2Strong,
    Body2,
    Caption1Strong,
    Caption1,
    Caption2;

    private val token: FluentAliasTokens.TypographyTokens
        get() = when (this) {
            Display -> FluentAliasTokens.TypographyTokens.Display
            LargeTitle -> FluentAliasTokens.TypographyTokens.LargeTitle
            Title1 -> FluentAliasTokens.TypographyTokens.Title1
            Title2 -> FluentAliasTokens.TypographyTokens.Title2
            Title3 -> FluentAliasTokens.TypographyTokens.Title3
            Body1Strong -> FluentAliasTokens.TypographyTokens.Body1Strong
            Body1 -> FluentAliasTokens.TypographyTokens.Body1
            Body2Strong -> FluentAliasTokens.TypographyTokens.Body2Strong
            Body2 -> FluentAliasTokens.TypographyTokens.Body2
            Caption1Strong -> FluentAliasTokens.TypographyTokens.Caption1Strong
            Caption1 -> FluentAliasTokens.TypographyTokens.Caption1
            Caption2 -> FluentAliasTokens.TypographyTokens.Caption2
        }

    /**
     * 取出当前主题下的排版。
     *
     * 只返回字号/行高/字重，颜色留给 `BasicText` 通过 `LocalContentColor` 解析，
     * 这样同一套排版在浅色与深色模式下都能获得正确的前景色。
     */
    @Composable
    @ReadOnlyComposable
    fun style(): TextStyle = FluentTheme.aliasTokens.typography[token]
}

/**
 * 语义色板。
 *
 * 每个值都是 Fluent 的 alias token，底层是 `FluentColor(light, dark)` 双值结构，
 * 取值时按当前 [ThemeMode] 解析。深色模式下 neutral 色阶整体反转，
 * 因此不会出现"白底白字"或"黑底黑字"。
 *
 * 界面代码只允许从这里取色，禁止直接写 `Color.Black` / `Color.White` / `Color.Gray`
 * 这类固定色——它们不随主题切换，正是深色模式下文字看不清的根因。
 */
object AppTheme {

    /** 解析一个 Fluent 双值颜色到当前主题模式。 */
    @Composable
    private fun resolve(color: FluentColor): Color =
        color.value(themeMode = FluentTheme.themeMode)

    /** 页面画布底色：浅色 #F5F5F5 / 深色 #141414。 */
    val canvas: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.neutralBackgroundColor[
                FluentAliasTokens.NeutralBackgroundColorTokens.CanvasBackground
            ]
        )

    /** 一级表面（顶栏、底栏）：浅色 #FFFFFF / 深色 #1F1F1F。 */
    val surface: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.neutralBackgroundColor[
                FluentAliasTokens.NeutralBackgroundColorTokens.Background1
            ]
        )

    /** 二级表面：与 [surface] 同色，深色模式下用于贴边容器。 */
    val surfaceAlt: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.neutralBackgroundColor[
                FluentAliasTokens.NeutralBackgroundColorTokens.Background2
            ]
        )

    /** 三级表面：Fluent 顶栏 Neutral 样式的标准底色，浅色 #FFFFFF / 深色 #292929。 */
    val elevatedSurface: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.neutralBackgroundColor[
                FluentAliasTokens.NeutralBackgroundColorTokens.Background3
            ]
        )

    /** 静态深色表面（用于反色提示条）。 */
    val invertedSurface: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.neutralBackgroundColor[
                FluentAliasTokens.NeutralBackgroundColorTokens.BackgroundDarkStatic
            ]
        )

    /** 静态浅色前景（位于 [invertedSurface] 之上）。 */
    val onInvertedSurface: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.neutralForegroundColor[
                FluentAliasTokens.NeutralForegroundColorTokens.ForegroundLightStatic
            ]
        )

    /** 主前景：浅色 #242424 / 深色 #FFFFFF，对 [canvas] 与 [surface] 的对比度均 ≥ 17:1。 */
    val textPrimary: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.neutralForegroundColor[
                FluentAliasTokens.NeutralForegroundColorTokens.Foreground1
            ]
        )

    /** 次级前景：浅色 #616161 / 深色 #D6D6D6，对比度 ≥ 9:1。 */
    val textSecondary: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.neutralForegroundColor[
                FluentAliasTokens.NeutralForegroundColorTokens.Foreground2
            ]
        )

    /**
     * 三级前景。
     *
     * **注意对比度**：浅色模式下 `NeutralForeground3` 是 #808080，压在画布 #F5F5F5 上
     * 只有 3.62:1，达不到 WCAG AA 对正文要求的 4.5:1（这是 Fluent 官方色阶本身的取值）。
     * 因此它只能用于装饰性元素（图标、分隔强调）或大号文字，
     * **不要用它渲染小号说明文字**——那种情况请用 [AppTheme.textSecondary]。
     */
    val textTertiary: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.neutralForegroundColor[
                FluentAliasTokens.NeutralForegroundColorTokens.Foreground3
            ]
        )

    /**
     * 辅助/提示文字。
     *
     * 语义上等同于三级前景，但取 [textSecondary] 的色值，确保小号说明文字也满足
     * WCAG AA 的 4.5:1。版本号、格式提示、占位说明等一律用这个令牌。
     */
    val textHint: Color
        @Composable
        get() = textSecondary

    /** 禁用态前景。 */
    val textDisabled: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.neutralForegroundColor[
                FluentAliasTokens.NeutralForegroundColorTokens.ForegroundDisable1
            ]
        )

    /** 分隔线/描边：浅色 #E0E0E0 / 深色 #4D4D4D。 */
    val stroke: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.neutralStrokeColor[
                FluentAliasTokens.NeutralStrokeColorTokens.Stroke1
            ]
        )

    /** 更细的装饰性描边。 */
    val strokeSubtle: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.neutralStrokeColor[
                FluentAliasTokens.NeutralStrokeColorTokens.Stroke2
            ]
        )

    /** 品牌强调色：浅色 Color80 / 深色 Color100，保证在各自底色上可读。 */
    val brand: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.brandForegroundColor[
                FluentAliasTokens.BrandForegroundColorTokens.BrandForeground1
            ]
        )

    /** 品牌色之上的前景色（仅当 [brand] 作为背景时使用）。 */
    val onBrand: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.neutralForegroundColor[
                FluentAliasTokens.NeutralForegroundColorTokens.ForegroundLightStatic
            ]
        )

    /**
     * 品牌色作为**背景**的表面色（顶栏底色）。
     *
     * 刻意不用 `BrandBackground1` 的默认双值：它在深色模式下取 `brandColor[Color100]`，
     * 那是一个亮蓝，白字压上去只有约 2.9:1。这里显式指定深色模式回落为
     * `NeutralBackground3`，与 Fluent 官方 `AppBarTokens.backgroundBrush` 对
     * `FluentStyle.Brand` 的处理完全一致。
     */
    val brandSurface: Color
        @Composable
        get() = resolve(
            FluentColor(
                light = FluentTheme.aliasTokens.brandBackgroundColor[
                    FluentAliasTokens.BrandBackgroundColorTokens.BrandBackground1
                ].value(ThemeMode.Light),
                dark = FluentTheme.aliasTokens.neutralBackgroundColor[
                    FluentAliasTokens.NeutralBackgroundColorTokens.Background3
                ].value(ThemeMode.Dark)
            )
        )

    /** 品牌浅底：用于选中态背景。 */
    val brandTint: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.brandBackgroundColor[
                FluentAliasTokens.BrandBackgroundColorTokens.BrandBackgroundTint
            ]
        )

    /** 危险/错误前景：浅色 #B10E1C、深色 #FF99A4，都是各自的对比度安全值。 */
    val danger: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.errorAndStatusColor[
                FluentAliasTokens.ErrorAndStatusColorTokens.DangerForeground1
            ]
        )

    /** 危险浅底。 */
    val dangerSurface: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.errorAndStatusColor[
                FluentAliasTokens.ErrorAndStatusColorTokens.DangerBackground1
            ]
        )

    /** 成功前景。 */
    val success: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.errorAndStatusColor[
                FluentAliasTokens.ErrorAndStatusColorTokens.SuccessForeground1
            ]
        )

    /** 警告前景。 */
    val warning: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.errorAndStatusColor[
                FluentAliasTokens.ErrorAndStatusColorTokens.WarningForeground1
            ]
        )

    /**
     * Snackbar 底色。
     *
     * 取 `NeutralBackground4`：浅色 `#FAFAFA` / 深色 `#333333`，
     * 与 Fluent `SnackBarTokens` 的 Neutral 分支一致，在画布与表面之上都能看出浮起。
     */
    val snackbarSurface: Color
        @Composable
        get() = resolve(
            FluentTheme.aliasTokens.neutralBackgroundColor[
                FluentAliasTokens.NeutralBackgroundColorTokens.Background4
            ]
        )

    /** Snackbar 文字色：与 [snackbarSurface] 配对，对比度约 6.2:1（浅）/ 8.9:1（深）。 */
    val snackbarText: Color
        @Composable
        get() = textSecondary

    /**
     * 点击涟漪色：浅色模式用黑、深色模式用白。
     *
     * 与 Fluent `TabItemTokens.rippleColor` 的做法完全一致
     * （那里就是 `FluentColor(light = Black, dark = White)`）。
     *
     * **自实现的可点击控件必须显式把它传给 `clickable(indication = ripple(...))`。**
     * 不传时 `Modifier.clickable` 会取 `LocalIndication.current`，而本工程的主题栈
     * （`FluentTheme` + 一个 Material3 的 `CompositionLocalProvider`）并不会把涟漪
     * 装进这个 CompositionLocal，结果就是"能点但没有任何点击反馈"——
     * Fluent 的组件之所以没这个问题，是因为它们全部显式传了涟漪。
     *
     * 此处用 Material3 的 `ripple()` 而非 M2 已废弃的 `rememberRipple()`：后者返回
     * 旧的 `Indication` 实现，与 Compose 1.7 的新 Indication API 不兼容。`ripple()`
     * 不是 `@Composable` 函数，不需要 `remember`，直接内联调用即可。
     */
    val ripple: Color
        @Composable
        get() = resolve(
            FluentColor(
                light = FluentTheme.aliasTokens.neutralForegroundColor[
                    FluentAliasTokens.NeutralForegroundColorTokens.ForegroundDarkStatic
                ].value(ThemeMode.Light),
                dark = FluentTheme.aliasTokens.neutralForegroundColor[
                    FluentAliasTokens.NeutralForegroundColorTokens.ForegroundLightStatic
                ].value(ThemeMode.Dark)
            )
        )
}

/**
 * 统一的文字组件：把**颜色显式写进 [TextStyle]**，不依赖 `LocalContentColor`。
 *
 * 这一点是刻意的。`BasicText` 默认色是 `Color.Unspecified`，运行期再向
 * `LocalContentColor` 求值；只要这条链路上有任何一环被覆盖或缺失，就会静默退回
 * `Color.Black`——正是"深色模式下文字全黑"这类问题的成因，而且编译期、单测都发现不了。
 * 因此本工程的所有文本一律走这里，由 [color] 参数在组合期就把颜色定下来。
 *
 * [centered] 控制默认对齐：页面上的文字统一居中，需要靠左时显式传 false。
 */
@Composable
fun FluentText(
    text: String,
    style: FluentTextStyle,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.textPrimary,
    centered: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    BasicText(
        text = text,
        style = style.style().copy(
            color = color,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start
        ),
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * 页面级滚动容器，统一 [PageMetrics] 外边距与列间距。
 *
 * 用于替换各页面里 `padding(16.dp)` + `fillMaxWidth(0.85f)` 这类散落的宽度魔法值：
 * 文字与控件的左右边界统一由 [LocalPageGutter] 决定，不再贴屏。
 *
 * 默认水平居中：本工程要求界面文字全部居中，因此让列本身居中承载，
 * 子项再用 `fillMaxWidth()` 撑满内容宽度，居中效果才稳定。
 */
@Composable
fun PageColumn(
    modifier: Modifier = Modifier,
    gutter: Dp = LocalPageGutter.current,
    horizontalAlignment: androidx.compose.ui.Alignment.Horizontal =
        androidx.compose.ui.Alignment.CenterHorizontally,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    contentPadding: PaddingValues = PaddingValues(vertical = FluentSpacing.mPlus),
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    // RTL 下 start/end 需要交换，交由 Compose 计算而不是硬编码 left/right。
    val layoutDirection = LocalLayoutDirection.current
    val resolved = PaddingValues(
        start = contentPadding.calculateStartPadding(layoutDirection) + gutter,
        end = contentPadding.calculateEndPadding(layoutDirection) + gutter,
        top = contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding()
    )
    val scrollModifier = if (scrollable) {
        Modifier.verticalScroll(rememberScrollState())
    } else {
        Modifier
    }
    // 用 fillMaxHeight 而不是 fillMaxSize：verticalScroll 会把自己变成"高度无上限"的约束，
    // 后续的 fillMaxSize 便拿不到父级高度，内容不足一屏时 Column 会塌缩成内容高度，
    // 使"卡片撑满剩余空间"这类填充布局失效。fillMaxHeight 保证最小高度仍是视口高度。
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .then(scrollModifier)
            .padding(resolved),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = verticalArrangement,
        content = content
    )
}

/**
 * 为整个子树提供页面外边距。
 *
 * 由 [BottomNavigationApp] 根据当前可用宽度计算一次，避免每个页面各自判断断点。
 */
@Composable
fun ProvidePageGutter(
    gutter: Dp,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalPageGutter provides gutter, content = content)
}

/**
 * 圆角文本输入框。
 *
 * Fluent 的 `TextField` **没有任何颜色参数**（签名里只有 value/onValueChange/label/
 * hintText/errorString 等），所有配色都走 `textFieldTokens`；而且它的四角是直角——
 * `TextFieldTokens` 里没有 cornerRadius 令牌。因此这里做两件事：
 *
 * 1. 提供自定义 [TextFieldTokens]，让输入文字、提示、标签、光标、错误色全部显式来自
 *    本工程的语义令牌（`AppTheme.*`），不依赖任何运行时兜底；
 * 2. 在外层包一层 [Surface] 画圆角底板与描边，并把 Fluent 自身的背景设为透明，
 *    避免方角底板从圆角边缘露出来。
 */
@Composable
fun FluentTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hintText: String = "",
    enabled: Boolean = true
) {
    val tokens = rememberAppTextFieldTokens()
    Surface(
        // heightIn 保证最小高度，同时允许内容增长（多行文本）
        // 不用固定 height：会裁切文字和缩小内部清空按钮
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(FluentShapes.radius),
        color = AppTheme.surface,
        border = BorderStroke(1.dp, AppTheme.stroke)
    ) {
        // Fluent 的 TextField 没有 singleLine 参数；它是多行文本框（也因此更适合
        // 文本/网址/密码这类可长可短的输入），行数由内容与 MaxLines 决定。
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            hintText = hintText,
            enabled = enabled,
            textFieldTokens = tokens,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 自定义 TextField 令牌：颜色全部取自 [AppTheme]，并且背景透明（圆角由外层 Surface 提供）。
 *
 * `TextFieldTokens` 是 `open class`，这里只覆盖需要的几项，其余沿用官方默认。
 */
// `TextFieldTokens` 的所有配色方法都是 `@Composable open fun`，因此这里的覆写必须
// 同样带上 `@Composable`，否则会变成"名称相同但签名不同"的新函数（报 Conflicting overloads）。
// 类的 `Parcelable` 由父类实现并在此保持，没有新增需要序列化的字段之外的负担。
private class AppTextFieldTokens(
    private val fieldBackground: Color,
    private val fieldLabel: Color,
    private val fieldHint: Color,
    private val fieldInput: Color,
    private val fieldCursor: Color,
    private val fieldDivider: Color,
    private val fieldError: Color
) : TextFieldTokens() {

    @Composable
    override fun backgroundBrush(textFieldInfo: TextFieldInfo): Brush =
        SolidColor(fieldBackground)

    @Composable
    override fun textAreaBackgroundBrush(textFieldInfo: TextFieldInfo): StateBrush = StateBrush(
        rest = SolidColor(fieldBackground),
        disabled = SolidColor(fieldBackground),
        focused = SolidColor(fieldBackground)
    )

    @Composable
    override fun labelColor(textFieldInfo: TextFieldInfo): Color =
        if (textFieldInfo.isStatusError) fieldError else fieldLabel

    @Composable
    override fun hintColor(textFieldInfo: TextFieldInfo): Color = fieldHint

    @Composable
    override fun assistiveTextColor(textFieldInfo: TextFieldInfo): Color =
        if (textFieldInfo.isStatusError) fieldError else fieldHint

    @Composable
    override fun inputTextColor(textFieldInfo: TextFieldInfo): StateColor = StateColor(
        rest = fieldInput,
        focused = fieldInput,
        pressed = fieldInput,
        selected = fieldInput,
        disabled = fieldHint
    )

    @Composable
    override fun cursorColor(textFieldInfo: TextFieldInfo): Brush = SolidColor(fieldCursor)

    @Composable
    override fun dividerColor(textFieldInfo: TextFieldInfo): Brush =
        SolidColor(if (textFieldInfo.isStatusError) fieldError else fieldDivider)
}

/**
 * 构建 [AppTextFieldTokens]。
 *
 * 颜色在这里（组合期、正确的主题作用域内）解析一次，令牌对象随之固定下来，
 * 避免把 `@Composable` 取值带进非组合的回调里。
 */
@Composable
private fun rememberAppTextFieldTokens(): TextFieldTokens {
    // 背景透明：圆角底板由外层 Surface 负责，Fluent 自己再画一层方角底会露边
    val transparent = Color.Transparent
    val label = AppTheme.textHint
    val hint = AppTheme.textHint
    val input = AppTheme.textPrimary
    val divider = AppTheme.stroke
    val error = AppTheme.danger
    return remember(label, hint, input, divider, error) {
        AppTextFieldTokens(
            fieldBackground = transparent,
            fieldLabel = label,
            fieldHint = hint,
            fieldInput = input,
            fieldCursor = input,
            fieldDivider = divider,
            fieldError = error
        )
    }
}

/** 当前是否处于深色模式（供少数组件在无主题上下文时判断）。 */
@Composable
@ReadOnlyComposable
fun isDarkMode(): Boolean = when (FluentTheme.themeMode) {
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
    ThemeMode.Auto -> isSystemInDarkTheme()
}
