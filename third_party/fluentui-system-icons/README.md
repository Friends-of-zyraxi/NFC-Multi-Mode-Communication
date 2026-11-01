# Fluent UI System Icons

来源：[Microsoft Fluent UI System Icons](https://github.com/microsoft/fluentui-system-icons)。

本项目仅引入底部导航所需的 24 px 图标，每个图标包含 Regular 和 Filled 两个版本：

| 导航项 | 图标 | 上游目录 |
| --- | --- | --- |
| 读卡 | Tag | [assets/Tag/SVG](https://github.com/microsoft/fluentui-system-icons/tree/main/assets/Tag/SVG) |
| 写卡/卡模拟 | Edit | [assets/Edit/SVG](https://github.com/microsoft/fluentui-system-icons/tree/main/assets/Edit/SVG) |
| 端对端通信 | Arrow Swap | [assets/Arrow Swap/SVG](https://github.com/microsoft/fluentui-system-icons/tree/main/assets/Arrow%20Swap/SVG) |

资源位于 `app/src/main/res/drawable/ic_fluent_*_24_*.xml`。从上游 SVG 转换为 Android VectorDrawable，保留路径和填充规则；统一使用黑色路径，由界面主题着色，尺寸为 24 dp。

图标采用 MIT 许可证，完整版权声明和许可条款见 [LICENSE](LICENSE)。
