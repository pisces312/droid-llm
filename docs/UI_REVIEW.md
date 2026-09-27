# UI/UX 审查与重构方案（UI_REVIEW）

> 日期：2026-09-27
> 范围：`app/` 全部 Compose UI + `ui/theme` + `ui/components`，对照 `UI_DESIGN.md` 与**本机 MnnLlmChat 源码实测**
> 状态：审查与规划文档。§7 的 4 项决策已于 2026-09-27 拍板；**R1、R1.5、R2、R3 已按本方案落地**（见 `IMPLEMENTATION.md` §8d 各「交付说明」），R4 待做。§4.1 的 P0-3~7 已逐条标注实测结果，其中 **P0-4 的修法被实测推翻并改写**，值得先读那条。§5.1 的布局已在 R3 实现，实际落地形态见下方「R3 实测补充」。
> 关联：界面权威仍是 `UI_DESIGN.md`；引擎/Backend 契约见 `DESIGN.md` §1.2。

---

## 0. 结论摘要

底子不差：token 集中在 `ui/theme`、双套深浅色、通用组件抽到 `UiComponents.kt`、有 `UI_DESIGN.md` 做唯一真相。
真正的问题不在"好不好看"，而在两处：

1. **控件语义混乱**——同一个主色填充按钮被复用于"选中 / 主操作 / 次级"共 12+ 处，导致每页都有一排紫块，真正该突出的大数字与主按钮反而被淹没。
2. **信息密度错配**——工具型 App，但每页把大段说明和宽松大卡片堆在首屏，操作被推到屏幕外（最典型：评测页"开始评测"不在首屏）。

外加一组**一致性硬伤**：同一个引擎在四个界面有四种写法（详见 §4.1）。

MnnLlmChat 的对照给出一个高价值结论：**"引擎+模型合并成一个入口"是对的，但入口本体不需要图标**——它的切换器 `ModelSwitcherView` 就是"文字 + 下拉箭头"，图标只出现在**弹层内的列表条目**上（§3.3）。

---

## 1. 审查范围与方法

- 通读 `UI_DESIGN.md`、全部 UI 源码（5 个 Screen + `FileBrowser` + `UiComponents` + `theme`）、`docs/screenshots/` 四张真机截图。
- 交叉核对引擎契约（`core/engine-api/.../LlmEngine.kt`），确认 UI 建议不破坏 `DESIGN.md` §1.2。
- 对照阅读本机 MnnLlmChat 源码（`D:\3rd-party-projects\MNN\apps\Android\MnnLlmChat`），逐条给出"可借鉴 / 不可借鉴"。

**判定纪律**：所有"现状"结论都有文件:行号支撑；所有"建议"都标注是否属于纯改名 / 可独立验证的改动。

---

## 2. 现状盘点

### 2.1 页面结构

四页 Compose，底部 Tab 导航，`DroidLlmRoot.kt` 承载 `AppNavigation`：

| 页面 | 文件 | 首屏主要内容 |
|---|---|---|
| 聊天 | `ui/chat/ChatScreen.kt` | 顶栏（引擎下拉 + 模型下拉 + 溢出菜单）→ 消息列表 → 采样参数折叠卡 → 输入行 |
| 模型 | `ui/models/ModelsScreen.kt` | TabRow（已导入 / 模型市场）→ 引擎按钮行 → 列表 |
| 评测 | `ui/benchmark/BenchmarkScreen.kt` | 引擎 × 模型卡片堆叠 → 提示词/用例 Chip → 折叠参数 → 「开始评测」 |
| 设置 | `ui/settings/SettingsScreen.kt` | 采样默认值 → 外观 → 数据 → 关于 |

### 2.2 主题与 token

- `ui/theme/Color.kt` 集中 StreamClip 紫/青 token；`Theme.kt` 双套 `lightColorScheme`/`darkColorScheme`；`DroidExtraColors`（Accent/Warn/Ok）走 `CompositionLocal`。
- `ui/theme/Type.kt`：`bodySmall` / `labelSmall` 都是 11sp，`labelMedium` 12sp——**层级几乎无差**（见 §4.3）。
- Metric 字号带 tabular `tnum`，这点做得好，应保留。

### 2.3 通用组件（`ui/components/UiComponents.kt`）

已有 `EngineStatusCard` / `ModelPicker` / `MetricPill` / `PrimaryButton`(52dp) / `OutlinedToolButton` / `ProgressHeader` / `ResultTable` / `WarningBanner`。

问题在于 `PrimaryButton` 被当成了"默认按钮"来用（§4.2 第一条）。

---

## 3. 参考对照：MnnLlmChat（本机源码实测）

`MNN_LLM_CHAT_ROOT=D:\3rd-party-projects\MNN\apps\Android\MnnLlmChat`，Java + XML View 体系（不是 Compose），但**交互骨架完全可移植**。

### 3.1 它的整体骨架

```
activity_main.xml          DrawerLayout
├── CoordinatorLayout
│   ├── AppBarLayout（enterAlways 可折叠）
│   │   └── MaterialToolbar
│   │       └── ModelSwitcherView(id=main_title_switcher, layout_gravity=center)   ← 顶栏中央唯一控件
│   ├── FrameLayout(id=main_fragment_container)
│   ├── BottomTabBar(56dp, gravity=bottom)
│   └── ExpandableFabLayout（星标/提 issue/添加本地模型）
└── NavigationView（抽屉：Models / History）
```

**关键设计：顶栏中央那一个控件是"一物两态"的**（`MainActivity.kt:298-316`, `364-392`）：

| 页 | 显示 | 可点 | 点击行为 |
|---|---|---|---|
| Chat 列表 | "Chats" | ✗ | — |
| 模型市场 | 当前下载源名 | ✓ | 弹源选择 |
| 评测 | "Benchmark" | ✗ | — |

而**进入单个会话后的 `ChatActivity` 顶栏中央 = 模型选择器**（`activity_chat.xml:32-37`），点击 → `showModelSelectionDialog()`（`ChatActivity.kt:1001`）→ `fragment_choose_model.xml` BottomSheet。

### 3.2 可直接借鉴的模式

| # | 模式 | MnnLlmChat 实现 | 对我们解决什么 |
|---|---|---|---|
| 1 | **顶栏中央单入口** | `ModelSwitcherView`：`TextView(maxWidth=150dp, singleLine, ellipsize=end)` + `ic_arrow_drop_down`，`minHeight=30dp`（`view_model_switcher.xml`） | 替掉现在半宽模型下拉 + 引擎下拉两个平行控件 |
| 2 | **弹层 = BottomSheet，条目带勾选** | `fragment_choose_model.xml`：drag handle + 标题 + RecyclerView；条目 `list_item_model_selection.xml`(56dp)：40dp avatar + 名称(1行 ellipsize) + TagsLayout + 右侧 `iv_check` | 长模型名不再被 50% 宽下拉截断 |
| 3 | **位置即语义** | 会话级（模型）→ 顶栏；**每条消息级**（Thinking 开关）→ 输入框右侧（`activity_chat.xml:212-230`） | 印证：我们的会话级配置不该挪到输入框旁边 |
| 4 | **输入区是一张复合卡** | 单个 `MaterialCardView`：图片预览区 + `EditText(minLines=1, maxLines=5)` + 底行 `[+][音频] … [Thinking][发送]` | 把我们的"输入框 + 折叠参数卡"收成一张卡 |
| 5 | **自动滚动三件套** | ①新内容即滚 ②用户上滚 → 停跟随（`isUserScrolling`）③非底部时显示悬浮「回到底部」按钮 `btn_scroll_to_bottom`（`activity_chat.xml:306-329`；逻辑 `ChatListComponent.kt:216,228-229`；已有 `ChatAutoScrollUiAutomatorTest`） | 直接修掉我们"生成过程中列表不动"的缺陷 |
| 6 | **统一空态** | Chat：`ModelAvatarView`(65dp) + 一句 `model_hello_prompt`；Models：200dp 插图 + `OutlinedButton`「去下载」+ 说明（`chat_layout_empty_view.xml` / `fragment_modellist.xml`） | 我们三种空态做法不统一 → 收成同一套 |
| 7 | **参数设置走 BottomSheet + 显式保存** | `fragment_settings_sheet.xml`：drag handle + 标题 + 可滚动内容 + 底部 `[重置][取消][完成]` + divider | 折叠卡（全宽 Card 套 TextButton）换成 sheet |
| 8 | **评测页置顶"选模型"卡** | `fragment_benchmark.xml`：第一张卡就是 `ModelAvatarView`(48dp) + 模型名 + `TagsLayout` + 文件夹图标 + 大小/状态 | 替掉我们 5 张引擎卡各占约 70dp 的堆叠 |
| 9 | **AppBar 可折叠** | `layout_scrollFlags="enterAlways"` + `appbar_scrolling_view_behavior` + 动态 `app_bar_content` | 滚动时把顶部让给内容 |

### 3.3 "图标"这条的实测结论（**修正我此前的判断**）

此前我说"模型是用户自导入的任意文件，不可能建立图标体系"。看到 MnnLlmChat 实现后需要**修正**：

- `ModelAvatarView.setModelName()`（`widgets/ModelAvatarView.kt:51-74`）先试 `ModelUtils.getDrawableId(modelName)`（`model/ModelUtils.kt:81+`）——按**名称子串**匹配**厂商** logo：`deepseek/qwen|qwq/llama|mobilellm/smo/phi/baichuan/yi/glm|codegeex/reader/internlm/gemma/gpt/hunyuan…`；未命中则取模型名 `-` 前第一段当文字（`Qwen3-4B-Instruct` → `Qwen3`）。
- 所以它**有**图标，但图标的可识别性来自**厂商层**（约 16 个 `ModelVendors`），不是"每个模型一个图标"。用户任意命名的文件退化为文字。

**修正后的结论**：

1. **切换器本体不需要图标**——`ModelSwitcherView` 本身只有文字 + 箭头。图标只出现在**弹层里的列表条目**。
2. **引擎**（4 个固定值）适合给标识，因为它等价于"厂商层"；但用 **2 字符文字徽章/状态点** 比造图形更划算。
3. **模型**可做"厂商 logo（命中）+ 首段文字（兜底）"，前提是接受未命中就是文字。**不建议为模型做专属图形**。
4. **我们比 MnnLlmChat 更省事**：它的厂商靠**名称子串猜**，而我们的 `assets/model_catalog.json` **已有 `vendor` 字段**（162 条覆盖 28 家厂商）——市场下载的模型直接读字段，只有用户自导入的文件才需要子串兜底。详见 §7.1。

**判定**：**合并入口这个直觉是对的**（§5.1）；**"用图标提高紧凑度"不成立**——紧凑度来自"两个控件合并为一个入口 + 弹层半屏可用"，与图标无关。**"移到输入框旁边"不成立**——位置即语义（模式 3）。

### 3.4 不可借鉴 / 差异

| 项 | MnnLlmChat | droid-llm | 说明 |
|---|---|---|---|
| 引擎选择 | **不存在**（单引擎 App） | 必须并列 4 个引擎 | 我们是它没有的复杂度，**弹层的"两级选择"要自研** |
| 底部导航 | 2 tab（Chats / Models），Benchmark 在溢出菜单 | 4 tab（聊天/模型/评测/设置） | 可讨论是否降级：评测是低频操作 |
| 抽屉 | 有（Models / History 历史会话） | 无（会话历史是"新建会话"即清空） | 会话历史不在本期范围 |
| 模型列表页 | 有"模型市场 / 本地 / 评测"三个 Tab | 有"已导入 / 模型市场"两 Tab | 已对齐 |
| 技术栈 | Java + XML + Fragment | Kotlin + Compose | 只借交互，不借实现 |

---

## 4. 问题清单

### 4.1 P0 · 一致性硬伤（都有代码定位，可复现）

**P0-1 同一个引擎，六个界面六种写法**

| # | 位置 | 实际显示 | 来源 |
|---|---|---|---|
| 1 | Chat 引擎下拉 | `MNN 3.6.1 #c0461933` | `ui/chat/ChatViewModel.kt:141` → `engine.labelledName` |
| 2 | Models 引擎按钮 | `LLAMACPP` | `ui/models/ModelsScreen.kt:381,387` → `EngineId.name` |
| 3 | Models 已导入列表 | `LITERT` | `ui/models/ModelsScreen.kt:225` → `${model.engineId}` |
| 4 | Models 市场列表 | `LITERT` | `ui/models/ModelsScreen.kt:306` → `${row.model.engine}`（catalog 存的就是 `EngineId.name`） |
| 5 | 评测结果表 | 成功行带版本 / **失败行 `LITERT`** | `ui/benchmark/BenchmarkScreen.kt:335,347`；失败 fallback 在 `core/benchmark/.../BenchmarkRunner.kt:106` |
| 6 | 评测历史卡 | `LITERT` | `ui/benchmark/BenchmarkScreen.kt:387` → `${item.engineId}`（Room 存 `EngineId.name`） |

**修法（比原计划更简单）**：`LlmEngine.displayName` **已经存在**（`core/engine-api/.../LlmEngine.kt:204`，实测值 `LiteRT-LM` / `MNN` / `Genie` / `llama.cpp`）。只需：

1. 在 `engine-api` 加 `val EngineId.displayName: String`（与四个引擎的 `displayName` 一致），供只有枚举的场合（`EngineIdDropdown`）使用；
2. 让各引擎的 `override val displayName` **改为返回 `id.displayName`**，保证只有一处定义；
3. 上表 2–6 全部改走它；`labelledName`（`LlmEngine.kt:230`）只保留给需要版本号的场合（Chat 顶栏、导出 JSON）。

> **实施时新增的发现**：第 4 项（catalog 的 `engine` 字段）与第 6 项（Room 行）存的是 **`EngineId.name`**，是**机器可读 ID**，不能直接当展示名。故另加 `EngineId.Companion.fromStorage(raw)` 反解后取 `displayName`。第 1 项（Chat 顶栏）保持 `labelledName`——版本号在那里有用。
> **不得改动**的 `.name` 用法（持久化/机器可读）：`ModelsViewModel.engineDir`（目录名）、`DataStoreModelPathStore:82`、`BenchmarkRunner:418,420`（Room 字段）、`JsonExporter:31`（JSON 的 `engineId` 字段，与 `engineDisplayName` 成对）。


**P0-2 Models 引擎按钮文字断行**（截图实证：`LITER T`、`GENI E`）
`EngineIdDropdown`（`ModelsScreen.kt:375-390`）用 4 个 `weight(1f)` 的 52dp 按钮，每格约 85dp 装不下 `LLAMACPP`。
**修法**：改 `FlowRow` + `FilterChip`（参照 MnnLlmChat `chip_filter_item.xml`），或横向滚动 chip 行。
→ **已落地（R1.5，2026-09-27）**：取「横向滚动 chip」方案，`ModelsScreen.ChoiceChipRow`，
三行（引擎 / 下载源 / 下载过滤）统一。实测三行文字全部单行，且 4 个引擎 chip 总宽 891px
一屏放得下（无需滚动）。

**P0-3 Settings 的 `backend` 是只读 `OutlinedTextField`**（`SettingsScreen.kt:361-368`，`readOnly=true` 在 364）
长得像能输入，点了没反应；同一字段在 Chat 是下拉（`ui/chat/ChatScreen.kt` 采样面板）。
**修法**：改下拉（复用 Chat 那份），或改只读行视觉（无输入框描边）。
→ **已落地（R2，2026-09-27）**：复用通用下拉组件（`UiComponents.kt`，由 `ModelPicker` 更名 `LabeledDropdown`），
候选 `Backend.entries`；实测点开列出 `CPU / GPU / OPENCL / NPU_HTP / AUTO`，选中后落盘并回显，
`uiautomator` 里该节点由纯 `EditText` 变为带 `android.widget.Spinner` 子节点（真的成了下拉）。

**P0-4 数值输入框吞掉中间态**（`SettingsScreen.kt:310-381` 六处 `OutlinedTextField`；`ChatScreen.kt` 采样面板）
`v.toFloatOrNull()?.let{}` 直接拒掉 `"0."`，且值由外部 state 回灌 → 把 `0.7` 改成 `0.75` 时手感发粘。
**修法**：本地 buffer + 失焦/IME 完成再解析写回。MnnLlmChat 有现成参考 `modelsettings/NumericInputParser.kt`。
→ **已落地（R2，2026-09-27）**：抽 `ui/components/NumericField`，Settings 五处 + Chat 采样面板五处共用。
**动手时实测推翻了上面这条修法**（"失焦/IME 完成再解析"在本 App 里根本不触发），两条反直觉事实：

1. **触屏模式下点按钮不会移动焦点**。实测点「深色」按钮后输入框 `focused` 仍为 `true`，`onFocusChanged`
   不触发；改用 `DisposableEffect(onDispose)` 兜底也不行（切底部 tab 时编辑直接丢：0.75 → 回来变 0.7）。
   所以**不能依赖失焦**，改为**每次输入即提交**，本地 buffer 只负责让 `0.` 这类中间态留在屏幕上。
2. **`"0.".toFloatOrNull()` 是合法的**（= `0.0f`，不是 null）。所以"解析失败就不提交"这道门槛挡不住半截
   输入：把 `0.7` 删成 `0.` 会**静默把 temp 写成 `0.0`**（实测到了，切页回来显示 `0.0`）。
   补一条"末尾是小数点就不提交"的判断（唯一需要挡的形态，`-` / `e` / `.` 单独出现时 `toFloatOrNull` 本就返回 null）。

模拟器实测：`0.75 → 删位 → 显示 0.`（不再被弹回）→ 切页再回 = `0.7`（半截状态不污染存储）→ 补成 `0.75` 并切页往返 = `0.75`。

**P0-5 长回复不跟随滚动**（真缺陷）
`ChatScreen.kt:85` 用 `LaunchedEffect(messages.size)`，但流式 token 是**替换最后一条**（`ChatViewModel.kt:369-376`、`395-401`、`425-430` 的 `dropLast(1) + bubble`），`size` 全程不变 → 只在新增用户消息时滚一次，生成过程列表不动。
**修法**：照抄 §3.2 模式 5 三件套（新内容即滚 / 上滚停跟随 / 悬浮回底部按钮）。
→ **已落地（R2，2026-09-27）**：`LaunchedEffect(lastIndex, tailLength)`（按尾条内容长度触发，而非 `size`）+
`derivedStateOf` 判定是否停在底部（停则跟随，上滚即放手）+ 列表视口内右下角「回到底部」图标按钮。
**模拟器无法验证**（需要真实流式回复 → 需要模型 load，见 `docs/mnn.md` §5.1），**待真机**。

**P0-6 结果表左右是两份独立 `Column`**（`UiComponents.kt:312-342`，外层 `Column` + 内层横向 `Column`）
`TableCell.maxLines=2` 换行时行高不联动 → 错行风险；`UI_DESIGN §7` 承诺的"行展开看样本"未实现。
→ **已落地（R2，2026-09-27）**：不试图同步两个 pane 的行高，而是把所有 `TableCell` 钉在**同一个固定行高**
（`UiComponents.kt` `TableRowHeight = 52.dp`，容 2 行 `labelMedium` + 内边距）——固定即不可能错行。
"行展开看样本"仍未实现，留待 R4。

**P0-7 表格数字无单位**：Load/TTFT 是 ms、RSS peak 是 MB、温度是 ℃，表头与数据都没写。
→ **已落地（R2，2026-09-27）**：表头改为 `Load ms / TTFT ms / Prefill tok/s / Decode tok/s / RSS peak MB / 温度 ℃`。

### 4.2 P1 · 体验

| 问题 | 建议 |
|---|---|
| 主色填充按钮复用于"选中 / 主操作 / 次级"12+ 处 | 分三层：筛选走 Chip，一屏**仅一个** Filled 主操作，次级走 Outlined → **R3 已落地**（`UI_DESIGN.md` §4.4；实际改了三处：Settings 外观从 Filled/Outlined 按钮对改 chip、Models 添加卡的三颗按钮收成一颗 Filled + 路径框尾部图标、市场「刷新状态」从每行一颗提到工具栏一颗） |
| Chat 顶栏模型下拉只占半屏，模型名带版本号必然截断 | 顶栏中央单入口（§5.1），选中项在弹层里看全名 → **R3 已落地**（作用域条 + ScopeSheet） |
| 空态三种做法：Chat 有文案无按钮、Models 空列表纯空白、Benchmark 有按钮但写成"去 Models 页" | 统一「插图 + 一句说明 + 按钮」（模式 6） |
| 反馈位置错：Models 提示在 TabRow 上方、Settings 在整页最底部，滚动后看不到 | 统一 Snackbar |
| 市场每条目都带"刷新状态"按钮 | 提到顶部工具栏，做一次 → **R3 已落地** |
| 加载遮罩写"请勿离开此页"，但设计上底部导航可切（`UI_DESIGN §5.1`） | 二选一：真锁死，或允许离开并把进度提到顶栏常驻 |
| "展开采样参数"是全宽 Card 套 TextButton，折叠态白占约 64dp 且双重点击区 | 改一行 `ListItem`（标题 + 右侧箭头）；进一步可改 BottomSheet（模式 7） → **R3 已落地**：改成输入框上方一颗参数 chip，点击开 sheet |
| Chat 溢出菜单只有一个"新建会话"条目 | 直接放图标按钮 → **R3 已落地** |
| 评测页"开始评测"在首屏外 | 引擎卡改紧凑行（模式 8） |

### 4.3 P2 · 打磨

- 字号：`bodySmall`/`labelSmall` 均 11sp、`labelMedium` 12sp，层级几乎无差；中文 11sp 偏小 → 说明文案 12sp，11sp 只留脚注。
- 浅色卡片 `#EEEAF8` 对背景 `#F6F5FB` 对比过弱，卡片边界靠猜 → 浅色卡片改纯白 + 极浅描边。
- 长路径（模型根目录、模型条目）没有复制按钮——本 App 用户必然要复制路径，该给（MnnLlmChat 有 `ClipboardUtils.kt`）。
- `ui/models/FileBrowser.kt` 行高约 36dp，低于 44dp 触控标准。
- 消息不能长按复制；Chat 页是唯一没有 `titleLarge` 标题的页面。
- 术语不统一：`temp`（Settings）vs `temperature`（Chat）；`去 Models 页` 混了英文页名。

---

## 5. 布局重构方案

### 5.1 Chat 页：一行「作用域条」+ 两级 BottomSheet

**放弃**的两个方案（记录理由，避免反复）：

| 方案 | 判定 | 理由 |
|---|---|---|
| 引擎+模型移到输入框附近 | ✗ | 引擎/模型是**会话级**配置，切换 = 卸载旧会话 + 重新 load（阻塞、秒级）；输入框旁应放**每次发送都可能变**的轻量开关。位置即语义（MnnLlmChat 模式 3 同此结论） |
| 模型用图标点开 | ✗ | 紧凑度来自"两控件合并成一个入口 + 弹层半屏"，与图标无关；且 `ModelSwitcherView` 本体就是文字+箭头（§3.3） |

**采用**：

```
┌─────────────────────────────────────────┐
│  ● MNN  ·  Qwen3-4B-Instruct-2507-...  ⌄ │  ← 作用域条（整行可点，打开弹层）
├─────────────────────────────────────────┤
│                 消息列表                   │
│                              [↓ 回到底部]  │  ← 非底部时出现（P0-5）
├─────────────────────────────────────────┤
│  [ temp 0.7 ▾ ]  ← 采样 chip（可选）      │
│  ┌───────────────────────────────────┐  │
│  │ 输入…                        [发送] │  │  ← 复合输入卡
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

弹层（`ModalBottomSheet`）**一个面板管两级**（这正是 MnnLlmChat 没有、我们必须自研的部分）：

```
┌─────────────────────────────────────────┐
│  ──── 拖动条 ────                         │
│  选择引擎与模型                            │
│  ● LiteRT-LM   ● MNN   ● Genie   ● llama.cpp   ← 引擎 chip 行（● = 状态点：绿/灰/琥珀）
│  ─────────────────────────────────────    │
│  MNN 引擎的可用模型                        │  ← 列表随 chip 变
│  ┌────┐ Qwen3-4B-Instruct-2507-MNN-4bit  ✓│
│  │Qwen│ MNN · 4bit · 2.1GB                 │  ← 厂商 logo（命中）/ 首段文字（兜底）
│  └────┘                                    │
│  ┌────┐ LFM2-350M-MNN-4bit                 │
│  │LFM │ MNN · 4bit · 350MB                 │
│  └────┘                                    │
└─────────────────────────────────────────┘
```

这样做 UI 结构终于对上了数据模型——模型本来就从属于引擎（`DESIGN §1.2` 四种格式互不通用），现状两个平行下拉把这个层级藏起来了。

**必须守住的约束**

- 弹层里的选择**仍然只是"选中"**：按 `DESIGN §1.2`，选引擎或模型只释放旧会话回到 `IDLE`，**不自动 load**；加载仍由主按钮触发。弹层只换外壳，不动契约。
- 作用域条**整行可点** → 打开弹层；引擎 chip 只在弹层**内部**切换（否则又变两个入口）。
- 模型条目字段参照 MnnLlmChat `list_item_model_selection.xml`：`avatar/徽章 + 名称(1 行 ellipsize) + 副行(引擎 · 量化 · 体积) + 选中勾`。avatar = 厂商 logo（§7.1，命中 74%）+ 文字兜底。
- 引擎 chip 带**状态点 ●**（§7.2 三态映射），不可用引擎 chip 灰显 + 一行原因，仍可点开查看。

**R3 实测补充（落地形态与上面草图的差异，2026-09-27）**

1. **顶栏从三行压到两行**。草图画了一行作用域条，没写状态行去哪。实做：第一行 = 作用域条；第二行 =
   状态句 + 启动/停止（40dp）+「新建会话」图标按钮。状态句必须留着——加载失败原因、空回复提示都靠它。
2. **参数 chip 放在输入框正上方**（`AssistChip` + Tune 图标，显示 `temp … · top_p … · tok …`），
   而不是草图中"输入卡上方一行"的独立区块。理由同位置即语义：它只影响下一句话怎么生成。
3. **ScopeSheet 的引擎 chip 行复用 `ChoiceChipRow`**，因此 `dimmed` 语义定为"只降透明度、不禁用"——
   不可用引擎仍然要能点开读原因。
4. **两个 sheet 都 `skipPartiallyExpanded = true`**（参数面板 6 个字段 + 标题 + 按钮，半屏放不下；
   且 M3 的 `skipPartiallyExpanded` 是 `rememberModalBottomSheetState` 的参数，不是 `ModalBottomSheet` 的——
   写成后者直接编译不过）。
5. **`EngineChoice.unavailableReason()`** 抽成顶层函数：状态行与 sheet 引擎 chip 共用一份原因文案，
   避免两处各写一遍后漂移。
6. **作用域条整行 clickable 用 `onClickLabel`** 而不是给 `⌄` 图标挂 `contentDescription`：
   图标是装饰，无障碍节点应读整行的动作。
7. **模型条目的副行**用 `引擎 · 量化 · 体积`，体积格式化函数 `formatModelSize` 从 `ModelsScreen`
   提到 `UiComponents`（十进制单位，与模型站标称一致）。
8. **模型条目尚未加厂商 logo**（§7.1）——avatar 位留待 R4 与 logo 资源一起做。

### 5.2 其余页面

| 页面 | 改动 | 解决 |
|---|---|---|
| Models | ~~4 个等宽引擎按钮 → 一行**横向 chip**~~ **已完成（R1.5）**：引擎 / 下载源 / 下载过滤三行都换成 `ChoiceChipRow` | P0-2 断行 |
| Models | ~~"刷新状态"从每个条目提到顶部工具栏一次~~ **已完成（R3）** | 重复按钮 |
| Models | ~~添加卡三颗按钮 → 一个 Filled + 路径框尾部图标~~ **已完成（R3）** | 三层职责 |
| Models | 列表条目加**厂商 logo** avatar（§7.1，命中 74%，未命中文字兜底） | 可识别性 |
| Models | 空态改「插图 + 说明 + 按钮」 | 空态不统一 |
| Benchmark | 引擎卡 → 置顶"选模型"卡 + 紧凑引擎行（模式 8） | "开始评测"回首屏 |
| Settings | ~~`backend` 假输入框 → 下拉；数值框加本地 buffer~~ **已完成（R2）** | P0-3 / P0-4 |
| Settings | ~~外观三选一用 Filled/Outlined 按钮对表达选中 → chip~~ **已完成（R3）** | 三层职责 |
| Chat | ~~采样参数折叠卡 → chip + sheet~~ **已完成（R3）**；~~顶栏双下拉 → 作用域条 + 两级 sheet~~ **已完成（R3）** | 再省约 64dp / 长名截断 |
| 全局 | **底部导航保持 4 tab 常驻**（决策 1：宽度充裕，不改） | — |
| 全局 | 三层控件职责写进 `UI_DESIGN.md` **§4.4**（含"一屏一个 Filled"按可见状态判定的说明） | 防止再次漂移 |

---

## 6. 落地顺序

分四步，每步可独立验证；**R1 / R1.5 / R2 / R3 已完成**，下一步 R4。

| 步骤 | 内容 | 覆盖 | 风险 |
|---|---|---|---|
| **R1** ✅ | 引擎显示名统一（`EngineId.displayName`，**6 处**展示面改走它） | P0-1 | 极低，纯改名（**已完成 2026-09-27**） |
| **R1.5** ✅ | Models **三行**等宽按钮 → `ChoiceChipRow`（横向滚动 chip），根治 `LiteRT-LM` / `llama.cpp` / `ModelScope` 断字 | P0-2 | 极低（**已完成 2026-09-27**，从 R4 提前） |
| **R2** ✅ | 五个小改纯收益项：数值输入 buffer（P0-4）、跟随滚动三件套（P0-5）、表格单位（P0-7）、`backend` 假输入框（P0-3）、表格行高联动（P0-6） | P0-3~7 | 低（**已完成 2026-09-27**；P0-5 待真机验，见 §4.1） |
| **R3** ✅ | 三层控件体系 + Chat 顶栏重构（作用域条 + 两级 BottomSheet，引擎 chip 带**状态点 ●**〔决策 2〕）+ 采样参数收成 chip 并**点击开 BottomSheet**〔决策 4〕 | §4.2 首条 / §5.1 | 中（**已完成 2026-09-27**，已回写 `UI_DESIGN.md` §4.2/§4.4/§5.1/§7.1；构建 + 单测通过，UI 效果待真机/模拟器确认） |
| **R4** | 评测页密度重构（§4.2）+ **厂商 logo 资产接入（§7.1，含 `docs/LICENSING.md` 增记商标条目）** + 空态统一 + 其余打磨。~~Models 引擎 chip 行~~ 已由 **R1.5** 提前完成 | §4.2 / §4.3 | 中 |

**R3 需同步回写 `UI_DESIGN.md`**：它现在是唯一真相，但没有"控件三层职责"这条约束；不写进去会再次漂移。
→ **已完成**：新增 §4.4（三层职责）+ §4.5（Compose 表单两个反直觉事实）+ §7.1（状态点三态），
并同步 §4.2 组件表、§5.1 聊天页、§5.2 模型页、§5.3 评测页、§5.4 设置页、§9 实现映射。
**R4 的 logo 资产**是新增资源类别（我方 `res/drawable*` 目前几乎为空），需一并处理压缩与 `LICENSING.md`。
**决策 1（4 tab 常驻）不产生改动项**，已从清单移除。

---

## 7. 决策记录（2026-09-27 已拍板）

| # | 问题 | 决定 | 影响 |
|---|---|---|---|
| 1 | 底部导航是否降级为 2 tab + 溢出菜单 | **不降级，4 tab 全部常驻**（当前宽度充裕） | 无改动；`DroidLlmRoot.kt` 导航结构不动 |
| 2 | 引擎标识形态 | **状态点 ●**（不用文字徽章） | 复用 `EngineStatusCard` 已有的绿/灰/琥珀三态色；顺带替掉单独占一行的"可用 · 已加载" |
| 3 | 模型条目是否引入厂商 logo | **引入** | 见下方清单与命中率；未命中退化为文字 |
| 4 | 采样参数是否搬进 BottomSheet | **搬** | Chat 输入卡上方只留一个 chip，点击开 sheet（与 MnnLlmChat 模式 7 一致） |

### 7.1 厂商 logo 的落地依据（实测）

**关键发现：我们的 `assets/model_catalog.json` 已经有 `vendor` 字段**，市场下载的模型不需要按名字猜厂商——直接读字段。只有**用户自导入的任意文件**才需要子串匹配兜底。

`162` 条 catalog 的厂商分布与 logo 覆盖（logo 源：MnnLlmChat `res/drawable-nodpi/*_icon.{png,webp}`，共 17 个文件，总体积约 `900KB`）：

| 厂商 | 条数 | logo 资源 | 厂商 | 条数 | logo 资源 |
|---|---:|---|---|---:|---|
| Qwen | 63 | `qwen_icon` ✓ | FastVLM | 4 | — 文字兜底 |
| LFM | 21 | — 文字兜底 | MiMo | 4 | — 文字兜底 |
| Smol | 11 | `smolm_icon` ✓ | MiniCPM | 3 | `minicpm_icon` ✓ |
| Gemma | 10 | `gemma_icon` ✓ | 其余 13 家 | 各 1–2 | 部分命中 ↓ |
| DeepSeek | 6 | `deepseek_icon` ✓ | THUDM | 5 | `chatglm_icon` ✓ |
| Llama | 6 | `llama_icon` ✓ | Hunyuan | 5 | `hunyuan_icon` ✓ |
| MobileLLM | 4 | `llama_icon`（同 Meta） | InternLM | 2 | `internlm_icon` ✓ |
| GPT / 01.AI / Baichuan / Phi / TinyLlama | 各 1 | `openai` / `yi` / `baichuan` / `phi` / `llama` ✓ | | | |

**命中率约 120/162 ≈ 74%**（Qwen 一家占 63 条），其余约 26% 走文字兜底（末段规则：`ModelAvatarView` 取名称 `-` 前第一段，我们可改用 `vendor` 字段或首段）。

**资产与合规（R4 需一并处理）**

- 需从 MnnLlmChat 复制约 14 个 logo 到 `app/src/main/res/drawable-nodpi/`；`smolm_icon.png` 单文件 417KB，入包前应压缩。
- 我方当前 `res/drawable*` 几乎为空（仅 `ic_launcher_background.xml`），是新增资源类别。
- **厂商 logo 是各公司商标**：MNN 仓库为 Apache-2.0，但商标不在 Apache-2.0 授权范围内；用于"标识模型出处"属指示性使用（nominative use），业界普遍做法。**落地时需在 `docs/LICENSING.md` 增记一条**（来源 = MnnLlmChat，用途 = 厂商识别，声明商标归各厂商所有）。
- 我们的 `ModelVendors` 无需照抄 MnnLlmChat 的 16 条枚举——直接用 catalog 的 `vendor` 字段值做 key。

### 7.2 引擎状态点 ● 的三态映射（沿用 `EngineStatusCard`）

| `Availability` / 会话态 | 颜色 | 语义 |
|---|---|---|
| `Available` + 会话 `IDLE` | 绿 | 可用，未加载 |
| `Available` + 已加载该模型 | 绿（实心/加粗） | 正在使用 |
| 加载/生成中 | 琥珀 | 忙 |
| `MissingDependency` / `UnsupportedSoc` / `ModelNotConfigured` | 灰 | 不可用（点击弹层内对应引擎 chip 灰显 + 一行原因） |

