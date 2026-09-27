# droid-llm UI / 交互设计

> 本文档是 **界面与交互的唯一权威来源**。架构与引擎契约见 `DESIGN.md`，阶段执行见 `IMPLEMENTATION.md`。
> 冲突时：引擎/数据契约以 `DESIGN.md` 为准；视觉与交互以本文为准。
>
> 风格参照（本机工程）：
> - **StreamClip**（`D:\my-projects\StreamClip`）— 工具型深色 App：M3 Dark、紫主色 + 青强调、灰阶卡片、12dp 圆角按钮、52dp 触控高度、多 Fragment 高密度工具面
> - **PixelPlayerOSS**（`D:\3rd-party-projects\PixelPlayerOSS`）— Compose M3 工程化：双套 Light/Dark ColorScheme、透明状态栏与图标亮度、Typography 独立成文件、Color token 集中定义
>
> 定位：**评测工具**，不是内容消费 App。参考 StreamClip 的信息密度与按钮规范，采用 PixelPlayer 的 M3 双套主题与工程落地方式。**不**用 PixelPlayer 的音乐感大标题/展示型字体。

---

## 1. 风格锚点（Style Anchor）

| 项 | 选择 | 来源 |
|----|------|------|
| 类型 | 本机工具 / 调试台 | StreamClip |
| 底色 | 深色为主（跟随系统可切浅色） | StreamClip Dark + PixelPlayer 双套 |
| 强调 | 紫靛主色 + 青色数据强调 | StreamClip purple/teal |
| 表面 | 深灰层级卡片，非纯黑 | StreamClip gray_800/900 |
| 形 | 卡片 12dp / 按钮 12dp / Chip 全圆 | StreamClip `cornerRadius 12dp` |
| 字 | 系统字体（中英文清晰），不用展示字体 | 工具可读性优先 |
| 动效 | 默认 M3；无循环装饰动画 | 避免干扰读数 |

---

## 2. 色彩 Token（`ui/theme/Color.kt` 集中定义）

### 2.1 深色（默认）

| Token | Hex | 用途 |
|-------|-----|------|
| `Bg` | `#121212` | 页面底 |
| `Surface` | `#1E1E1E` | 卡片 |
| `SurfaceHigh` | `#2A2A2A` | 次级容器 / 输入框 |
| `Outline` | `#3C3C3C` | 分割线、描边 |
| `Primary` | `#7C6AF5` | 主按钮、选中、链接（StreamClip 紫的 M3 化） |
| `OnPrimary` | `#FFFFFF` | 主色上文字 |
| `Accent` | `#26C6DA` | 青强调：tps/TTFT 数字、进度、图表（对齐 StreamClip teal_200） |
| `Warn` | `#FFB74D` | 温度警告、空回复重试 |
| `Error` | `#EF5350` | 失败样本、不可用 |
| `Ok` | `#66BB6A` | 可用状态点 |
| `TextPrimary` | `#EDEDED` | 正文 |
| `TextSecondary` | `#9E9E9E` | 次要 / 说明（StreamClip gray_500） |
| `TextDisabled` | `#616161` | 禁用（gray_700） |

### 2.2 浅色

| Token | Hex | 用途 |
|-------|-----|------|
| `Bg` | `#F6F5FB` | 页面底（PixelPlayer LightBackground 邻近） |
| `Surface` | `#FFFFFF` | 卡片 |
| `SurfaceHigh` | `#EEEAF8` | 次级容器 |
| `Primary` | `#5B4BD6` | 主色 |
| `Accent` | `#00838F` | 数据强调 |
| `TextPrimary` | `#1C1B22` | 正文 |
| `TextSecondary` | `#5F5A6E` | 次要 |
| `Error` / `Warn` / `Ok` | `#D32F2F` / `#B26A00` / `#2E7D32` | 语义同深色 |

**规则**：同一语义在 Light/Dark 下角色不变；数字指标一律 `Accent`；错误样本文字用 `Error` 且加 `·` 前缀短因，不整行标红。

**可选增强（P5）**：Material You 动态取色（PixelPlayer 有 `dynamicColorScheme`）；默认关闭，Settings 提供开关，评测读数场景优先稳定对比度。

---

## 3. 字体与尺度（`ui/theme/Type.kt`）

- 字体族：`FontFamily.Default`（CJK 系统字体），**不引入** Montserrat/展示变体
- 数字：等宽倾向（`FontFeature` tabular，若可用），避免 tps 跳动抖列宽

| Style | Size / Weight | 用途 |
|-------|---------------|------|
| Title | 20sp / 600 | 页面标题 |
| Headline | 16sp / 600 | 分区标题 |
| Body | 14sp / 400 | 正文、列表 |
| Label | 12sp / 500 | 标签、Chip |
| Caption | 11sp / 400 | 脚注、免责句 |
| Metric | 18sp / 700 tabular | 结果表关键数字 |
| MetricSmall | 13sp / 600 tabular | 行内 tps |

---

## 4. 布局与组件

### 4.1 网格 / 间距

- 水平边距 **16dp**；区块垂直间距 **12dp**；卡片内边距 **12–16dp**
- 触控：按钮 `minHeight 52dp`（StreamClip SettingButton）、图标按钮 44dp
- 列表行高：普通 56dp；双行 72dp

### 4.2 通用组件（四页共用）

全部在 `app/src/main/java/.../ui/components/UiComponents.kt`。

| 组件 | 规格 |
|------|------|
| `EngineStatusCard` | 一行：`StatusDot` + 引擎名 + 状态句；灰显时透明度 45% |
| `StatusDot` | 8dp 实心 / 10dp 空心圆；三态见 §7.2，`solid=false` 表示"可用但未加载" |
| `ChoiceChipRow<T>` | 横向滚动 `FilterChip` 单选行；`dimmed` 只降透明度**不禁用**（不可用引擎仍要能点开看原因），`leading` 放状态点 |
| `LabeledDropdown` | Outlined 下拉，吃 `key to label` 列表；空态文案可传 |
| `NumericField` | 数值输入框：本地 buffer + 每次输入即提交；末尾小数点不提交（见 §4.5） |
| `SheetTitle` | BottomSheet 顶部标题块（标题 + 一行上下文） |
| `MetricPill` | 圆角胶囊，`Accent` 描边，展示 `TTFT/tps` |
| `PrimaryButton` | 52dp 高（`height` 可覆盖，聊天状态行用 40dp），12dp 圆角，`Primary` 填充 |
| `OutlinedToolButton` | 灰描边 + `SurfaceHigh` 底（StreamClip 样式） |
| `ProgressHeader` | 线性进度 + 主副文案 + 取消 |
| `ResultTable` | 横向可滚动；首列冻结引擎名；**所有单元格行高固定 52dp**，表头带单位（`Load ms` / `TTFT ms` / `Prefill tok/s` / `Decode tok/s` / `RSS peak MB` / `温度 ℃`） |
| `WarningBanner` | `Warn` 底色 12% 透明，一行可关闭 |
| 状态栏 | 透明；图标亮度随 `Bg`（PixelPlayer StatusBar 处理） |
| `formatModelSize` | 模型体积，**十进制**单位（与模型站标称一致）；`.so` 体积走二进制单位，见 Settings |

### 4.3 底部导航

- 4 Tab：`聊天` / `模型` / `评测` / `设置`（M3 NavigationBar）
- 选中：`Primary` 图标+标签；未选：`TextSecondary`
- **4 个 tab 全部常驻**，不做降级到溢出菜单（2026-09-27 决策：宽度充裕）

### 4.4 控件三层职责

同一种主色填充按钮同时表达"选中 / 主操作 / 次级"会让每页都长出一排紫块，真正该突出的
主操作反而被淹没。固定三层，不再漂移：

| 层 | 控件 | 表达什么 | 反例 |
|----|------|----------|------|
| L1 筛选 / 选中 | `ChoiceChipRow`（`FilterChip`） | 一组互斥选项里"当前是哪个"：引擎、下载源、下载过滤、主题模式 | ✗ 用 Filled 按钮表示选中——未选项会看起来像禁用 |
| L2 主操作 | `PrimaryButton`（`Primary` 填充） | 这一屏最想让用户点的那**一个**动作 | ✗ 一排 Filled；✗ 每个列表行都挂一个 Filled |
| L3 次级 | `OutlinedToolButton` | 其余全部动作：取消、恢复默认、校验、删除、导出、浏览 | — |

「一屏一个 Filled」按**当前可见状态**判定，不是按代码出现次数：

- 聊天页：未加载时 Filled = 「启动」，发送钮灰显；`READY` 后「启动」降级为 Outlined「停止」，
  Filled 让给「发送」。两者永不同时为 Filled。
- 评测页：未跑时 Filled = 「开始评测」；跑起来后 Filled = 暂停态的「继续」。
- 列表行：只有在"这一行本身就是该屏唯一操作"时才允许出现 Filled。否则整屏留一个
  （模型市场的「刷新状态」因此从每行一颗提到工具栏一颗）。

### 4.5 Compose 表单的两个反直觉事实（实测，勿再踩）

1. **触屏模式下点按钮不会移动输入焦点**。所以"失焦时提交"在本 App 里根本不触发
   （点「深色」按钮后输入框 `focused` 仍为 `true`）。`NumericField` 因此选择**每次输入即提交**，
   本地 buffer 只负责让 `0.` 这类中间态留在屏幕上。
2. **`"0.".toFloatOrNull()` 是合法的**（= `0.0f`，不是 null）。所以"解析失败就不提交"挡不住半截
   输入：把 `0.7` 删成 `0.` 会静默把值写成 `0.0`。必须额外挡"末尾是小数点"这一种形态。

---

## 5. 分页交互

### 5.1 聊天（Chat）

```mermaid
flowchart TD
  A["作用域条: ● 引擎 · 模型 ⌄"] -->|整行可点| B[ScopeSheet 两级选择]
  B --> B1[引擎 chip 行 + 状态点]
  B --> B2[该引擎的模型列表 + 选中勾]
  C[状态行 + 启动/停止 + 新建会话] --> D[消息列表 + 回到底部]
  E["参数 chip: temp 0.7 · tok 128"] -->|点击| F[SamplingSheet]
  D --> G[输入框 + 发送]
```

- **作用域条（顶栏第一行，整行可点）**：`● 引擎名 · 模型名 ⌄`，底色 `SurfaceHigh`、12dp 圆角。
  - **引擎和模型合并成一个入口**，不再是两个半宽下拉。模型从属于引擎（`DESIGN §1.2` 四种格式
    互不通用），两个平行下拉把这个层级藏起来了，还会把长模型名截断成两行。
  - 作用域条**本身不是引擎选择入口**：引擎只在弹层内部切换，否则又变成两个入口。
  - 状态点按 §7.2 取色；实心 = 该模型已加载，空心 = 可用但未启动。
- **状态行（顶栏第二行）**：状态句（weight 1，最多 2 行）+ 「启动/停止」（40dp）+「新建会话」图标按钮。
  溢出菜单只有一个条目，故直接给图标按钮。
- **两级 `ScopeSheet`（`ModalBottomSheet`）**：标题 + 引擎 chip 行（带状态点，不可用者灰显但仍可点）
  + 选中引擎不可用时的一行原因 + 分隔线 + 模型列表。模型条目：名称（1 行 ellipsize）
  + 副行 `引擎 · 量化 · 体积` + 右侧选中勾。列表随引擎 chip 联动。
  - **弹层里的选择仍然只是"选中"**：按 `DESIGN §1.2`，选引擎或模型只释放旧会话回到 `IDLE`，
    **不自动 load**；加载仍由「启动」触发。弹层只换外壳，不动契约。
  - 选中模型后自动收起弹层；只切引擎时保持打开，方便接着选模型。
- 模型生命周期：`SessionState = IDLE / LOADING / READY / FAILED`。「启动」先卸载旧会话再 `load()`，
  READY 后按钮变 Outlined「停止」（释放模型）。多模型驻留开启时保留驻留会话
- LOADING 态：整页覆盖 `scrim(0.32)` 全屏遮罩，中央卡片显示「正在加载模型…」+ 模型名
  + 「首次加载需数十秒，请勿离开此页」。遮罩吞掉触摸事件，**底部导航在其外层、仍可切换 Tab**。
  加载是阻塞 native 调用且不可中断，故不提供取消；`load()`/`unload()`/`reset()` 一律走
  `Dispatchers.IO`，否则主线程被占满、LOADING 状态根本渲染不出来
- 消息：用户右/助手左气泡；助手气泡下挂 `MetricPill`（TTFT、本次 tok/s）。
  **引擎未产出任何 token 时补一条「（空回复，可重试）」气泡**，状态行记为「完成（无输出）」，
  避免出现无反馈的空轮次
- **自动滚动三件套**：新内容即滚（按**尾条内容长度**触发，因为流式 token 是替换最后一条气泡、
  `messages.size` 全程不变）／用户上滚即停跟随／非底部时右下角悬浮「回到底部」按钮
- **采样参数**：输入框上方一颗参数 chip（`AssistChip`，显示 `temp … · top_p … · tok …`），
  点击打开 `SamplingSheet`：temp / top_k / top_p / threads / backend / maxNewTokens + 底部「完成」。
  默认值取自 Settings；不适用当前引擎的字段灰显 + 12sp 说明（`DESIGN §1.2` 字段适用性）。
  此项为 `DESIGN §4` 信息架构要求，P0 已实现，**不可移除**——只是从内联折叠卡搬进 sheet（决策 4）
- 输入：多行，发送钮 `Primary`；生成中变「停止」。**仅 READY 可发送**，否则灰显 + 占位提示
  「启动模型后可发送消息」
- 空态：「先在「模型」页添加模型，再回到这里启动它」
- 错误：气泡内红色短行，不弹窗打断

### 5.2 模型（Models）

- 两个 Tab：`已导入` / `模型市场`。页头显示共享模型根目录
- **`已导入`**：
  - 顶部「添加 / 导入第三方模型」卡：引擎选择用 `ChoiceChipRow`（横向滚动 chip，替掉原来 4 个
    等宽按钮——`LiteRT-LM` / `llama.cpp` 会被断成 `LITER T`），引擎格式提示一行；
    显示名 + 源路径两个输入框（**路径框的尾部图标按钮**开内置文件浏览器）；
    底部三颗动作按 §4.4 分层：`导入并复制到模型目录` 是唯一 Filled，`仅引用原路径` /
    `扫描模型根目录` 走 Outlined
  - 已导入列表：名称 + `引擎 · 格式 · 量化` + 路径 + 「校验」「删除」（均 Outlined）
  - 添加：绝对路径 + **内置文件浏览器**（按引擎过滤：`.gguf` / `.litertlm` / `.task` 文件，
    MNN/Genie 选目录；需 `MANAGE_EXTERNAL_STORAGE`，未授权仅可浏览 App 私有目录并给授权引导；
    其他 App 的 `Android/data/` 灰显不可选）。SAF 降级为可选外部分享入口，P5 不强制（`DESIGN §1.3`）
  - 校验失败：行内红字原因（缺哪个文件写哪个）
- **`模型市场`**：下载源 chip 行（`HF官方 / HF镜像 / ModelScope`）+ 下载过滤 chip 行
  （`全部 / 已下载 / 未下载`）+ 说明行末的**一颗**「刷新状态」（不再每个条目挂一颗）；
  条目：名称 / `引擎 · 厂商 · 体积` / tags / 描述 / 下载进度；动作按 §4.4 分层，
  `下载` 或 `已下载 · 添加到列表` 是唯一 Filled
- 引擎与厂商显示名一律走 `EngineId.displayName` / `engineIdFromStorage()` 反解，
  **不得**直接打印持久化的 `EngineId.name`（详见 `docs/UI_REVIEW.md` §4.1 P0-1）

### 5.3 评测（Benchmark）— P4 主界面

```mermaid
flowchart TD
  A[引擎×模型配置] --> B[提示词 Chip + 用例 L/P/D/T]
  B --> C[折叠参数: warmup/runs/maxNewTokens]
  C --> D[Primary 开始评测]
  D --> E[ProgressHeader 实时]
  E --> F[ResultTable + 免责句]
  F --> G[Outlined 导出 JSON]
```

- **配置**：可用引擎默认勾选；勾选后展开 `LabeledDropdown`；提示词 4 Chip 单选；用例默认 L/P/D
- **跑前**：`WarningBanner`「建议插电、静置冷却（>42℃ 仅警告）」
- **进行中**：`引擎 2/3 · Decode · 样本 3/4`（样本数含 warmup，默认 warmup=1 + runs=3 即共 4）+ 最近 `decode xx tok/s`；可取消
- **退后台**：自动暂停（DESIGN §3.3）；返回时 ProgressHeader 呈暂停态（`Warn` 描边），提供「继续 / 放弃本次评测」两动作，暂停区间不计时
- **结果表列**：`引擎 / 模型 / Quant / Load ms / TTFT ms / Prefill tok/s / Decode tok/s / RSS peak MB / 温度 ℃`
  - **表头必须带单位**：只写 `Load` 无法判断是 ms 还是 s，行内数字又不带单位
  - 失败格：`Error` 色短因，不显示 0
  - 空值：`—`
- **结果表下方** Caption：「跨模型/跨量化只作参考，不构成绝对快慢结论」
- **历史**：列表可清空（引擎列同样走 `engineIdFromStorage()` 反解，Room 里存的是 `EngineId.name`）；
  导出路径提示 Snackbar

### 5.4 设置（Settings）

- 默认采样：temp / top_k / top_p / threads / maxNewTokens / backend
  - 数值框全部走 `NumericField`（§4.5 的两条反直觉事实即出自此处）
  - **backend 是 `LabeledDropdown`，不是只读输入框**——只读 `OutlinedTextField` 长得像能输入，
    点了没反应；候选为该引擎支持的 backend
- 内存：**多模型驻留开关**（默认关 = 单模型驻留，切换即 unload；`DESIGN §3.3`），开启行附 12sp 警告「8GB 机型易 OOM」
- 系统提示词：默认 `You are a helpful assistant.`，可改可清空（清空会导致 MNN 上极短首轮直接出 EOS，
  详见 `docs/mnn.md` §2）
- 数据：导出目录、清空 benchmark 库
- 外观：深色 / 浅色 / 跟随系统，用 `ChoiceChipRow` 表达选中（**不是** Filled/Outlined 按钮对，§4.4）
- 关于：依赖与许可摘要、原生库 MD5

---

## 6. 文案与状态规范

| 场景 | 文案模式 | 示例 |
|------|----------|------|
| 不可用 | 原因 + 一句怎么办 | 「QAIRT 未打包，Genie 不可用；用 -PskipGenie 外的构建包重装」 |
| 加载失败 | 失败点 + 检查项 | 「缺少 genie_config.json」 |
| 生成失败 | 现象 + 动作 | 「空回复，已重试 2 次仍失败」 |
| 成功 | 短确认 | 「已导出 bench_…json」 |
| 数字空 | 单字符 | `—` |
| 禁用控件 | 灰显 + 12sp 说明 | 不单独弹窗 |

原则：**不堆英文缩写**；首次出现写「首 token 延迟（TTFT）」。

---

## 7. 标志性时刻（记忆点）

1. **评测结果表**：Metric 大数字 + 青强调，一眼看出谁快；行展开看样本（**尚未实现**，见 `docs/UI_REVIEW.md` R4）
2. **四引擎状态条**：绿/灰点阵列，缺依赖一目了然且不崩

### 7.1 引擎状态点 `●` 的三态映射

一处定义（`StatusDot`），四处复用（作用域条 / ScopeSheet 引擎 chip / `EngineStatusCard` / 未来评测页）。

| 引擎可用性 / 会话态 | 颜色 | 形态 | 语义 |
|---|---|---|---|
| `Available` + 会话 `IDLE` | 绿 `Ok` | 空心环 | 可用，未加载 |
| `Available` + 会话 `READY`（当前模型已加载） | 绿 `Ok` | 实心点 | 正在使用 |
| 加载中 / 生成中 | 琥珀 `Warn` | 实心点 | 忙 |
| `MissingDependency` / `UnsupportedSoc` / `ModelNotConfigured` / `InvalidModel` | 灰 `TextDisabled` | 实心点 | 不可用 |

- 不可用引擎在 chip 行里**只降文字透明度，不禁用点击**——它必须能点开，才能读到一行原因。
- 原因文案（`EngineChoice.unavailableReason()`）由状态行与 ScopeSheet 共用，不许各写一份。

---

## 8. 明确不做

- ❌ 雷达图 / 3D / 装饰插画
- ❌ 音乐播放器式大字展示字体
- ❌ 强制锁温、飞行模式弹窗
- ❌ 自动滚动马灯、循环动画

---

## 9. 实现映射

| 文档项 | 代码落点 |
|--------|----------|
| Color/Type | `app/src/main/java/.../ui/theme/{Color,Type,Theme}.kt` |
| 组件（含 §4.4 三层职责、§7.1 状态点） | `app/src/main/java/.../ui/components/UiComponents.kt` |
| Chat 作用域条 / 两级 sheet / 参数 sheet | `ui/chat/ChatScreen.kt`；选择契约 `ui/chat/ChatViewModel.kt` |
| Models 三行 chip / 控件分层 | `ui/models/ModelsScreen.kt` |
| Benchmark 页 | `ui/benchmark/BenchmarkScreen.kt` + `BenchmarkViewModel.kt` |
| Settings 数值框 / backend 下拉 / 外观 chip | `ui/settings/SettingsScreen.kt` |

**验收**：四页同套 token；深浅色可切换；每屏最多一个 Filled 主操作；选中态一律 chip；
评测页在无模型时引导清晰，有结果时表+免责句齐全（表头带单位）。
