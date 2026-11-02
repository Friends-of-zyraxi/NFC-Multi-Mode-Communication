package io.github.zyraxi21.nfc.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.annotation.DrawableRes
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.microsoft.fluentui.theme.token.Icon
import com.microsoft.fluentui.theme.token.controlTokens.ButtonStyle
import com.microsoft.fluentui.tokenized.notification.SnackbarState
import io.github.zyraxi21.nfc.R
import kotlinx.coroutines.launch

enum class NavigationItem(
    val titleResId: Int,
    @param:DrawableRes val regularIconResId: Int,
    @param:DrawableRes val selectedIconResId: Int,
) {
    READ(R.string.nav_read, R.drawable.ic_fluent_tag_24_regular, R.drawable.ic_fluent_tag_24_filled),
    WRITE(R.string.nav_write, R.drawable.ic_fluent_edit_24_regular, R.drawable.ic_fluent_edit_24_filled),
    P2P(R.string.nav_p2p, R.drawable.ic_fluent_arrow_swap_24_regular, R.drawable.ic_fluent_arrow_swap_24_filled)
}

/**
 * 应用外壳：窄屏使用顶栏和横向分页，宽屏使用侧栏和页面淡入淡出。
 *
 * 顶栏底色走 [AppTheme.brandSurface]：浅色模式用品牌色（对齐 Word 安卓版带主题色的标题栏），
 * 深色模式退回中性表面 `NeutralBackground3`。深色不能继续用品牌色的原因是硬性的——
 * 那里的 `BrandBackground1` 取 `brandColor[Color100]`，是亮蓝 `#479EF5`，白字压上去
 * 只有约 2.9:1，远低于 WCAG AA 的 4.5:1。详见 [AppTopBar] 与 `AppTheme.brandSurface`。
 *
 * 顶栏与页面文字都通过 `FluentText` 显式指定颜色，不依赖 `LocalContentColor`。
 *
 * edge-to-edge 要点：
 * - 窄屏顶栏自己用 `statusBarsPadding()`，让品牌/表面色块贯通状态栏。
 * - 宽屏侧栏背景绘制到窗口边缘，仅对控件应用安全区，避免挖孔侧出现整条空白。
 * - 底栏自己用 `navigationBarsPadding()` 贯通手势条。
 * - `Scaffold` 的 `contentWindowInsets` 置零，系统栏 insets 只由顶栏/底栏消费一次，避免重复留白。
 */
@Composable
fun BottomNavigationApp(
    readerScreen: @Composable () -> Unit,
    writeScreen: @Composable () -> Unit,
    p2pScreen: @Composable () -> Unit,
    snackbarHostState: SnackbarState? = null,
    initialItem: NavigationItem = NavigationItem.READ,
    safeDrawingInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    var selectedItemIndex by rememberSaveable { mutableIntStateOf(initialItem.ordinal) }
    val pagerState = rememberPagerState(initialPage = selectedItemIndex, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()
    val pageAlpha = remember { Animatable(1f) }

    val navigationItems = listOf(NavigationItem.READ, NavigationItem.WRITE, NavigationItem.P2P)

    AboutBottomSheet { openAbout ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val availableWidth = this.maxWidth
            val wide = availableWidth >= PageMetrics.mediumBreakpoint
            val railInset = safeDrawingInsets.asPaddingValues().calculateStartPadding(LocalLayoutDirection.current)
            val contentWidth = availableWidth - if (wide) 89.dp + railInset else 0.dp
            val gutter = PageMetrics.gutterFor(contentWidth)
            // 进入窄屏时先同步当前页，再观察滑动；侧栏切换不驱动横向 Pager。
            LaunchedEffect(wide, pagerState) {
                if (!wide) {
                    pagerState.scrollToPage(selectedItemIndex)
                    snapshotFlow { pagerState.settledPage }.collect { selectedItemIndex = it }
                }
            }
            LaunchedEffect(wide, selectedItemIndex) {
                if (wide) {
                    if (pagerState.currentPage != selectedItemIndex) {
                        pageAlpha.animateTo(0f, tween(100))
                        pagerState.scrollToPage(selectedItemIndex)
                    }
                    pageAlpha.animateTo(1f, tween(160))
                } else pageAlpha.snapTo(1f)
            }
            val selectPage: (Int) -> Unit = { index ->
                selectedItemIndex = index
                if (!wide) coroutineScope.launch { pagerState.animateScrollToPage(index) }
            }
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                containerColor = AppTheme.canvas,
                snackbarHost = { FluentSnackbarHost(snackbarHostState) },
                topBar = { if (!wide) AppTopBar(gutter, onAboutClick = openAbout) },
                bottomBar = {
                    if (!wide) AppBottomBar(navigationItems, selectedItemIndex, selectPage)
                }
            ) { innerPadding ->
                Row(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)
                    .then(if (wide) Modifier else Modifier.windowInsetsPadding(safeDrawingInsets.only(WindowInsetsSides.Horizontal)))
                    .testTag("app-shell")) {
                    if (wide) AppNavigationRail(navigationItems, selectedItemIndex, selectPage, openAbout, safeDrawingInsets)
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .then(if (wide) Modifier.windowInsetsPadding(safeDrawingInsets.only(WindowInsetsSides.End + WindowInsetsSides.Vertical)) else Modifier)
                        .testTag("app-page-viewport"), contentAlignment = Alignment.TopCenter) {
                        val contentModifier = Modifier.widthIn(max = PageMetrics.maxContentWidth).fillMaxSize()
                        ProvidePageGutter(gutter) {
                            CompositionLocalProvider(LocalWideLayout provides wide) {
                                // 复用同一个分页容器以保留表单状态；宽屏在透明时直接换页，不滚动过渡。
                                HorizontalPager(
                                    state = pagerState, modifier = contentModifier
                                        .graphicsLayer { alpha = if (wide) pageAlpha.value else 1f }
                                        .then(if (wide) Modifier.testTag("app-wide-pages") else Modifier),
                                    beyondViewportPageCount = 1,
                                    userScrollEnabled = !wide,
                                ) { page ->
                                    when (page) {
                                        0 -> readerScreen()
                                        1 -> writeScreen()
                                        2 -> p2pScreen()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 顶栏：点击应用标题打开关于底部面板，底色使用品牌色（对齐 Word 安卓版的标题栏观感）。
 *
 * 为什么浅色模式用品牌色、深色模式改用中性表面色——这不是偏好问题，而是对比度决定的：
 * 深色模式下 Fluent 的 `BrandBackground1` 取 `brandColor[Color100]`，那是一个**亮**蓝
 * （官方值 `#479EF5`），白字压在上面对比度只有约 2.9:1，低于 WCAG AA 的 4.5:1。
 * Fluent 官方的 `AppBarTokens.backgroundBrush` 对 `FluentStyle.Brand` 也是同样处理：
 * 浅色用品牌色、深色退回 `NeutralBackground3`。本工程的品牌色阶已按官方色阶的亮度分布
 * 生成，因此浅色模式的 `Color80` 与官方蓝同级，白字对比度约 5.4:1。
 *
 */
@Composable
private fun AppTopBar(gutter: Dp, onAboutClick: () -> Unit) {
    val dark = isDarkMode()
    val background = if (dark) AppTheme.elevatedSurface else AppTheme.brandSurface
    val titleColor = if (dark) AppTheme.textPrimary else Color.White
    val dividerColor = if (dark) null else AppTheme.strokeSubtle
    val aboutLabel = stringResource(R.string.about_title)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .drawBehind {
                val strokeColor = dividerColor ?: return@drawBehind
                val stroke = 1.dp.toPx()
                drawLine(
                    color = strokeColor,
                    start = Offset(0f, size.height - stroke / 2),
                    end = Offset(size.width, size.height - stroke / 2),
                    strokeWidth = stroke
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    indication = ripple(color = titleColor),
                    interactionSource = remember { MutableInteractionSource() },
                    role = Role.Button,
                    onClickLabel = aboutLabel,
                    onClick = onAboutClick
                )
                // 水波纹先覆盖整个顶栏，再为标题内容留出状态栏高度。
                .statusBarsPadding()
                .heightIn(min = PageMetrics.minTouchTarget)
                .padding(horizontal = gutter, vertical = FluentSpacing.m),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FluentText(
                text = stringResource(R.string.app_title),
                style = FluentTextStyle.Title3,
                color = titleColor,
                centered = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(FluentSpacing.xxs))
        }
    }
}

/** 底栏图标尺寸，对标 Fluent TabItem 的 24dp。 */
private val BottomTabIconSize = 24.dp

/** 内容与顶边之间的留白（对标 Fluent TabItem 的 top padding）。 */
private val BottomTabTopPadding = FluentSpacing.s

/**
 * 底栏：自实现，不再直接用 Fluent 的 `TabBar`。
 *
 * 为什么必须自己写：`TabBar` 内部的 `Row` 是 `Modifier.fillMaxWidth()`，**没有等高**，
 * `TabItem` 的 `weight(1F)` 在 `Row` 里只分配宽度，高度仍是内容高度。因此无论给
 * `TabBar` 加 `navigationBarsPadding()`（只是在 Column 里多出一段空白），还是给
 * `TabBar` 设一个更大高度（`Row` 不会跟着长高），`TabItem` 的 `clickable` 与涟漪
 * 都停在手势条上方——按下去能明显看到白色手势条那一段是脱开的。
 *
 * 自己写之后布局是确定的：每个 Tab 是一个 `fillMaxHeight` 的点击区，
 * 高度 = 顶部留白 + 图标 + 文字 + 导航栏 inset，于是**点击与涟漪一直铺到屏幕底部**；
 * 图标与文字仍停在手势条上方，位置与原来的 `TabBar` 一致。
 *
 * inset 由 `WindowInsets.navigationBars` 换算，不硬编码手势条高度；
 * 三键导航时 inset 更大，同样成立。
 *
 * 配色沿用原 `TabBar` 的语义：容器与未选中项背景用同一个 `surface`
 * （Fluent 的 TabItem 未选中项也是 `NeutralBackground1`），选中项用品牌色。
 */
@Composable
private fun AppBottomBar(
    tabs: List<NavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val navigationBarInset = with(LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    // 内容高度 = 顶部留白 + 图标 + 图标与文字间距 + 文字行高(Caption2)
    val rowHeight = BottomTabTopPadding + BottomTabIconSize +
        FluentSpacing.xxs + 16.dp + navigationBarInset
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("app-bottom-navigation")
            .background(AppTheme.surface)
    ) {
        // 顶部 1dp 描边：与 Fluent TabBar 的 topBorder 一致，用于分隔内容与底栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AppTheme.stroke)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
        ) {
            tabs.forEachIndexed { index, item ->
                BottomTab(
                    title = stringResource(item.titleResId),
                    icon = ImageVector.vectorResource(if (index == selectedIndex) item.selectedIconResId else item.regularIconResId),
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

/** 侧栏背景延伸至挖孔区域，安全区只约束控件；导航居中，关于入口固定在底部。 */
@Composable
private fun AppNavigationRail(
    tabs: List<NavigationItem>, selectedIndex: Int, onSelect: (Int) -> Unit,
    onAboutClick: () -> Unit, insets: WindowInsets,
) {
    val rippleColor = AppTheme.ripple
    Row(Modifier.fillMaxHeight().background(AppTheme.surface).testTag("app-navigation-rail")) {
        BoxWithConstraints(Modifier.windowInsetsPadding(insets.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
            .width(88.dp).fillMaxHeight()) {
            val viewportHeight = this.maxHeight
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewportHeight)
                .padding(vertical = FluentSpacing.s)) {
                Spacer(Modifier.weight(1f))
                tabs.forEachIndexed { index, item ->
                    val active = index == selectedIndex
                    val color = if (active) AppTheme.brand else AppTheme.textSecondary
                    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("rail-tab-${item.name}")
                        .semantics { selected = active }
                        .clickable(role = Role.Tab, indication = ripple(color = rippleColor),
                            interactionSource = remember { MutableInteractionSource() }, onClick = { onSelect(index) })
                        .padding(horizontal = FluentSpacing.s, vertical = FluentSpacing.s),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(FluentSpacing.xs, Alignment.CenterVertically)) {
                        Icon(ImageVector.vectorResource(if (active) item.selectedIconResId else item.regularIconResId),
                            contentDescription = null, modifier = Modifier.size(BottomTabIconSize), tint = color)
                        FluentText(stringResource(item.titleResId), FluentTextStyle.Caption2, color = color, maxLines = 2)
                    }
                }
                Spacer(Modifier.weight(1f))
                FluentButton(text = stringResource(R.string.about_title), onClick = onAboutClick,
                    style = ButtonStyle.TextButton,
                    modifier = Modifier.fillMaxWidth().heightIn(min = PageMetrics.minTouchTarget).testTag("rail-about"))
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(AppTheme.stroke))
    }
}

/**
 * 底栏里的一个 Tab。
 *
 * 点击区是整块 `fillMaxHeight` 的 Column（一直铺到屏幕底部），涟漪会填满它。
 * 视觉内容（图标 + 文字）位于顶部；其下方用 `navigationBarsPadding()`
 * 撑出手势条区域——这段与 TabItem 的**背景色相同**，因此视觉上背景是连通的，
 * 不会出现"图标区一块、下面手势条一块"的割裂感。
 *
 * 涟漪必须显式传 `ripple()`：`Modifier.clickable` 不传 `indication` 时取
 * `LocalIndication.current`，而本工程的主题栈并不提供它，漏掉就会"能点但没有反馈"。
 * （用 Material3 的 `ripple()`，而非 M2 已废弃的 `rememberRipple()`；它非
 * `@Composable`，无需 `remember`。）
 */
@Composable
private fun BottomTab(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = if (selected) AppTheme.brand else AppTheme.textSecondary
    val rippleColor = AppTheme.ripple
    Column(
        modifier = modifier.clickable(
            indication = ripple(color = rippleColor),
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClick
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(BottomTabTopPadding))
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(BottomTabIconSize),
            tint = contentColor
        )
        Spacer(modifier = Modifier.height(FluentSpacing.xxs))
        FluentText(
            text = title,
            style = FluentTextStyle.Caption2,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // 手势条区域：与 Tab 同一背景色，把视觉背景一直铺到屏幕底部
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

@Preview(name = "外壳 浅色", showBackground = true)
@Composable
private fun PreviewBottomNavigationAppLight() {
    MyApplicationTheme(darkTheme = false, dynamicColor = false) {
        BottomNavigationApp(
            readerScreen = {},
            writeScreen = {},
            p2pScreen = {}
        )
    }
}

@Preview(name = "外壳 深色", showBackground = true)
@Composable
private fun PreviewBottomNavigationAppDark() {
    MyApplicationTheme(darkTheme = true, dynamicColor = false) {
        BottomNavigationApp(
            readerScreen = {},
            writeScreen = {},
            p2pScreen = {}
        )
    }
}

@Preview(name = "平板 横屏浅色", showBackground = true, widthDp = 1280, heightDp = 800)
@Preview(name = "平板 横屏深色", showBackground = true, widthDp = 1280, heightDp = 800,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "平板 竖屏", showBackground = true, widthDp = 800, heightDp = 1280)
@Preview(name = "手机 横屏", showBackground = true, widthDp = 850, heightDp = 393)
@Composable
private fun PreviewTabletNavigation() {
    MyApplicationTheme(dynamicColor = false) {
        BottomNavigationApp(readerScreen = { NFCReaderScreen(null, false) },
            writeScreen = { WriteCardScreen(WriteState.IDLE, "", {}, onOpenScreenShare = {}) },
            p2pScreen = { P2PScreen(ConnectionState.DISCONNECTED, null, true, true, null,
                {}, {}, {}, {}, {}, {}, {}, {}, true, {}) },
            initialItem = NavigationItem.P2P)
    }
}
