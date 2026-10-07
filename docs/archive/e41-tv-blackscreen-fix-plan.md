# E41「世界」电视端黑屏：根因分析与解决方案

> 状态：**已裁决并收口**（2026-10-07）——**不修**，保留原观感，改为**只在最高画质档提供**
> 首版：v1.0（2026-10-06） · 全部结论来自创维 9R54_G8S 真机实测，**无一条是属性推断**
> 相关：§10.201（2026-09-29 的 ES5 降级修复）、§10.197（E41 改 three-globe）、`assets/globe/globe.js`

---

## 0. 结论先行（2026-10-07 终稿）

排查最终定位到**两个互相独立的阻断点**，且第二个比第一个更深：

**阻断点 1（已绕开，但最终未采用）**：`three-globe 2.45.2` 的 UMD 把 TSL 子系统整段打进包里，
TSL 在**模块初始化期**就 `new Proxy(...)`；Chrome 39 没有 `Proxy`，ES5 也无法实现（要拦截
`.setLayout` 这类未知键名，必须能拦未枚举的键访问）⇒ 结构性死路。
**已按方案 A 完成原生 three.js 地球**并真机验证正确（见 §2.2）。

**阻断点 2（真正的拦路虎，方案 A 解决不了）**：
**这台电视的 WebView 被 Compose `AndroidView` 承载时，只画出第一帧，之后永不更新。**
与 WebGL 无关——普通 2D canvas 同样不上屏。详见 **§2.2**。

⇒ **最终裁决（所有者）**：观感最好的一版**原样保留**（手机上完美显示），
E41 改为**只在最高画质档（HIGH）提供**，中/低档跳过；老旧设备上它就是黑屏，**接受**。
实现：`VisualizerTheme.WORLD` 由 `Tier.ADV` 提到 `Tier.ULTRA`。

**本次排查沉淀下来的东西（比修复本身更值钱）**：
- 一套**可复用的真机取证方法**（§4）——尤其「画布回读 vs `screencap`」这一对判据，
  它是唯一能把「画了但没上屏」和「画出来是黑的」分开的手段；
- 三个**已排除项**（§2.3），避免日后重蹈。

---

## 0.1 初版结论（v1.0，2026-10-06，已被 §0 终稿取代，保留供追溯）

**手机正常、电视黑屏的根因只有一个：电视的系统 WebView 是 Chrome 39，它跑不动 three-globe 2.45.2。**
不是性能、不是纹理、不是 Kotlin 桥接、不是 WebView 拦截——这些都已排除或已修好。

`three-globe` 的 UMD 包把 **TSL（Three Shading Language）着色器子系统**整段打进了包里，
而该子系统在**模块初始化期**就 `new Proxy(...)`。Chrome 39 既没有 `Proxy`，ES5 也无法实现它。
⇒ **`three-globe` 在这台电视上无法加载，且不是靠打补丁能救的。**

同时实测确认：**纯 three.js r160 在这台电视上完全能跑通、能渲染。**
（PowerVR Rogue G6110 / GLSL ES 1.0 / 纹理 2048×1024 解码成功 / `readPixels` 取到真实受光像素。）

⚠️ **这段结论只解释了「为什么加载失败」，没有解释「为什么画不上屏」**——后者是 §2.2 才查出来的。

---

## 1. 真机取证

设备：`192.168.0.114:5555`，`adb` 全路径 `C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe`。

| 项 | 实测值 | 采集命令 |
|---|---|---|
| 机型 / 系统 | `9R54_G8S` / Android **5.1.1** / SDK **22** | `getprop ro.product.model` 等 |
| WebView provider | **空**（无 Play WebView，用 AOSP 自带、不可更新） | `dumpsys webviewupdate` |
| 引擎 | `Chrome/39.0.0.0`，`AppleWebKit/537.36` | 探针页 `navigator.userAgent` |
| 装包 | v2.38.3 / versionCode 172 / **DEBUGGABLE** | `dumpsys package` |
| 资产 | `three.es5.js`、`three-globe.es5.js`、`polyfill.es5.js` **齐全**（拉取 base.apk 解包核对） | `adb pull` + 解包列目录 |

### 1.1 应用内症状

切到 E41 后 logcat 每秒约 10 条：

```
E/WorldGlobe: JS[ERROR] Uncaught ReferenceError: WorldGlobe is not defined @:1
```

与 §10.201 记录的老症状**一字不差**。该 `ReferenceError` 来自 Kotlin 侧 100 ms 一次的
`WorldGlobe.setAudio(...)` 注入，说明**页面活着、但 `window.WorldGlobe` 从未被赋值**。
`globe.js:35` 的 `if (!THREE || !ThreeGlobe) return;` 正是这条链的起点。

> ⚠️ **诊断盲点**：`three.es5.js` 加载时的 `SyntaxError` **没有**出现在 logcat 里
> （`WebChromeClient.onConsoleMessage` 在这台 WebView 上不转发脚本加载期错误），
> 只看到我们注入脚本自己报的 `ReferenceError`。这是 §7 里「自诊断」那条建议的由来。

### 1.2 隔离探针

用电视自带浏览器（`com.skyworth.tv_browser/.BrowserActivity`，同一套 WebView 引擎）加载
`file:///data/local/tmp/globe_probe*/probe*.html`，把结论直接**画在屏幕上**，再 `screencap` 读回。
四轮递进，每轮只解除一个阻断：

| 轮次 | 装入内容 | 屏幕上的结论 | 截图 |
|---|---|---|---|
| ① 基线 | polyfill + three.es5 + three-globe.es5（现状资产） | `SyntaxError: In strict mode code, functions can only be declared at top level or immediately within another function` @ `three.es5.js:2715` / `three-globe.es5.js:12579` → `THREE = undefined` | `output/e41-tv-03-probe1-strictmode-syntaxerror.png` |
| ② 去掉 `"use strict"` | 同上，两文件各删 1 行 | `THREE = object REVISION=160` ✅　`ReferenceError: Proxy is not defined` @ `three-globe.es5.js:10401` | `output/e41-tv-04-probe2-proxy-missing.png` |
| ③ 加最小 `Proxy` 垫片 | 同上 + 垫片 | `TypeError: undefined is not a function` @ `three-globe.es5.js:10976` | `output/e41-tv-05-probe3-setlayout.png` |
| ④ **可行性闸门一** | polyfill + **只用 three.es5** | `THREE r160` / `PowerVR Rogue G6110` / `WebGL GLSL ES 1.0` / `MAX_TEX 8192` / `WebGLRenderer(isWebGL2=false)` / `render()` 无异常 / `earth_lit.jpg` 解码 2048×1024 / 解码后中心像素 `78,89,98` | `output/e41-tv-06-probe4-threejs-renders.png` |
| ⑤ 贴图渲染 | 同 ④，等纹理解码后重绘 | 真实卫星图地球（撒哈拉/红海/阿拉伯半岛/印度清晰、昼夜明暗正常） | `output/e41-tv-06b-probe6-texture-rendered.png` |
| ⑥ **可行性闸门二（方位·网格法）** | 同 ⑤ + `fe()` 生成的经纬网格（每 30°）+ 经纬度标签 + 城市标签 | 网格与真实地理**重合**（圆心=90°E：赤道交点是孟加拉湾、北京在右上陆块上、开普敦左下临边、悉尼右下临边）⇒ **yaw = 0 正确**。⚠️ 第一轮「肉眼估坐标」结论已被所有者质疑作废，见 §2.1 | `output/e41-tv-08-graticule-verification.png` |

> 截图 ④ 里画布未出现，是探针自己的 `flush()` 用 `innerHTML` 覆盖掉了已插入的 canvas，与电视无关；
> `readPixels` 取到真实受光像素已足以证明 GL 侧渲染正常。闸门二改把 canvas 放进独立容器并
> 跟随视口尺寸，规避了电视 DPR 1.5 导致的放大裁切。

---

## 2. 根因：三个逐层递进的阻断点

### 阻断点 1 — ES5 严格模式的**早期错误**（`SyntaxError`，整文件被拒）

```
three.es5.js:2715   function C() { A_1.dispose(); s.delete(c); c.removeEventListener("dispose", C); }
                   ↑ 位于 2698 行的 if (...) { ... } 块内
```

ES5 规定：**严格模式下，函数声明只能出现在顶层或函数体直接层，不能出现在块内**
（`if` / `for` / `try` / `switch` 的 `{ }` 中）。Chrome 39 的 V8 把它当**解析期早期错误**，
整个 `three.es5.js` **直接不执行** ⇒ `window.THREE` 从未定义。

这是 `tsc` 降级产物（`downlevel_libs.mjs`，2026-09-29）的固有形态：
它降的是语法，不会把源文件里本来就写在 `if` 块内的函数声明改写成 `var f = function(){}`。

**关键事实：上游基线同样是严格模式、同样有块内函数声明。**
`three.min.js`（偏移 551）与 `three-globe.min.js`（偏移 329）都含且仅含 1 处 `"use strict"`。
⇒ 这不是降级器引入的缺陷，是 **three.js r160 的 UMD 产物在 ES5 引擎上本来就不可加载**。
所谓「降级成 ES5」从来不足以修复电视端，只是把失败点从「`class` 语法错误」推到了「块内函数声明」。

### 阻断点 2 — `Proxy` 缺失（`ReferenceError`，three-globe 初始化中断）

```
three-globe.es5.js:10401   return new Proxy(function () { }, { apply: ..., get: ..., set: ... });
```

这是 three 的 **TSL `Fn()` 包装器**。全文件共 3 处 `new Proxy`（10203 / 10210 / 10401）。

> ⛔ **推翻 §10.201 的一条既有判断**：当时写的是
> 「`new Proxy` 出现 ×3，全部位于 three 的 TSL / node material 子系统……`globe.js` 只用
> `MeshPhongMaterial`，不走 node material，这些代码**不会执行**，故不补亦安全——但这是
> 『当前配置下安全』」。**实测证明它确实在模块初始化期就执行了**，模块初始化在中途抛异常，
> `ThreeGlobe` 全局压根没被赋值，后面的「当前配置下安全」无从谈起。
> 这正是 §10.201 自己总结的那条教训——「产物能被解析 ≠ 产物能正确运行」——第二次应验。

### 阻断点 3 — ES5 无法实现 `Proxy`（**结构性死路**）

补了最小 `Proxy` 垫片（只实现 `apply`/`get`/`set`）后，死因推进到：

```
three-globe.es5.js:10976
Cv = ry(function (_j) {...}).setLayout({ name: "sRGBTransferEOTF", ... }),
    Rv = ry(function (_j) {...}).setLayout({ name: "sRGBTransferOETF", ... })
    ^ TypeError: undefined is not a function
```

`ry()` 即 `Fn()`，返回 `new Proxy(...)`；TSL 在**模块顶层**紧接着对它调 `.setLayout()`。
`setLayout` **不是目标函数的自有属性**，要拿到它必须让 `get` trap 拦截一个**未知键名**。
ES5 没有这个能力——只能对**已知的键**用 `Object.defineProperty` 建转发访问器，
而 `setLayout` / `once` / `toStack` / `toVarIntent` 这类名字无法预先枚举。

⇒ **Chrome 39 上不存在让 three-globe 2.45.2 初始化的可行补丁。**
（唯一理论解是自带一个完整 ES5 Proxy 实现，仍然接不住未知键名这条路。）

### 可行性闸门：three.js 本体没问题

第 ④ 轮证明：

| 项 | 结果 |
|---|---|
| `THREE.REVISION` | `160`（完整加载并初始化） |
| GPU | `PowerVR Rogue G6110`（**真实硬件，非软渲染**，与 §10.201 探针一致） |
| 着色器 | `WebGL GLSL ES 1.0`，`isWebGL2 = false`（走 three 的 **WebGL1 回退路径**） |
| `MAX_TEXTURE_SIZE` | `8192`（4096×2048 贴图绰绰有余；实测贴图是 2048×1024） |
| `new WebGLRenderer({antialias:true})` | 成功 |
| `render()` | 未抛异常 |
| 纹理解码 | `earth_lit.jpg` 2048×1024 成功 |
| `readPixels` 中心 | `78,89,98` —— **真实受光的地球像素** |

这同时**结清了 §10.201 的第 2 条待确认**：「three r160 的 WebGL1 路径能否在 Chrome 39 的
ANGLE→GLES3 上编译着色器」——**能**。

### 2.1 可行性闸门二：贴图渲染 + 经纬方位（2026-10-06 补测，两轮）

闸门一只证明了「像素非黑」。**方案 A 的核心交付物是「一张贴了图的球 + 城市点落位正确」**，
而项目历史上正是在这里翻过车（§10.201 记录过「夜面偏 90° ⇒ 正确的城市点看起来全落进海里」）。

**第一轮 —— 方法不合格，仅存档不作证据**：渲染贴图球 + `WorldCities` 37 城光点 + 投影中文标签，
凭**肉眼估屏幕坐标**得出「逐一吻合」。
⚠️ **该结论被所有者当场质疑并要求复核**——肉眼估坐标正是 §10.201 明文警告的做法。
截图 `output/e41-tv-07-probe8-texture-and-orientation.png` 存档但**不作为证据**。

**第二轮 —— 网格法，可复现**：把 **`fe()` 生成的经纬网格**画到球面上（青=经线每 30°、
橙=纬线每 30°，每条带经纬度标签）。网格来自 `fe()` 假设，**贴图有自己的固有朝向，
两者是否重合就是判据**，全程不需要肉眼估算。

结果（`output/e41-tv-08-graticule-verification.png`，相机 `θ=-π/2` ⇒ **圆心经度 = 90°E**）：

| 检查项 | 预期 | 实测 |
|---|---|---|
| 圆心赤道交点 | 90°E 赤道 = 孟加拉湾（应为水） | ✅ 深蓝水域 |
| 撒哈拉 / 北非 | 圆心左 ~70° | ✅ 陆块位置正确 |
| 北京 116.58E | 圆心右 26.6°、北纬 40° | ✅ 右上且**落在陆块上** |
| 东京 139.78E | 圆心右 49.8° | ✅ 更靠右上临边 |
| 开普敦 18.42E | 圆心左 71.6°、南纬 34° | ✅ 左下临边 |
| 悉尼 151.18E | 圆心右 61.2° | ✅ 右下临边 |
| 伦敦 / 纽约 / 圣保罗 | 距圆心 >90° | ✅ 全部不可见（正确） |

⇒ **网格与真实地理重合 ⇒ `fe()` 与 `SphereGeometry` 原生 UV 自洽 ⇒ yaw = 0 成立。**

> ⚠️ **第二轮自己也翻了一个车（同样值得记）**：相机转角设成 `-π/2`，却在注释里声称
> 「本初子午线正对屏幕中心」。实测 `0°` 落在**左侧临边**、圆心是 **90°E**
> （θ=-π/2 ⇒ 圆心经度 = 90°E）。标签没画出来时，这个错误会被一路带进结论。
> **教训：探针里每个「我以为」都要有屏幕上可见的读数兜底。**

> ⚠️ 两个探针自身的坑（都不是电视的问题）：
> ① 首版在纹理解码完成前就 `render()` ⇒ 截出一颗**全黑球**，一度像新问题；
> ② 网格版把 `fe()` 返回的**普通数组**当 `Vector3` 用，`draw()` 第 1 帧抛异常，
>    异常又打断 `setTimeout` 链 ⇒ 后续帧从未执行 ⇒ **又是一颗黑球**。
> ⇒ **动画循环内的异常必须 try/catch 兜住**，否则一次报错就等于「停在黑屏」。

> ⛔ **本测试修正了一个错误假设**：原以为原生球体需要 `rotation.y = -π/2` 才能把贴图 UV
> 对齐到经纬（该值抄自 `globe.js` 的 `EARTH_YAW_OFFSET`）。**实测 yaw = 0 就是对的** ——
> `THREE.SphereGeometry` 的原生 UV 已与 three-globe 的 `fe()` 约定自洽。
> `EARTH_YAW_OFFSET = -π/2` 是 **three-globe 内部球体朝向**的补偿值，不是地理事实。
> ⇒ 方案 A 的地球球体**不加 yaw**；夜面球壳必须**与地球用同一个 yaw（0）**，
> 而 `NIGHT_LNG_CALIBRATION`（夜面贴图自身的经度偏差旋钮）需重新对真机调一次。

---

## 2.2 ⭐ 阻断点 2：WebView 只画第一帧（2026-10-06 夜，方案 A 验证时才发现）

方案 A 落地后在电视上装包实测，**画面仍然全黑**。逐层加埋点后定位如下。
⛔ 这一节是全文**最贵**的取证过程（约 6 轮构建 + 装机），值得完整留档。

### 现象与读数

`globe.js` 侧的 `diag()`（Kotlin 侧 `evaluateJavascript` 周期性取回）显示**一切正常**：

```
three: 160   globe: true   mode: native   errs: []
frames: 2653   frameError: null   ctxLost: false
canvas: [1280,720]   sceneChildren: 7   groupChildren: 5   cities: 37
earthMapSize: [2048,1024]   nightMapSize: [2048,1024]   glowMapSize: [2048,1024]
renderMs: 3   renderMsMax: 10426        ← 首帧 10.4s，之后稳定 3ms
px: ["14,16,25","16,25,31","4,6,12","24,33,44","2,3,6"]   ← 画布回读有真实像素
```

但同一时刻的 `adb screencap`：**整个地球区域是精确的 `(0,0,0)`**。

### ⭐ 判定方法（本次最有价值的沉淀）

| 判据 | 结论 |
|---|---|
| ① `frames` 时间序列（周期复查，不是单次） | 3 → 3 → 12 → **161 → 2653** ⇒ **rAF 没死**，只是首帧 10.4s |
| ② `renderMs` 计时（只测 `render()` 一段） | 首帧 10426ms，之后 **3–9ms** ⇒ 稳态渲染完全正常 |
| ③ **画布回读**（`drawImage` 到 2D 画布取像素） | 有真实像素 ⇒ **场景确实画出来了** |
| ④ `screencap` 量化 | 精确 `(0,0,0)` ⇒ **内容没上屏** |
| ⑤ 把页面 CSS 底色改成可见的深蓝 | 屏幕上出现**底色** ⇒ WebView 的 DOM 层在合成 |
| ⑥ 放一个**普通 2D canvas**红方块进去 | **也不显示** ⇒ ⛔ 与 WebGL 无关 |

⑥ 是决定性的一步：它把「WebGL 的合成失败」与「WebView 整体只画首帧」彻底分开。
⇒ **结论：这台电视的 WebView 被 Compose `AndroidView` 承载时，只画出第一帧，之后永不更新。**

⚠️ **③ 必须紧跟 `render()` 在同一个任务里做**（`preserveDrawingBuffer` 默认 false，
一旦交给合成器，`drawImage` 读到的就是空）——否则会得出「场景没画出来」的错误结论。

### 探针为什么看起来是好的（假阴性）

电视自带浏览器（`com.skyworth.tv_browser`）里跑同一批文件，地球**正常显示**。
⛔ 但那个探针里 **`file://` 页面无法 XHR 其它 `file://` 子资源**（Android 5.1 默认关闭），
三张贴图**一张都没加载**（`diag` 在 App 里显示的是 2048×1024，探针里必然是空的）
⇒ 探针跑的是一个**空场景**，成本低得多，也完全没暴露宿主合成问题。
**教训：探针必须复现宿主条件，否则它的「正常」不能用来否定 App 里的「异常」。**

### 2.3 已排除项（别再走）

| 尝试 | 结果 |
|---|---|
| `preserveDrawingBuffer: true` | ⛔ 无效，屏幕仍只显示首帧 |
| `setLayerType(LAYER_TYPE_SOFTWARE)` | ⛔ **更糟**：直接 `Error creating WebGL context`，WebGL 彻底起不来 |
| `webView.onResume()` + `resumeTimers()` | ⛔ 无效（`AndroidView` 挂载路径本就会走 `onAttachedToWindow`） |
| 把 WebView 移出 Compose、改挂 Activity 根 | ⏸ 未做（会盖住歌词与指示器，需改全 App 共用的舞台代码） |

### 2.4 为什么最终选择「不改」

方案 A（原生 three.js）本身是**完成且验证正确**的资产——它证明了 Chrome 39 能跑纯 three.js。
但它**解决不了阻断点 2**：只要 E41 继续用 WebView 承载，这台电视就是黑屏。

所有者裁决（2026-10-07）：**观感最好的原版保留**（手机上完美显示，这是主要使用场景），
E41 改为**只在最高画质档提供**，老旧设备上黑屏**接受**。
⇒ `VisualizerTheme.WORLD`：`Tier.ADV` → **`Tier.ULTRA`**（`supports()` 对 ULTRA 的门槛是
`allowFramebuffer`，该字段只有 HIGH 为 `true`）。
⛔ `needsParticleBudget = true` **照实保留**（渲染器确实读 `ctx.quality.maxParticles`，
`ParticleBudgetGateTest` 用源码扫描反推真值集合，标错就红）。

---

## 3. 为什么手机正常、电视黑屏

同一份 assets，差别全在 WebView 引擎版本：

| 能力 | 手机（现代 WebView） | 电视（Chrome 39） | 后果 |
|---|---|---|---|
| `class` / 箭头 / `let` / 模板串 | ✅ | ❌ | ES6 原版包加载不了（§10.201 已修） |
| 严格模式下块内函数声明 | ✅（ES2015 起合法） | ❌ **早期错误** | tsc ES5 产物整文件不执行 |
| `Proxy` | ✅（Chrome 49+） | ❌ | three-globe 初始化抛错 |
| 未知键名 `get` 拦截 | ✅ | ❌ | 垫片无效 |
| WebGL1 渲染 | ✅ | ✅ | 不是问题 |

一句话：**手机对这三道门全部免疫，电视一道都过不去。**

---

## 4. 为什么上一轮验证没抓到（这节是本文最重要的一节）

§10.201 建立了「四道验证关」：① acorn `ecmaVersion:5` 语法解析 ② 对照组 ③ Node `vm` 加载 ④ 基线，
12/12 通过。**但这四关全部跑在 Node 的现代 V8 上，而现代 V8 在这两点上恰好比 Chrome 39 宽松**：

| 验证手段 | 对本次缺陷的失效方式 |
|---|---|
| acorn `ecmaVersion:5` 解析 | acorn 8 **不强制**「严格模式块内函数声明」这条早期错误（它按 ES2015 语义放行），⇒ 全部通过 |
| Node `vm` 删 20 个 API 模拟 Chrome 39 | ① `vm` 全局里**块内函数声明合法**；② `Proxy` 在 Node 里存在，且 §10.201 明确记录「`Proxy` 与 `globalThis` 在 vm 全局里不可配置、删不掉」⇒ **已知盲区，恰好就是本次的两个根因** |
| 「Phone 上也是黑的算回归」反向验证 | 上一轮 Babel 产物在现代 WebView 上也崩（真回归），于是被判定为「库加载失败」并归因到 Babel；**修好后没人再回电视复测** |

§10.201 结尾写着「⚠️ 仍待真机确认（本轮按约定不做编译与真机验证）」，
列了 4 条待确认项（刷屏消失 / 着色器能否编译 / 解析耗时 / 手机必须复测）——**这 4 条至今没有执行记录**。
实测证明第 1 条从未达成：症状原封不动。

**沉淀（应写进 `.opencode/rules.md` 或 AGENTS.md）：**

> ⛔ **面向特定旧引擎的产物，验证必须在那台引擎上跑。**
> 用现代 Node / 浏览器做的兼容性验证，只能证明「现代环境可用」，
> 对「旧引擎早期错误 / 全局 API 缺失」**零检出能力**，且这两类恰恰是旧引擎黑屏的主因。
> 本项目的现成做法：把结论**渲染到屏幕**（探针页）+ `adb screencap` 读回——
> 比 logcat 可靠，因为 logcat 在这台 WebView 上不转发脚本加载期错误（§1.1）。

---

## 5. 方案对比

### 方案 A（推荐）：地球改用原生 three.js，弃用 three-globe

**做法**：`globe.js` 里的 `new ThreeGlobe()` 拆成三块原生实现。

| three-globe 能力 | 现状用法 | 原生替代 |
|---|---|---|
| 地球贴图球 | `globeImageUrl('./earth_lit.jpg')` | `SphereGeometry(100, 180, 90)` + `MeshPhongMaterial(map)`，**yaw = 0**（§2.1 已实测验证，⛔ **不要**照抄 `-π/2`） |
| 大气层 | `showAtmosphere/atmosphereColor/atmosphereAltitude` | 反面球壳（`BackSide` + 加法混合），半径 `100 × 1.16`；**`globe.js` 已有同类自定义 shader（夜面球壳）可照抄** |
| 城市光点 | `pointsData` + tier 配色/半径 + 合批 | 4 个 `Points`（按 Tier 分组，4 个 draw call）或单 `Points` + 顶点色 + `ShaderMaterial`；坐标用 §2.1 已验证的 `fe()` |
| 大圆航线 | `arcsData` + 大圆插值 + dash 动画 | `fe(lat,lng)` 球面换算 + 球面线性插值抬弧高 + dash 相位 shader 偏移；**线宽需 `Line2`**（见下方成本项） |
| 球体材质引用 | `globe.globeMaterial()` 改 `emissiveIntensity` | 直接持有 `MeshPhongMaterial` 引用 |

#### 5.0 ⭐ 必须新增的地球组结构（否则会埋雷）

**现状的隐患**：地球球体（藏在 `three-globe` 内部、自带 `rotation.y = -π/2`）、夜面球壳
（每帧 `rotation.copy(globe.rotation)` 再 `+= EARTH_YAW_OFFSET`）、光点、航线**分属不同对象**，
对齐全靠**逐帧手工 copy 旋转**。这三者只要有一个漏抄，夜面灯光就和大陆错位 —— 这正是
§10.201 记录过的真实回归（「上一轮『大陆消失、城市落海』回归的真实成因」）。

**改造后应改成父子结构**，把「逐帧 copy」换成「天然同组」：

```
scene
├── earthGroup                 ← 只做一件事：自转（animate 里累加 rotation.y）
│   ├── earthMesh     yaw 0   · 半径 100 · earth_lit.jpg
│   ├── nightShell    yaw 0   · 半径 100 · earth_night.jpg · 现有自定义 shader 原样保留
│   ├── atmosphere          · 半径 116 · BackSide 加法混合
│   ├── points（Tier 1..4）
│   └── arcs（航线）
├── sunLight   ← 世界空间固定，不进 earthGroup
├── sunSprite  ← 相机空间固定，不进 earthGroup
├── moonPivot  ← 独立公转，⛔ 绝不能进 earthGroup（globe.js:508 已有明文警告）
└── starDust / starBright
```

好处：① `EARTH_YAW_OFFSET` 归零，夜面球壳不需要每帧算偏移；
② 光点/航线与地球**天然同转**，不可能错位；③ 手指拖动（§5.1）只需改 `earthGroup.rotation.y` 一个值。
⚠️ 唯一要重新验证的是 `NIGHT_LNG_CALIBRATION`（夜面贴图与昼面贴图并非同源，实测重叠率约 71%）。

#### 5.0.1 观感对照清单（改造后逐条验收，缺一条就是回归）

| # | 现有观感元素 | 现状归属 | 改造后归属 | 风险 |
|---|---|---|---|---|
| 1 | 地球本体 + 真实卫星图 | three-globe | 原生 `earthMesh` | 低（§2.1 已实测） |
| 2 | 大气层（`#274b7a`，altitude 0.16） | three-globe | 原生反面球壳 | 中（需照抄夜面 shader 范式） |
| 3 | 昼夜分界线 + 夜面灯光 + 大洲辉光 | `globe.js` 自定义 shader | **不动** | 无 |
| 4 | 城市光点 4 级配色/半径/呼吸/拍点 | three-globe + `globe.js` | 原生 `Points` + 现有 `updatePoints` | 低 |
| 5 | 大圆航线 + dash 流动 + lane 分层 | three-globe | 原生 `Line`/`Line2` + dash shader | **高**（§成本项） |
| 6 | 星空双层 + 视差 | `globe.js` | **不动** | 无 |
| 7 | 太阳 sprite（画面右上、随能量呼吸） | `globe.js` | **不动** | 无 |
| 8 | 月球公转 + 倾角 + 月食 | `globe.js` | **不动** | 无 |
| 9 | 相机（fov 50 / near 50 / far 1000 / z 260） | `globe.js` | **不动** | 无 |
| 10 | 灯光配比（ambient 0.18 / sun 3.4 / SUN_DIR） | `globe.js` | **不动** | 无 |
| 11 | 像素比锁 1.0 + antialias 开 | `globe.js` | **不动** | 无 |
| 12 | 音频驱动（自转速度/夜面亮度/辉光/太阳呼吸/光点拍点） | `globe.js` | **不动** | 无 |
| 13 | 航线数随音乐强度、并行车道错开 | Kotlin `WorldGlobeRenderer` | **不动**（契约不变） | 无 |
| 14 | 效果内交互（拖动转地球） | —（新增） | §5.1 模式开关 | 低 |

**读法**：1/2/4/5 是本次真正要动的四块，其余 10 项**一行都不改**。
`WorldGlobe` 的对外契约（`initCities`/`updateRoutes`/`setAudio`/`isReady`）**保持不变**，
所以 Kotlin 侧 `WorldGlobeRenderer` 的 369 行与航线模型全部不用动。

**收益**
- ✅ **唯一被真机实证可行**的 3D 路线（§2 闸门已过）
- ✅ 直接省掉 `three-globe.es5.js` 的 **2,056,088 字节**（APK 内实际大小），三个 `.min.js` 基线另计
- ✅ 消灭 `Proxy` / TSL 整条依赖链，后续升级 three.js 不再受牵连
- ✅ 手机与电视**共用同一份渲染代码**，不需要能力分叉

**成本 / 风险**
- ⚠️ `globe.js` 净增约 250–400 行（大圆插值 + dash 动画是最花时间的部分）
- ⚠️ 手机端目前是好的 ⇒ **存在视觉回归风险**（弧线观感、光点大小、昼夜对比都要重新对真机调）
- ⚠️ `EARTH_YAW_OFFSET` / `NIGHT_LNG_CALIBRATION` 的既有结论要重新验证（three-globe 内部球体朝向由它保证，改原生后由我们自己保证）
- ⚠️ **`arcStroke` 分档线宽需要额外移植 `Line2`/`LineMaterial`**（bundle 实测：`LineMaterial`/`LineGeometry`/`LineSegments2` 在
  `three-globe.min.js` 中出现 14 处、在 `three.min.js` 中 **0 处** ⇒ three-globe 把「粗线」模块自己打进了包里）。
  WebGL 的 `lineWidth` 在绝大多数实现上恒为 1，只有 `Line2`（`three/examples/jsm/lines/`）能做到任意线宽，
  而它**不在 three.js 主 UMD 包里**。二选一：① 一并移植该模块（约 10 KB 纯 three.js 代码，无 `Proxy` 依赖，可 ES5 化）；
  ② 放弃 `KLASS_STROKE` 的三档线宽，全部 1 px，改用颜色/透明度/虚线密度区分 klass。
  ⚠️ 现状 `KLASS_STROKE = {0:1.1, 1:0.75, 2:0.5}` **是生效的**（three-globe 内部走 `Line2`），直接放弃会丢掉用户
  「主线更亮更粗」的既有观感差异，建议选 ①。

**必做前置**：删 `"use strict"`（`three.es5.js:85`、`three-globe.es5.js:91`）
——已在探针②验证有效，但**必须改在 `downlevel_libs.mjs` 里**（`alwaysStrict: false` / `strict: false`），
不能手改产物，否则下次重新转译又会把 `"use strict"` 加回去。

#### 5.1 A 方案要守住的两个不变量（交互 + 天体位置）

**现状场景图是解耦的**（读码核实）：`sunLight` / `nightShell` / `moonPivot` / `sunSprite` / 星空
**全部是 world space 的兄弟节点，没有一个是 `globe` 的子节点**。这个架构必须保持。

##### 不变量一：天体位置不随地球自转改变

| 节点 | 位置来源 | 用户拖动地球后的行为 |
|---|---|---|
| `sunLight` | world 空间固定 `SUN_DIR × 700`（`:92`） | 不动 ⇒ 晨昏线继续随地球扫过 ✅ |
| `nightShell` | 每帧 `rotation.copy(globe.rotation)` + yaw 偏移（`:765-768`） | 自动跟随，大陆与夜面灯光不错位 ✅ |
| `moonPivot` | 独立公转，`rotation.x = MOON_INCLINATION`（`:471-473`） | 不动 ⇒ 月球继续独立公转 ✅ |
| `sunSprite` | 相机空间固定偏移（`:802`，刻意非物理） | 不动 ⇒ 仍在画面右上 ✅ |
| 星空 | 独立反向自转 `-0.00004`（`:785-786`） | 不动 ⇒ 视差关系不变 ✅ |

⇒ **让用户拖动直接写入 `globe.rotation.y` 这一个累加量**（与 `:749` 的自动自转同一个变量），
上面五条**全部自动保持正确，不需要任何新增几何计算**。晨昏线是 `n·SUN_DIR = 0` 的大圆，
只取决于世界空间的太阳方向；拖动改变的只是「哪一面朝向光」，和现在自动自转的行为完全一致。

⛔ **硬约束：只允许绕 Y 轴（水平）拖动。**
一旦允许俯仰/侧倾：①「可见面约 1/3 在夜侧」的构图保证失效（可能整个盘面全亮或全黑）；
② 月球轨道面固定在 XZ 平面（不随地球），地球一歪就与自己的月亮对不上。

##### 不变量二：手机端手势归属（**已裁决：加模式开关**）

**冲突事实**：`VisualizerStage.kt:281-295` 的外层 Box 已绑定 `detectHorizontalDragGestures`
（阈值 80dp），把**水平拖动用于切换效果**；`:274-275` 又把 `DirectionLeft/Right` 绑到切效果。
地球若直接吃掉水平拖动，手机用户就失去既有手势。

**裁决（所有者，2026-10-06）**：在效果上加一个**模式开关**，由用户自选
「滑动 = 切效果」还是「滑动 = 效果内操作」。⇒ **两个手势都不取消，谁也不牺牲。**

**为什么这个开关有地方放（读码核实）**：

| 端 | 开关载体 | 依据 |
|---|---|---|
| 电视 | **`DirectionUp` 键** | `:276` 现为保留的无动作分支（注释「控制栏已移除」）⇒ 天然空位，零冲突 |
| 手机 | **右上角触摸开关** | `:547-555` 的 `FpsBadge` 在同一位置且**默认关闭不常显**（`nasmusic_fps` 全局开关）⇒ 不打架 |

**设计要点**

1. **能力位**：给 `VisualizerRenderer` 加 `val supportsInStageInteraction: Boolean get() = false`，
   `WorldGlobeRenderer` 覆写为 `true`。⇒ 开关**只在声明支持的渲染器上出现**，
   其余 40+ 套效果的手机/电视 UI 逐像素不变。
2. **默认 OFF**：进入舞台/切换效果时重置为「切效果」模式 ⇒ 对现有用户**零回归**；
   首次进 E41 时借效果名 Toast（`:480-501`，已存在 2.5s）顺带提示可开。
3. **鸡生蛋问题已被载体解决**：切回「切效果」用的是开关本身（电视=上键 / 手机=右上角按钮），
   **与被征用的水平手势无关**，不存在「手势被吃掉后无法退出」的锁死。
4. **接线方式**（避免与 Compose 手势系统硬碰）：
   - `VisualizerRenderer` 加 `onStageDrag(dxPx: Float) {}` 默认空实现；
   - `WorldGlobeRenderer` 覆写：按**像素位移**换算角度增量写入 `globe.rotation.y`
     （⛔ 不按帧累加，否则 60/30fps 手感不同），并暂停自动自转 2–3s 后平滑恢复；
   - `VisualizerStage` 的 `pointerInput` 在「模式 ON 且渲染器声明支持」时**改为转发**
     `onStageDrag`，否则维持现状。
5. **电视端同样受益**：`DirectionLeft/Right` 在模式 ON 时也转为 `onStageDrag`
   （负/正映射），⇒ **不用改 Compose 层，D-pad 也能转地球**。退出效果仍走既有五级 BACK。
6. **手机开关控件的实现约束**（`:559-568` 已记录的坑）：
   ⛔ **不能用 `androidx.tv.material3.IconButton`** —— 该机实测「看得见、按不动」
   （`Surface.onClick` 走 `tvClickable`，无 `Modifier.clickable`，触摸永不触发 `onClick`）。
   必须用项目自建的 `FocusableSurface`（内部 `combinedClickable`，触摸/D-Pad 双通道）。
   触摸目标 ≥48dp（有 `audit_small_touch_target.py` 门禁）。

**待验证**：`WebView` 建时设了 `isFocusable/isClickable/isLongClickable = false`
与 `FOCUS_BLOCK_DESCENDANTS`（`WorldGlobeRenderer.kt:183-187`）。这些只拦**按键与焦点**路由
（让遥控器方向键归 Compose），不拦 MotionEvent 下发，JS 侧 `touchmove` 预期可用；
但 Compose 的 `pointerInput` 与 `AndroidView` 宿主 View 是否同时收到同一次拖动，
需真机实测。若被父层吃掉，解法是给 `AndroidView` 加 `pointerInteropFilter`，
或直接在 Kotlin 侧用 `onStageDrag` 直接算（**推荐后者**——绕开 WebView 触摸，
交互全走既有 100ms 桥接，手机与电视共用一条路径）。

### 方案 B（推荐，与 A 同批做）：就绪看门狗 + 降级，杜绝「无信息黑屏」

**做法**
1. `index.html` 顶部注册 `window.onerror`，把错误写进 `document.title` 并在页面上画出红字
   （电视上肉眼可见，不依赖 logcat）。
2. `globe.js` 用 `try/catch` 包住初始化；失败时暴露 `window.WorldGlobe = { failed: true, reason: '...' }`
   而不是让 `window.WorldGlobe` 缺席。
3. Kotlin 侧 `WorldGlobeRenderer` 增加就绪看门狗：`onPageFinished` 后 N 秒（建议 8 s）仍
   `!isReady()` ⇒ 走降级分支并 `AppLog.e` 打印 `reason`。
4. 降级目标：① 方案 A 的简化地球（地球 + 光点，不含弧线）② 或恢复被 `546dd7c` 删除的 2D
   `WorldRenderer`（`git show 546dd7c^:app/src/main/java/com/nasmusic/tv/visualizer/renderers/WorldRenderer.kt`）。

**收益**：成本极低、**独立于 A 也能立刻消灭黑屏**；即使 A 将来再次踩坑，也不会再出现「纯黑 + 不知道为什么」。
**风险**：无。

### 方案 C（兜底）：按 WebView 能力门控，电视端不提供 E41

在 `AppSettings.WORLD` 的可达性上加 WebView 版本判定（复用 §10.205 里 `allowFramebuffer` /
`supports()` 那套机制），引擎低于阈值就不进效果列表。
**诚实、零风险**，但电视用户直接失去该效果——只适合 A+B 都失败时。

### 已否决

| 选项 | 否决理由 |
|---|---|
| 给电视装可更新的 WebView | `dumpsys webviewupdate` 为空、无 Play 服务，AOSP 5.1 WebView **不可更新**（§10.201 同结论） |
| 裁掉 `three-globe.es5.js` 里的 TSL 段（约 9000–12000 行） | 可试但**脆弱**：需精确切点 + 保证无悬空引用；失败时无 fallback，且 `downlevel_libs.mjs` 的可复现性会被破坏（ES5 产物必须能从上游基线逐字节 diff） |
| 用更老的 three-globe（不含 TSL） | 未验证；且 §10.201 明确要求 `three.min.js` / `three-globe.min.js` 作为**比对基线**永久保留，换版本等于换基线 |
| 完整 ES5 `Proxy` 实现 | ES5 无法拦截未知键名（第 2 条阻断已论证），原理上不成立 |

---

### P0 — 止血（**已回退**）

| # | 步骤 | 终态 |
|---|---|---|
| 1 | `downlevel_libs.mjs` 的 `stripUseStrict` + 零指令门禁 + `--only` | ⏪ **已回退**（随方案 A 一并撤销） |
| 2 | 重新生成 `three.es5.js` | ⏪ **已回退**到含 `"use strict"` 的原产物 |
| 3 | `index.html` 的 `?mode=` 解析 + `window.onerror` + 屏幕红字面板 | ⏪ **已回退** |
| 4 | `WorldGlobeRenderer.kt` 的 `nasmusic_globe_mode` 开关 / 周期看门狗 / 资产拦截埋点 | ⏪ **已回退** |

> ⚠️ 以上四项**都曾真机验证有效**（红字面板在 WebGL 上下文创建失败时准确报出原因）。
> 回退是裁决的结果、不是它们不成立——若日后重启这条线，§2.2 的取证方法可直接复用。

### P1 — 方案 A 主体（**已完成并验证，随后回退**）

| # | 步骤 | 终态 |
|---|---|---|
| 5 | 统一后端接口 `{group, earthYaw, material, setPoints, setArcs, onFrame, onResize}` | ✅ 完成 → ⏪ 回退 |
| 6 | `earthGroup` 父子结构、只由 `globeGroup.rotation.y` 承担自转 | ✅ 完成 → ⏪ 回退 |
| 7 | 地球本体 / 大气层 / 城市光点 / 大圆航线（自建 ribbon） | ✅ 完成 → ⏪ 回退 |
| 8 | `?mode=` 条件注入 three-globe（legacy 仅对照用） | ✅ 完成 → ⏪ 回退 |
| 9 | `rotateBy` + `Settings.Global` 运行时开关 | ✅ 完成 → ⏪ 回退 |
| 10 | `VisualizerRenderer.supportsInStageInteraction` / `VisualizerStage` 手势接线 | ✅ 完成 → ⏪ 回退 |

**验证结论**：`globe.js` 在这台电视的 Chrome 39 上**完全可跑**（three r160、契约建立、
37 城、航线、三张 2048×1024 贴图全部解码、`renderMs` 3ms）。
⛔ **但它救不了黑屏**——阻断点 2 在宿主合成层，与 JS 无关（§2.2）。

### P2 — 终态

| # | 步骤 | 终态 |
|---|---|---|
| 14 | `E41「世界」` 由 `Tier.ADV` 提到 **`Tier.ULTRA`** ⇒ 仅 HIGH 档提供 | ✅ **本次唯一的生产改动** |
| 15 | 同步更新被推翻的断言（`VisualizerThemeTest` 的 MEDIUM 一条），并补 HIGH 正向自证 | ✅ |
| 16 | 三道门：`assembleDebug` + `lintDebug` + `testDebugUnitTest` | ✅ |
| 17 | 顺带修掉打包事故：`assets/globe/node_modules`（23 MB 被打进 APK）+ `.gitignore` + `GlobeAssetsHygieneTest` | ✅ 保留（与 E41 无关的独立修复） |
| 18 | 真机验收：电视（E41 应在 MEDIUM/LOW 下**不可选**）+ 手机（**观感必须与回退前一致**） | ⏳ 待装包 |

---

## 7. 验收标准

| # | 标准 | 判定方式 |
|---|---|---|
| 1 | 电视进 E41 出画面（不再黑屏） | 目视 + `screencap` |
| 2 | `WorldGlobe is not defined` 刷屏消失 | `adb logcat -d \| grep WorldGlobe` |
| 3 | 城市点落在陆地上（无「落海」） | **用 §2.1 的网格法**：画经纬网格 + 城市标签，截图对地形；⛔ **不要肉眼估坐标**——那正是 §10.201 漏掉「城市落海」的原因 |
| 4 | 昼夜分界线可见、约 1/3 可见面在夜侧 | 目视 |
| 5 | 并行航线分层可见（同一端点对 ≥2 条不重叠） | 目视 |
| 6 | 音频驱动生效（光点呼吸 / 弧线密度 / 太阳呼吸） | 目视 + 暂停后航线清空 |
| 7 | **手机端无回归** | 目视，对照改造前截图 |
| 8 | 帧率可用（§10.201 记录该机 WebView 渲染进程曾占 133% CPU） | 帧率读数（`settings put global nasmusic_fps 1`）或 `dumpsys SurfaceFlinger --latency` |
| 9 | 失败可诊断 | 人为破坏一个资产后，**屏幕上**出现红字原因，而不是黑屏 |
| 10 | **观感对照清单 §5.0.1 的 14 条逐条打勾** | 缺一条即视为回归；其中 1/2/4/5 是本次动的，其余 10 条应逐像素不变 |

---

## 8. 需要所有者裁决的点

1. ~~**是否接受方案 A 的视觉回归风险**~~ → ✅ **已裁决：接受，走方案 A**（原话「决定：用A方案完成」）。
2. ~~**降级目标选哪个**~~ → ⏸ **降级分支未实现，故此项暂不触发**。legacy 后端已能一键切回对照，
   本身即是最好的降级路径；`FxCoverageScanTest` 的「21 个渲染器类」因此**不需要重新裁决**。
   ⛔ 只有当 legacy 与 native 在某台设备上都起不来时，才需要回头讨论「恢复 2D `WorldRenderer`」。
3. ~~**是否保留 `three-globe.min.js` 比对基线**~~ → ✅ **已裁决：保留，且要求整套 legacy 代码
   可随时切回**（原话「先保留three-globe.min.js这一套代码，以便我要求你切换回原来的方案
   进行效果验证」）。落地方式 = `Settings.Global` 的 `nasmusic_globe_mode` 运行时开关
   （**切换不需要重新构建**）。

### ⏳ 仍待裁决（本轮新增）

4. **`nasmusic_globe_mode` 是否长期保留为调试开关**？
   参照 `nasmusic_fps` 的取舍：它只存在于系统全局设置里、不进设置页、不在正式 UI 留痕。
   若倾向彻底移除，应改成「编译期常量 + 一次构建两套资产」——⛔ 但那会**违背裁决 3 的
   「随时切回对照」诉求**（切回要重新出包）。
5. **`TIER_POINT_PX` / `KLASS_STROKE` / ribbon dash 比例的最终值**——
   这些是对 legacy 观感**盲校准**出来的初值（7.0/5.5/4.5/3.5 px 等），**必须在 A/B 对照后定稿**。

### 运行时切换（供验收与日后 A/B 用）

```powershell
$adb = "C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$s   = "192.168.0.114:5555"
& $adb connect $s
# 切回 three-globe 做对照（重进可视化生效）
& $adb -s $s shell settings put global nasmusic_globe_mode legacy
# 回到原生 three.js（默认值）
& $adb -s $s shell settings put global nasmusic_globe_mode native
# 确认当前实际生效的后端：启动时日志里会打一行 WorldGlobe: boot: {...,"mode":"..."}
& $adb -s $s logcat -d -s WorldGlobe
```

> ⛔ **legacy 模式在电视上必然黑屏**（§2 阻断点 3，结构性死路）⇒ 它只在**手机/新引擎**上有意义，
> 用于回答「改造后观感与原版差多少」。在电视上切 legacy 只能用来**复现原故障**、确认诊断链路通畅。

---

## 附：取证复现步骤

```powershell
$adb = "C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$s   = "192.168.0.114:5555"

# 1) 环境
& $adb -s $s shell getprop ro.build.version.sdk
& $adb -s $s shell dumpsys webviewupdate          # 空 = AOSP WebView，不可更新

# 2) 应用内症状
& $adb -s $s logcat -c
#    （在电视上：播放 → 进可视化 → 切到 E41「世界」）
& $adb -s $s logcat -d | Select-String WorldGlobe
#    期望：JS[ERROR] Uncaught ReferenceError: WorldGlobe is not defined（每秒 ~10 条）

# 3) 隔离探针（同一引擎，结论画在屏幕上）
& $adb -s $s shell am start -n com.skyworth.tv_browser/.BrowserActivity `
    -a android.intent.action.VIEW -d "file:///data/local/tmp/globe_probe2/probe4.html"
Start-Sleep 40
& $adb -s $s shell screencap -p /data/local/tmp/p.png
& $adb -s $s pull /data/local/tmp/p.png output/
```

探针页与补丁脚本：`logs_temp/probe/`（`probe.html` 基线 / `probe2` 去 strict / `probe3` 加 Proxy 垫片 /
`probe4` 纯 three.js 可行性闸门；`make_patch.py` 生成去 strict 产物，`patched/` 内为已验证版本）。
**这些是临时取证件，不入库。**