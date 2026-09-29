/* ============================================================================
 * globe.js — NASMusicTV「世界」3D 地球（three-globe，完全离线）
 *
 * 依赖（index.html 中已按序加载，均为本地 UMD 文件）：
 *   - three.min.js        （≤ r162，WebGL1 兜底；暴露全局 THREE）
 *   - three-globe.min.js  （2.45.2 UMD；暴露全局 ThreeGlobe）
 *
 * 与 Kotlin 侧 WorldGlobeRenderer.kt 的 API 契约（编排方定死，勿单边改动）：
 *   window.WorldGlobe.initCities(cities)    // [{lat,lng,tier,name}] tier 1..4
 *   window.WorldGlobe.updateRoutes(routes)  // [{fromLat,fromLng,toLat,toLng,klass}]
 *                                          //   klass 0=主干 / 1=支线 / 2=次要
 *   window.WorldGlobe.setAudio(params)      // {energy,bass,mid,treble,beat} 0..1
 *   window.WorldGlobe.isReady()             // → true
 *
 * 音频桥接：Kotlin 侧 100ms 事件粒度注入；连续动画（弧线 dash 流动 / 光点
 * 脉冲 / 自转 / 大气呼吸 / 星空缓转）全部留在本文件的 rAF 循环，Kotlin 侧只发参数。
 *
 * 背景星空由 THREE.Points 程序化生成（星点贴图用 canvas 现画），**不引入任何
 * 资源文件**，与「完全离线」约束一致。
 *
 * ⛔ 完全离线：任何 fetch / 外部图片 / 在线字体一律禁止。地球纹理用本地
 *    earth_lit.jpg（由上游 earth.jpg 派生的着色版，assets 打包，经
 *    shouldInterceptRequest 从虚拟资产域提供），大气层用内置着色。
 * ⛔ ES5 语法：目标设备含 Android 5.1（创维开发机）的 WebView，不用
 *    const/let/箭头函数/模板字符串/Promise。
 * ========================================================================== */
(function () {
  'use strict';

  var container = document.getElementById('globe-container');
  if (!container) return;

  var THREE = window.THREE;
  var ThreeGlobe = window.ThreeGlobe;
  if (!THREE || !ThreeGlobe) return;

  // ── 场景 ────────────────────────────────────────────────────────────────
  var scene = new THREE.Scene();
  scene.background = new THREE.Color(0x03050a); // 近黑深蓝底（星点在其上叠加）

  var camera = new THREE.PerspectiveCamera(50, 1, 50, 1000);
  // near=50 而非 0.1：near 过小会让深度缓冲精度全部浪费在近处，球面
  // （相机 260 - 半径 100 = 160 起）与贴面弧线的 z-fighting 加剧 → 正面
  // 航线被球体遮住（2026-09-29 用户反馈）。near=50 下最近几何 160，安全。
  camera.position.z = 260;

  // ⚠️ 像素比锁 1.0（不跟 devicePixelRatio）：WebView 里填充率是 WebGL 最贵
  //   的项，1.5× 即 2.25 倍像素量。真机实测 sandboxed_process（WebView 渲染
  //   进程）占 133% CPU、「半条线 + 动画断续」——降回 1.0 换取流畅度。
  //   TV/手机远看球体，1.0 足够。
  // ✅ antialias 开回：此前为省性能关掉，导致地球与月球轮廓全是锯齿（真机反馈）。
  //   关它的两个前提（1.5× 像素比、每帧 globeMaterial() 查找）都已修掉，
  //   此刻 MSAA 4x @ pixelRatio 1.0 的开销远小于当初的 1.5× 无 MSAA。
  var renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false });
  renderer.setPixelRatio(1);
  container.appendChild(renderer.domElement);

  // ── 灯光：MeshPhongMaterial 必须要有光照，否则球体全黑 ─────────────────
  // （three-globe 官方示例标配：环境光 + 主方向光 + 补光。场景缺灯时
  //   Phong 材质的 diffuse 项为 0，只剩 emissive —— 球体近全黑，这是
  //   2026-09-29 手机黑屏的头号根因，勿删）
  // ⚠️ 环境光必须压到极低：AmbientLight 是**均匀从各方向**打光，会把夜面同样照亮，
  //    昼夜分界线会直接消失。emissive 同理（全表面均匀加光），只留少量作 earthshine。
  // ⚠️ 太阳方向**决定晨昏线落在哪**，是真机排查过的关键值。
  //
  // 几何：相机在 (0,0,260) 朝 -z 看 ⇒ 可见半球是 n_z > 0 的点；某点受光条件
  // 是 n·SUN_DIR > 0；晨昏线是 n·SUN_DIR = 0 的**大圆**。
  //   原值 (0.55, 0.32, 0.77) 与视线夹角 ≈ 140°（太阳几乎在相机身后），
  //   球面中心 n·SUN_DIR ≈ 0.77 ⇒ 中心受光；而要在 n_z>0 内满足 n·SUN_DIR=0，
  //   只能取 n_z 极小处 ⇒ **晨昏线被挤到轮廓边缘成一条细缝**，真机完全看不到，
  //   一度误判为「着色器没生效」（后来用纯绿自检才证明球壳渲染正常）。
  //
  // 现取与视线夹角 ≈ 70°（cos70° ≈ 0.342）：按 (1-cosθ)/2 估算，可见面约
  // 1/3 落在夜侧 —— 正是要的构图。太阳仍在右上，与太阳 sprite 的画面位置一致。
  var SUN_DIR = new THREE.Vector3(0.60, 0.52, 0.34).normalize();
  var SUN_DIST = 700; // 须满足 SUN_DIST + 相机 z(260) < far(1000)，否则 sprite 被裁掉

  // 环境光 0.10 → 0.18：配合过渡带收窄到 ±16°（此前 ±33°），这点补光已不会
  // 把晨昏线糊掉，却能让暗面细节（夜面轮廓）不至于全黑。
  var ambient = new THREE.AmbientLight(0x6f86b0, 0.18);
  scene.add(ambient);

  // 主光 = 太阳。世界空间固定 ⇒ 地球自转时分界线自然扫过球面 = 白天/黑夜
  //
  // ⚠️ 强度 2.1 → 3.4：真机反馈「白天这部分要加亮，现在还是很暗，要跟夜间
  //   有对比有明显区别」。两个叠加原因：
  //   ① 卫星图本身已被 darken_blue_marble.py 压到 0.62 亮度（为适配暗色主题）；
  //   ② 环境光为保住昼夜对比压到 0.10，昼面的照度几乎全靠这盏主光。
  //   加强主光是**定向**的 —— 只提亮受光面、不碰背光面，正是要的「昼夜分明」，
  //   比加环境光（会均匀提亮夜面、削弱对比）更对症。
  var sunLight = new THREE.DirectionalLight(0xfff4e0, 3.4);
  sunLight.position.copy(SUN_DIR).multiplyScalar(SUN_DIST);
  scene.add(sunLight);
  // ⛔ 已删 fillLight：它是从地球背面来的补光，会照亮夜面，与昼夜线冲突

  // 太阳本体（加性发光 sprite）
  //
  // ⚠️ 为什么不能用 `position = SUN_DIR * 700` 这种真实 3D 位置：
  //   相机在 z=260 朝 **-z** 看，能看到的地球半球是 z>0 那一面。太阳若按真实方向
  //   放在 z>0 一侧 ⇒ 它在**相机背后**，永远进不了画面（真机实测：看不到太阳）。
  //   而要让太阳可见就得偏向 -z，那样受光面会转到地球背面、正面全暗。
  //   两者在这个固定机位下几何互斥。
  //
  //   解法：把 sprite 固定在**相机空间的固定偏移**（画面右上方）。这不只是权宜：
  //   真实太阳相对地球几乎**不动**（地球自转时视角基本不变，只有周年视差），
  //   所以「固定于视角」恰好就是正确行为，只是距离上做了压缩。
  //   受光方向仍由 SUN_DIR 独立决定（右上），与它在画面右上的位置观感一致。
  var sunSprite = new THREE.Sprite(new THREE.SpriteMaterial({
    map: makeGlowTexture(),
    color: 0xfff0d0,
    transparent: true,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    depthTest: false // 固定在画面角落，不参与深度，不再有被地球挡住的问题
  }));
  var SUN_SCREEN_OFFSET = new THREE.Vector3(300, 165, -520); // 相机空间：右上前方
  var _tmp = new THREE.Vector3();
  sunSprite.scale.set(150, 150, 1);
  scene.add(sunSprite);

  // ── 星空背景 ────────────────────────────────────────────────────────────
  // 程序化生成，**不引入任何资源文件**（星点贴图用 canvas 现画，零网络、零 assets）。
  // 分两层只为拉开「大小/亮度」的纵深，成本仍是 2 个 draw call：
  //   远层：细小暗淡的星尘   近层：较大较亮的亮星
  // ⛔ ES5：不用 const/let/箭头函数/模板字符串（目标设备含 Android 5.1 的 WebView）。
  //
  // 半径取 600：相机在 z=260，最远的星距相机 260+600=860 < 相机 far(1000)，
  // 不会被远裁剪面切掉；又远大于大气层外径(≈116)，所以球体自转时星点视差正确。
  var STAR_RADIUS = 600;

  /** 星点贴图：canvas 画径向渐变，得到柔和的圆形星点（透明边），避免方块点 */
  function makeStarTexture() {
    var cv = document.createElement('canvas');
    // 64×64：星点 size 放大到 2.6/4.8 后，32×32 贴图会被拉伸发虚
    cv.width = 64;
    cv.height = 64;
    var g = cv.getContext('2d');
    var grd = g.createRadialGradient(32, 32, 0, 32, 32, 32);
    // 中心实心范围加大 ⇒ 星点更「实」，不像一团糊光
    grd.addColorStop(0, 'rgba(255,255,255,1)');
    grd.addColorStop(0.30, 'rgba(255,255,255,0.85)');
    grd.addColorStop(0.55, 'rgba(255,255,255,0.30)');
    grd.addColorStop(1, 'rgba(255,255,255,0)');
    g.fillStyle = grd;
    g.fillRect(0, 0, 64, 64);
    return new THREE.CanvasTexture(cv);
  }

  /**
   * 生成一层星点。
   * @param count 星数
   * @param size  点尺寸（世界单位，配合 sizeAttenuation）
   * @param bright 整体亮度
   * @param seed  LCG 种子 —— 用固定种子而非 Math.random，保证每次进页面星空一致
   *              （否则每次都换一批星，视觉上像「闪了一下」）
   */
  function makeStarLayer(count, size, bright, seed) {
    var s = seed >>> 0;
    function rnd() { // 确定性 LCG（glibc 参数），与 Math.random 解耦
      s = (s * 1103515245 + 12345) & 0x7fffffff;
      return s / 0x7fffffff;
    }
    var pos = new Float32Array(count * 3);
    var col = new Float32Array(count * 3);
    for (var i = 0; i < count; i++) {
      // 球面均匀采样：cos(theta)/sin(theta) 参数化，避免两极聚集
      var u = rnd() * 2 - 1;               // cos 极角 ∈ [-1,1]
      var theta = rnd() * Math.PI * 2;
      var r = Math.sqrt(1 - u * u);
      pos[i * 3] = STAR_RADIUS * r * Math.cos(theta);
      pos[i * 3 + 1] = STAR_RADIUS * u;
      pos[i * 3 + 2] = STAR_RADIUS * r * Math.sin(theta);
      // 色温微调：多数偏白，少数偏冷蓝/偏暖黄，避免「一片死白」
      var tint = rnd();
      var cr = 1, cg = 1, cb = 1;
      if (tint > 0.82) { cr = 0.72; cg = 0.82; cb = 1.0; }       // 冷蓝
      else if (tint < 0.14) { cr = 1.0; cg = 0.92; cb = 0.78; }  // 暖黄
      // 亮度随机抖动：让星点疏密自然
      var b = bright * (0.55 + 0.45 * rnd());
      col[i * 3] = cr * b;
      col[i * 3 + 1] = cg * b;
      col[i * 3 + 2] = cb * b;
    }
    var geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    geo.setAttribute('color', new THREE.BufferAttribute(col, 3));
    var mat = new THREE.PointsMaterial({
      size: size,
      map: makeStarTexture(),
      vertexColors: true,
      transparent: true,
      // 加性混合 ⇒ 亮星有辉光、暗星近乎不可见，正是星空观感
      blending: THREE.AdditiveBlending,
      depthWrite: false, // 星点不写深度，避免彼此遮挡产生黑斑
      sizeAttenuation: true
    });
    return new THREE.Points(geo, mat);
  }

  /** 太阳辉光贴图：中心亮核 + 外圈柔光，canvas 现画（零资源文件） */
  function makeGlowTexture() {
    var cv = document.createElement('canvas');
    cv.width = 128;
    cv.height = 128;
    var g = cv.getContext('2d');
    var grd = g.createRadialGradient(64, 64, 0, 64, 64, 64);
    grd.addColorStop(0, 'rgba(255,255,255,1)');
    grd.addColorStop(0.18, 'rgba(255,244,214,0.92)');
    grd.addColorStop(0.45, 'rgba(255,214,140,0.30)');
    grd.addColorStop(1, 'rgba(255,190,110,0)');
    g.fillStyle = grd;
    g.fillRect(0, 0, 128, 128);
    return new THREE.CanvasTexture(cv);
  }

  // ⚠️ 尺寸必须够大：PointsMaterial 的 size 是**世界单位**，配合 sizeAttenuation 后
  //   在星场半径 600 处会缩到亚像素 —— 真机实测「星空背景不明显」就是这个原因
  //   （原 1.1/2.3 基本不可见）。现给到 2.6/4.8 并把星数翻倍。
  var starDust = makeStarLayer(1500, 2.6, 0.80, 20260929);   // 远层：星尘
  var starBright = makeStarLayer(380, 4.8, 1.00, 77712345);   // 近层：亮星
  scene.add(starDust);
  scene.add(starBright);

  // ── 球体材质 + 昼夜分界线着色器 ──────────────────────────────────────────
  var SUN_DIR_VIEW = { value: new THREE.Vector3(0, 0, 1) }; // 视图空间太阳方向
  var NIGHT_LEVEL = { value: 1.0 };  // 夜面亮度总控（主循环按能量微调）

  // 大洲级辉光强度。
  //
  // 辉光**不在 shader 里算**（曾在片元里用 4 抽头偏移 UV 做低通，b=0.12 相当于
  // 43° 经度模糊 ⇒ 灯光被抹到海上，真机反馈「对不上大陆」）。改为贴图阶段
  // 生成 earth_glow.jpg：高斯模糊 + 乘陆地掩膜，物理上不可能溢出到海洋。
  // 该贴图已归一化拉伸（p99 拉到 1.0），故倍率回到接近 1 的量级。
  var CONTINENT_GLOW = { value: 1.15 };

  // 辉光贴图占位（加载前用纯黑，GLSL ES 1.0 不允许比较 sampler 是否为 null）
  function blackTexture2() {
    var t = new THREE.DataTexture(new Uint8Array([0, 0, 0, 255]), 1, 1, THREE.RGBAFormat);
    t.needsUpdate = true;
    return t;
  }
  var GLOW_MAP = { value: blackTexture2() };

  new THREE.TextureLoader().load('./earth_glow.jpg', function (tex) {
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.wrapS = THREE.RepeatWrapping;
    tex.generateMipmaps = true;
    tex.minFilter = THREE.LinearMipmapLinearFilter;
    tex.magFilter = THREE.LinearFilter;
    tex.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
    GLOW_MAP.value = tex;
  }, undefined, function (err) {
    console.log('[dbg] earth_glow.jpg FAILED: ' + err);
  });

  /**
   * 1×1 纯黑贴图，占位用。
   *
   * ⚠️ 为什么需要它：GLSL ES 1.00（WebGL1）**不允许比较 sampler 是否为 null**
   *   （`sampler2D != 0` 不是可移植写法，部分驱动直接编译失败）。所以「夜间
   *   贴图还没加载完」不能靠判空实现，只能**始终绑一张合法贴图**，加载完成前
   *   用纯黑占位 —— 采样结果为 0，天然等于「没有灯光」，优雅降级。
   */
  function blackTexture() {
    var t = new THREE.DataTexture(new Uint8Array([0, 0, 0, 255]), 1, 1, THREE.RGBAFormat);
    t.needsUpdate = true;
    return t;
  }
  var NIGHT_MAP = { value: blackTexture() }; // 异步加载完成后替换为 earth_night.jpg

  /**
   * 球体材质。
   *
   * ⚠️ 只保留基础外观设置，**不做任何着色器注入**。曾试过用
   *   `material.onBeforeCompile` 往片元里注入昼夜分界线，真机自检（注入恒定洋红）
   *   显示球体毫无变化、`onBeforeCompile fired` 日志也从未出现 —— three-globe
   *   2.45.2 并不使用我们传入的材质**实例**，而是把属性拷进自己新建的材质；
   *   `color` 之类是**属性**所以生效，而 `onBeforeCompile` 是**函数**，
   *   `Material.copy()` 不拷贝函数 ⇒ 注入从未挂上。
   *   昼夜效果改由独立的 [nightShell] 球壳实现，不依赖材质。
   *
   * color 必须是白色：MeshPhongMaterial 的 color 会与贴图**相乘**，深色会把
   * 已经很暗的纹理再压暗（这是「地球太暗」的历史根因之一）。
   *
   * emissive 固定为 0：emissive 是**全表面均匀加光**，会把夜面一起提亮、
   * 削弱昼夜对比。夜间可见度改由夜面球壳 + 城市灯光提供。
   */
  function makeGlobeMaterial() {
    return new THREE.MeshPhongMaterial({
      color: 0xffffff,
      emissive: 0x000000,
      shininess: 8,
      specular: new THREE.Color(0x14243c)
    });
  }

  // 加载夜间灯光贴图（与日间贴图同源同投影 ⇒ 像素级对齐，灯不会飘到海里）
  new THREE.TextureLoader().load('./earth_night.jpg', function (tex) {
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.wrapS = THREE.RepeatWrapping;
    // ⚠️ 保留 mipmap：大洲辉光靠 4 个偏移 UV 采样做低通，若关掉 mipmap，
    //   采样会落到纹理边缘/糊层，灯带出现噪点与断裂。
    tex.generateMipmaps = true;
    tex.minFilter = THREE.LinearMipmapLinearFilter;
    tex.magFilter = THREE.LinearFilter;
    tex.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
    NIGHT_MAP.value = tex;
  });

  // ── 夜面球壳（昼夜分界线 + 城市灯光）────────────────────────────────────
  // 半径先用一个占位值，稍后在 globe 建好之后用 Box3 **实测**修正。
  //
  // ⚠️ 为什么必须实测，不能假定 100：
  //   ① three-globe 2.45.2 没有 `globeRadius` prop（实测 0 命中），地球是它
  //      内部按固定缩放建的球，硬编码 100 是没有依据的猜测；
  //   ② 半径/球心一旦与地球不重合，灯带就会随自转漂到海上（真机反馈）。
  //   ⇒ 用 Box3 量地球的实际世界半径与中心，零猜测。
  var nightShell = new THREE.Mesh(
    // 段数与 three-globe 的地球（180×90）同量级：96×64 在赤道处仍可见棱边，
    // 叠加 MSAA 后才够平滑。
    new THREE.SphereGeometry(100 * 1.006, 144, 90),
    new THREE.ShaderMaterial({
      uniforms: {
        uNightMap: NIGHT_MAP,
        uSunDirView: SUN_DIR_VIEW,
        uNightLevel: NIGHT_LEVEL,
        uContinentGlow: CONTINENT_GLOW,
        uGlowMap: GLOW_MAP
      },
      vertexShader: [
        'varying vec3 vNrm;',
        'varying vec2 vUvw;',
        'void main() {',
        '  vNrm = normalize(normalMatrix * normal);',   // 视图空间法线
        '  vUvw = uv;',
        '  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);',
        '}'
      ].join('\n'),
      fragmentShader: [
        'uniform sampler2D uNightMap;',
        'uniform vec3 uSunDirView;',
        'uniform float uNightLevel;',
        'uniform float uContinentGlow;',
        'uniform sampler2D uGlowMap;',
        'varying vec3 vNrm;',
        'varying vec2 vUvw;',
        'void main() {',
        // ── 昼夜系数：法线与太阳方向点积 ──
        '  float ndl = dot(normalize(vNrm), normalize(uSunDirView));',
        // 晨昏线：分界**位置**由太阳方向决定（见 SUN_DIR，夹角 70° ⇒ 可见面约
        // 1/3 在夜侧），与过渡带宽度**互耦无关**：smoothstep 的两个边界只控制
        // 「多陡」，其**中点**才是分界线所在。保持中点在 ndl=0 ⇒ 位置不变。
        //
        // 过渡带：0.10 / −0.10 ≈ ±16°（此前 0.20/−0.20 ≈ ±33° 太宽）。
        // 真机反馈「晨昏过渡带可以少些，但实际的晨昏线不要变，还是 1/3」——
        // 正是要这个：分界位置不动，但过渡更锐利，昼/夜各自更纯粹。
        '  float night = smoothstep(0.10, -0.10, ndl);',
        // ── 夜色基底：冷蓝。刻意不叠日间贴图 ──
        // ⚠️ 叠日间贴图作基底的想法（想让夜面看得见大陆轮廓）**必须放弃**：
        //   球壳与地球的经度对齐是靠 −90° 偏转达成的，任何额外的贴图采样都会
        //   放大错位风险；而「夜面看不见大陆轮廓」的正确解法是让球壳**半透明**
        //   （alpha 封顶 0.85），让下面 three-globe 自己的地球贴图透出轮廓 ——
        //   它本来就是对齐的，无需我们再采一次。
        '  vec3 col = vec3(0.045, 0.070, 0.125);',
        // ── 点状细节（真实城市灯光）──
        '  vec3 lamp = texture2D(uNightMap, vUvw).rgb;',
        '  col += lamp * vec3(1.00, 0.84, 0.55) * 2.6;',
        // ── 大洲级辉光 ──
        // ⚠️ ⛔ 绝不要在这里用 4 抽头偏移 UV 做低通。实测踩坑：b=0.12 相当于
        //   **43° 经度**的模糊半径，横跨整片海域；配合高倍率，城市灯光被抹到
        //   大陆两侧的海里 ⇒ 真机反馈「灯光都在海里、对不上大陆」。
        //   （逐经度分箱实测证明基础贴图是准的：灯点 100% 落在陆地，错的只是
        //    这个运行时模糊。）
        // 正确做法：辉光在**贴图生成阶段**就高斯模糊好并乘上陆地掩膜
        //   （make_moon_and_night.py::build_continent_glow），物理上不可能溢出。
        // 额外好处：shader 少 3 次纹理采样（WebView 侧友好）。
        '  vec3 glow = texture2D(uGlowMap, vUvw).rgb;',
        '  col += glow * uContinentGlow;',
        // ⚠️ alpha 封顶 0.85（而非 1.0）：球壳永远略微半透明，让下面
        //   three-globe 的地球贴图透出大陆轮廓。这样「夜面看得见大陆」与
        //   「城市点与大陆同源对齐」两件事同时成立 —— 我们不再自己采日间贴图，
        //   也就不会引入任何额外的经度错位风险。
        '  gl_FragColor = vec4(col, min(night * uNightLevel, 0.85));',
        '}'
      ].join('\n'),
      transparent: true,
      depthWrite: false, // 不写深度：自身背光面靠地球的深度遮挡
      blending: THREE.NormalBlending
    })
  );
  // 位置也显式对齐到地球（不假设 three-globe 的 globe 在原点）
  scene.add(nightShell);
  // 注：夜面球壳的朝向/位置在主循环里逐帧跟随地球（见 nightShell.rotation 那段），
  // 此前在这里读 globe.__globeObj 的代码已删除（球壳不需要单独取地球位置）。

  // ── 月球 ────────────────────────────────────────────────────────────────
  // 尺寸与轨道距离都是**观感取舍**，不是真实比例，原因逐条列在下面。
  // 月面贴图用真实月面反照率（three.js 自带 / NASA 派生）。
  // ⚠️ 尺寸：真实月地半径比 0.2727（⇒ 本该是 27.27），但真机反馈「月球应该小
  //   一点」—— 0.27 在轨道上直径接近地球的 1/4，喧宾夺主。故按观感降到 16
  //   （比例 0.16）。**knowingly 偏离真实比例**，是构图取舍：地球是主角。
  var MOON_RADIUS = 16;
  // ⚠️ 轨道半径**必须小于相机 z**，否则月球有一半时间在相机背后、真机反馈
  //   「月球绕到视角后面了」。二者是硬约束：
  //     轨道 > 相机 z ⇒ 月球跑到镜头后，看不见
  //     轨道 < 月球半径 + 地球半径 ⇒ 月球会撞进地球/被地球整个吞掉
  //   取 150：z ∈ [-150, 150] 恒 < 260 ⇒ 全程在画面内；且 150 > 100+16
  //   ⇒ 内缘刚好擦过大气层外径(≈116)，既有「掠过地球」的分量，又不会穿插。
  //   真实轨道 384,400 km = 60.3 地半径 = 6033 单位，**在本相机下根本放不下**：
  //   既超 far=1000，又因 far 拉高会推翻 near=50/far=1000 的深度精度取舍
  //   （near 过小会让深度缓冲浪费在近处、加剧球面与弧线的 z-fighting）。
  //   取 150：z ∈ [-150, 150] 恒 < 260 ⇒ 全程在画面内；且 150 > 100+16
  //   ⇒ 内缘刚好擦过大气层外径(≈116)，既有「掠过地球」的分量，又不会穿插。
  //   代价：与真实 6033 单位相比压缩得更狠（真实值在本相机下根本放不下）。
  var MOON_ORBIT = 150;
  var MOON_INCLINATION = 5.145 * Math.PI / 180; // 白道相对黄道的真实倾角
  var MOON_PERIOD_MS = 110000;                   // 一圈 110s（真实 27.3 天，压缩后便于观察）

  // ── 地球本影：月球转到地球前方（相机一侧）时压暗 ─────────────────────────
  // 真机需求：「月球转到前景时，应该被地球挡住光线，变暗」。
  //
  // ⚠️ 与真实天文的关系（避免误解）：现实中「被地球挡住光线」是**月食**，
  //   发生在**满月**——地球在中间、月球在地球背后。而月球从地球**前方**经过
  //   对应的是**新月**：它位于地球与太阳之间，此时朝向我们的那一面本来就背光。
  //   本场景的太阳固定在右上，因此月球位于相机一侧时其亮面恰好朝着我们
  //   （真机诊断实测 phase≈0.9，接近满月外观），观感上「该暗却不暗」。
  //   这里按需求做成显式的**本影衰减**：月球越靠近相机（z 越大）越暗，
  //   进入地球背后（z<0）后恢复全亮。
  var MOON_SHADE_START = 0.15; // z 超过此值开始压暗（归一化到轨道半径）
  // 最暗时保留 6% —— 真正的地影里月球背面接近全黑。真机反馈「感觉变亮了」，
  // 根因不只在这：源贴图是**全月面反照率图**、本就没有暗部（实测 p25=113、
  // 暗部 64.6% 的像素 >80），已改为在贴图生成阶段压深暗部（p10 84→57）。
  // 两者叠加，本影才真正暗得下去。
  // 保留 6% 而非 0：给 0 时月球是纯黑球、落在黑色星空背景上会彻底消失
  // （此前已踩过这个坑）。
  var MOON_SHADE_MIN = 0.06;
  var _moonLight = 1;

  function applyMoonEclipse() {
    // 归一化的「朝相机程度」：z=+轨道半径 ⇒ 1（最近），z=0 ⇒ 0（侧面）
    var t = moonMesh.position.z / MOON_ORBIT;

    // ⚠️ 必须用**连续**函数，不能 `if (t > START) {...}` 硬分支。
    // 真机反馈「月球贴图有个跳变的过程」：硬分支在阈值处不连续，而缓动又是
    // **按帧**的（帧率一波动跳变幅度就变）⇒ 视觉上一亮一暗反复跳。
    // smoothstep 把压暗量平滑铺开，两端导数为 0，无突变。
    var k = (t - MOON_SHADE_START) / (1 - MOON_SHADE_START);
    k = k < 0 ? 0 : (k > 1 ? 1 : k);
    k = k * k * (3 - 2 * k);          // smoothstep 本体
    var target = 1 - (1 - MOON_SHADE_MIN) * k;

    // 指数缓动：用「每秒比例」而非「每帧比例」，否则 30fps 与 60fps 跟随速度
    // 差一倍，又是一种跳。
    var a = 1 - Math.pow(0.001, 1 / 60);
    _moonLight += (target - _moonLight) * a;

    // 调暗 diffuse（决定受光面亮度）。
    moonMesh.material.color.setScalar(_moonLight);
    // 暗面的「可见度补偿」：emissive 配 emissiveMap（= 同一张月面贴图），
    // 于是暗面按月海/陨坑的细节起伏，而不是一颗均匀的灰球。
    // 幅度 0.16：够看清轮廓纹理，又不至于让暗面亮到像被照亮。
    // ⚠️ 依赖 emissiveMap —— 没有它 emissive 就是纯色，暗面必然变灰球。
    moonMesh.material.emissive.setScalar((1 - _moonLight) * 0.16);

    // 注：此前的 [dbg] 周期日志（确认 light 连续变化、uniform 存活）已移除 ——
    // 月球压暗与夜面球壳均已真机验证通过，留着只会持续刷 logcat。
  }

  // 轨道倾角：把月球放进一个绕 X 轴倾斜的枢轴，枢轴内沿 XZ 圆周运动 ⇒
  // 得到「真实倾角 + 顺行（自北极俯视为逆时针）」的公转。
  var moonPivot = new THREE.Object3D();
  moonPivot.rotation.x = MOON_INCLINATION;
  scene.add(moonPivot);

  var moonMesh = new THREE.Mesh(
    // ⚠️ 段数 48×32 太少：球体占屏大时轮廓会明显呈多边形（真机反馈「边缘有
    //   锯齿」）。提到 96×64；再加 MSAA 后边缘已平滑。
    new THREE.SphereGeometry(MOON_RADIUS, 96, 64),
    // 月面贴图（真实月面反照率，three.js 自带 / NASA 派生，已压深暗部）
    // ⚠️ emissiveMap（而非纯 emissive）：真机反馈「暗部变成一个大灰球、没有
    //   纹理」。原因是 diffuse 被压到近全黑后，唯一可见的是 emissive —— 而
    //   均匀 emissive 是**不含任何纹理**的纯色，暗面自然成了灰球。
    //   emissiveMap = 同一张贴图 ⇒ 暗面按贴图细节起伏（月海/陨坑仍可辨），
    //   只是整体压暗，不破坏「被地球遮挡」的观感。
    new THREE.MeshPhongMaterial({ color: 0xffffff, shininess: 2 })
  );
  new THREE.TextureLoader().load('./moon.jpg', function (tex) {
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.wrapS = THREE.RepeatWrapping;
    // ⚠️ 过滤链必须用 **mipmap + 三线性**（LinearMipmapLinearFilter）+ 各向异性。
    //   两轮踩坑：
    //   ① 最初默认 mipmap → 真机反馈「变模糊且感觉变亮」；
    //      实际原因是低 mip 层是区域平均亮度（比原图亮），且在球面高曲率处
    //      各向异性不足会串色。
    //   ② 改为**完全关掉** mipmap（generateMipmaps=false + LinearFilter）→
    //      噪点暴增（真机反馈）。根因：贴图 1024×512 而月球占屏大，纯双线性
    //      在纹素间插值会放大压缩伪影，且 emissiveMap 暗部对比被拉高后更明显。
    //   正确解：**保留** mipmap 消噪，靠各向异性解决模糊，而不是牺牲 mipmap。
    tex.generateMipmaps = true;
    tex.minFilter = THREE.LinearMipmapLinearFilter; // 三线性：层间也插值，无带状
    tex.magFilter = THREE.LinearFilter;
    tex.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
    moonMesh.material.map = tex;
    // 同一张贴图兼作 emissiveMap：让暗面保留纹理而非变成灰球
    moonMesh.material.emissiveMap = tex;
    moonMesh.material.needsUpdate = true;
  });
  // ⛔ 月球挂在 moonPivot 下，**绝不能**挂到 globe 下 —— globe 每帧自转，
  //    挂过去月球会跟着地球一起转，等于失去公转。
  // 遮挡（前挡地球 / 后被地球挡）由深度测试天然完成，无需任何额外代码。
  moonPivot.add(moonMesh);

  // ── 地球（暗色球体 + 大陆纹理 + 大气层）────────────────────────────────
  var globe = new ThreeGlobe()
    // earth_lit.jpg = three-globe 官方示例同款的 **NASA Blue Marble 真彩卫星图**
    //   （unpkg three-globe/example/img/earth-blue-marble.jpg，public domain），
    //   仅经 logs_temp/world_map_gen/darken_blue_marble.py 做了一次
    //   亮度/对比/饱和度调整以适配暗色主题。
    // ⚠️ 不要再用「陆地掩膜 + 逐像素合成」自己造图（2026-09-29 定案）：
    //   此前 earth_lit.jpg 由 earth-dark.jpg（陆地纯黑 lum=0 / 海洋 lum=11~15）
    //   经掩膜合成生成，合成环节反复出错（配色反了、陆地只剩 7.9%），并连带
    //   引发「城市落海」的误判。真彩卫星图实测方位正确（8/9 城市 + 5/5 海洋），
    //   且与 three-globe 球面朝向天然配套（库作者即用它做默认演示）。
    .globeImageUrl('./earth_lit.jpg')
    .globeMaterial(makeGlobeMaterial())
    .showAtmosphere(true)
    .atmosphereColor('#274b7a')
    .atmosphereAltitude(0.16)
    .showGraticules(false);

  scene.add(globe);

  // 地球网格相对外层 globe 对象的固定偏转。三个 globe 对象各带不同的
  // rotation.y，其中**地球贴图球**的是 −90°（把纹理 UV 对齐到经纬坐标）。
  // 夜面球壳必须叠加同一个值，否则与地球差 90°。
  // ⚠️ 只给球壳用这一个常量；⛔ 不要去 traverse 抓内部网格并 copy 它的
  //   scale/position —— 会抓到大气层网格（scale≈1.16），球壳随之暴涨并把
  //   整片大陆盖成暗色（2026-09-29 真实回归）。
  var EARTH_YAW_OFFSET = -Math.PI / 2;

  // 夜面贴图的**经度校准量**（度）。可在真机直接调这一个数修正灯光位置。
  // 背景：上游 earth.jpg（已验证方位正确：7/8 城市 + 5/5 海洋）与
  // night_src.jpg（three-globe 的 earth-night.jpg）**并非同源**，实测灯光与
  // 陆地的重叠率约 71%，高纬/极区有偏差。但这张叠加图证明**贴图本身是对的**，
  // 城市点与球面朝向也不该有问题（three-globe 内部自洽）。
  // ⇒ 运行时若仍有偏移，只可能来自夜面球壳的朝向，故留此校准旋钮。
  // 调法：看夜面灯光相对大陆偏了几度，填进来（偏东就填负数）。
  var NIGHT_LNG_CALIBRATION = 0.0;

  // 夜面球壳半径直接用 100：已从 three-globe 2.45.2 源码确认地球半径
  // minified 里是硬编码的 `de=100`，球体为 SphereGeometry(de,180,90)、
  // 大气层 SphereGeometry(de*(1+atmosphereAltitude))。
  // ⚠️ 曾试过用 Box3.setFromObject(globe) 实测半径，但那是错的：Box3 会把
  //   挂在 globe 下的**航线**（带弧高、可达数倍半径）与城市光点一起算进去，
  //   量出来的外接盒远大于地球。切勿再用。


  // ── 城市光点（Tier 分级：1 最亮最大 → 4 最暗最小）────────────────────────
  // ⚠️ 配色必须是**与蓝色球体互补的暖色系**。2026-09-29 真机反馈「城市点看不出来」：
  //    原配色是 4 级全蓝（#a8dcff/#5aa8f0/#2f6fc0/#1c4a80），而球体本身已是
  //    藏青～石板蓝（海洋 lum 27 / 陆地 lum 107）——同色系叠加等于没有对比。
  //    改用 暖金→珊瑚→薄荷 的互补色阶，Tier 递进仍靠「亮度 + 半径」保持。
  var TIER_COLOR = {
    1: '#fff1b8', // 骨干枢纽：淡暖金（最亮、最大）
    2: '#ffc14d', // 区域枢纽：琥珀金
    3: '#ff7a5c', // 次级节点：珊瑚橙
    4: '#5ec8b5'  // 末端节点：薄荷青（最小最暗，但不再与球体同色）
  };
  var TIER_RADIUS = { 1: 0.20, 2: 0.15, 3: 0.11, 4: 0.08 };

  globe
    .pointsData([])
    .pointsMerge(true) // 合批，单 draw call
    .pointLat(function (d) { return d.lat; })
    .pointLng(function (d) { return d.lng; })
    .pointColor(function (d) { return TIER_COLOR[d.tier] || TIER_COLOR[4]; })
    .pointRadius(function (d) {
      return (d.r != null ? d.r : (TIER_RADIUS[d.tier] || TIER_RADIUS[4]));
    })
    .pointAltitude(0.02) // 抬离球面，避免与变亮后的地表 z-fighting
    // ⚠️ pointsTransitionDuration(0)：呼吸/拍点的平滑**已在 JS 侧自己算**
    //    （正弦 + 包络衰减，见 updatePoints）。若这里再留 180ms transition，
    //    每次 pointsData 刷新都会滞后 180ms 追不上目标值 → 拖影、动作发黏。
    .pointsTransitionDuration(0);
    // ⛔ 无 pointLabel：three-globe 2.45.2 的 points 层没有该方法，
    //    调用会抛 TypeError 中断脚本（2026-09-29 真机黑屏根因）。

  // ── 大圆航线（klass 分级：0 主干亮粗快 / 1 支线 / 2 次要暗细慢）──────────
  var KLASS_COLOR = {
    0: ['rgba(150,205,255,0.5)', 'rgba(150,205,255,0.95)'],
    1: ['rgba(90,150,230,0.5)', 'rgba(90,150,230,0.8)'],
    2: ['rgba(60,110,180,0.45)', 'rgba(60,110,180,0.7)']
  };
  // 线条变细：并行航线会同时出现多条，粗线会糊成一片（用户反馈「有些粗」）
  var KLASS_STROKE = { 0: 1.1, 1: 0.75, 2: 0.5 };
  var KLASS_DASH_MS = { 0: 1600, 1: 2600, 2: 3800 };

  globe
    .arcsData([])
    // ⛔ 无 arcsGreatCircle：three-globe 2.45.2 已移除该开关——大圆插值
    //    是弧线的**默认行为**（内部按大圆路径生成，无需显式开启）。
    .arcStartLat(function (d) { return d.fromLat; })
    .arcStartLng(function (d) { return d.fromLng; })
    .arcEndLat(function (d) { return d.toLat; })
    .arcEndLng(function (d) { return d.toLng; })
    // 弧高：按两端经纬跨度给，0.05 球半径兜底。
    //   altitude 语义 = 球半径倍数（three-globe 2.45.2 fe(): s = de*(1+r)）。
    //   ⚠️ 2026-09-29 曾因「正面航线看不见」把这里抬到 0.25~0.45，真机复测
    //   证明**与高度无关**（真正根因是颜色 alpha 0.02 + dash + 过细，见下），
    //   故按用户要求恢复原高度。
    //   lane = 同一端点对的并行车道号（Kotlin 侧按 tier 繁华度分配 0..2），
    //   乘 1/1.34/1.68 让多条并行航线**分层错开**，否则会画在同一条大圆上
    //   完全重叠、看起来仍然只有一条。
    .arcAltitude(function (d) {
      var span = Math.abs(d.toLat - d.fromLat) + Math.abs(d.toLng - d.fromLng);
      var base = Math.max(0.05, span / 180 * 0.22);
      return base * (1 + (d.lane || 0) * 0.34);
    })
    .arcColor(function (d) { return KLASS_COLOR[d.klass] || KLASS_COLOR[2]; })
    .arcStroke(function (d) { return KLASS_STROKE[d.klass] || KLASS_STROKE[2]; })
    // ⛔ 无 arcAltitudeAutoScale：上面已给显式 arcAltitude，自动缩放是死配置。
    //    （历史上它对短航线产生近零高度，一度被误判为「正面被遮」的根因。）
    .arcDashLength(0.6)
    .arcDashGap(0.15)
    // 并行车道错开 dash 相位，避免多条航线的亮段同步前进、看起来仍像一条
    .arcDashInitialGap(function (d) {
      return ((d.lane || 0) * 0.37 + Math.random() * 0.12) % 1;
    })
    .arcDashAnimateTime(function (d) { return KLASS_DASH_MS[d.klass] || 3800; });

  // 缓存球体材质引用：避免在 rAF 里每帧调 globe.globeMaterial()（three-globe
  // 的 accessor 每次都会走映射逻辑）。自转/呼吸循环只改这一个对象的属性。
  var globeMat = globe.globeMaterial();

  // ── 尺寸自适应 ─────────────────────────────────────────────────────────
  // ⛔ 不能只依赖 window resize 事件：WebView 首次加载时容器尺寸可能还是 0
  //    （AndroidView 尚未完成布局），此时 setSize(0,0) 会一直全黑。
  //    因此用 rAF 循环里逐帧比对，尺寸一变化就重建画布/投影。
  var lastW = 0, lastH = 0;
  function ensureSize() {
    var w = container.clientWidth || window.innerWidth || 1;
    var h = container.clientHeight || window.innerHeight || 1;
    if (w === lastW && h === lastH) return;
    lastW = w; lastH = h;
    renderer.setSize(w, h);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  }
  window.addEventListener('resize', ensureSize);
  ensureSize();

  // ── 状态 ────────────────────────────────────────────────────────────────
  var cityBase = [];        // 原始城市数据（不含呼吸半径）
  var audio = { energy: 0, bass: 0, mid: 0, treble: 0, beat: 0 };
  var lastRoutesKey = '';   // updateRoutes 去重（Kotlin 100ms 推一次）
  var lastPulseAt = 0;      // 拍点触发节流
  var ready = false;

  // ── 城市光点「呼吸」：持续慢速律动 + 拍点叠加包络 ────────────────────────
  // 2026-09-29 真机反馈「城市应该有呼吸感」：原先只有拍点时的一次性放大
  // （1.55 倍、220ms 回弹），静止时画面是死的。
  //
  // ⛔ 相位用**黄金角**（2.39996 rad）按序分配，而不是 i/count 均分：均分会让
  //    相邻城市依次起跳、出现「扫过地球」的波浪感；黄金角是让各点相位在圆周上
  //    尽量均匀又互不同步的最佳分布。
  var GOLDEN_ANGLE = 2.39996323;
  var TAU = 6.283185307;
  var BREATH_HZ = 0.5;        // 约 2s 一个周期，慢到不晃眼
  var BREATH_AMP = 0.18;      // 呼吸幅度 ±18%
  var BEAT_AMP = 0.5;         // 拍点额外放大
  var BEAT_DECAY = 0.94;      // 拍点包络每帧衰减系数
  var beatEnv = 0;            // 拍点包络 0..1
  var lastBreathMs = 0;       // 呼吸更新限流（~30Hz）

  function nowMs() {
    return (window.performance && performance.now) ? performance.now() : (new Date()).getTime();
  }

  /**
   * 按当前时间重建光点半径。
   * @param t    毫秒时间
   * @param force true 则无视限流立即刷新（初始化用）
   */
  function updatePoints(t, force) {
    if (!cityBase.length) return;
    if (!force && t - lastBreathMs < 33) return; // ~30Hz 足够：正弦本身平滑
    lastBreathMs = t;
    var pts = [];
    var w = t * 0.001 * BREATH_HZ * TAU;         // 呼吸相位随时间推进
    for (var i = 0; i < cityBase.length; i++) {
      var c = cityBase[i];
      var phase = (i * GOLDEN_ANGLE) % TAU;
      var breath = 1 + BREATH_AMP * Math.sin(w + phase);
      pts.push({
        lat: c.lat,
        lng: c.lng,
        tier: c.tier,
        r: (TIER_RADIUS[c.tier] || TIER_RADIUS[4]) * breath * (1 + BEAT_AMP * beatEnv)
      });
    }
    globe.pointsData(pts);
  }

  // ── 契约入口 ────────────────────────────────────────────────────────────
  window.WorldGlobe = {
    initCities: function (cities) {
      cityBase = cities || [];
      updatePoints(nowMs(), true);
      ready = true;
    },

    updateRoutes: function (routes) {
      var key = routes ? JSON.stringify(routes) : '';
      if (key === lastRoutesKey) return; // 去重：数据未变不重建几何
      lastRoutesKey = key;
      globe.arcsData(routes || []);
    },

    setAudio: function (params) {
      if (!params) return;
      audio.energy = params.energy != null ? params.energy : 0;
      audio.bass = params.bass != null ? params.bass : 0;
      audio.mid = params.mid != null ? params.mid : 0;
      audio.treble = params.treble != null ? params.treble : 0;
      audio.beat = params.beat != null ? params.beat : 0;
      // 拍点：不再「一次性放大再 setTimeout 回落」，而是把包络置 1，由主循环
      // 每帧衰减 —— 这样拍点脉冲可以和持续呼吸叠加，不会互相打断。
      var now = Date.now();
      if (audio.beat > 0.5 && now - lastPulseAt > 300) {
        lastPulseAt = now;
        beatEnv = 1;
      }
    },

    isReady: function () { return ready; }
  };

  // ── 主循环：自转 / 公转 / 昼夜 / 城市呼吸 / 弧线流动 ────────────────────
  function animate() {
    requestAnimationFrame(animate);

    // 尺寸自校正（见 ensureSize 注释：WebView 布局时序导致初始可能为 0）
    ensureSize();

    // 自转：能量越高转得越快。
    // 2026-09-29 由 0.0006 降到 0.00022（用户要求「减慢自转、延长一天」）：
    //   0.00026 rad/帧 @60fps ≈ 0.9°/s ⇒ 约 400s 转一圈 = 一个完整昼夜。
    //   之前 0.0006 ≈ 2.2°/s ⇒ 167s 一天，昼夜掠过太快、几乎看不清夜色。
    globe.rotation.y += 0.00022 * (1 + audio.energy * 0.8);
    // ⚠️ 夜面球壳必须跟随地球自转，否则灯带钉死在世界坐标（真机反馈「不随地球走」）。
    //   用 copy 而非只赋 rotation.y：连位置一起对齐，任何时候都不会错位。
    // ⚠️ 夜面球壳的旋转 = 地球自转 + 固定 −90°。
    //
    // 那个 −90° 是**必需的**，不是误差：three-globe 把地球网格挂在带
    // `rotation.y = -PI/2` 的 mesh 上，用它把纹理的 UV 方位对齐到经纬坐标
    // （`fe(lat,lng)` 直接产出经纬对应的 3D 坐标，不含该旋转）。验证：拿北京
    // (40.08N,116.58E) 对比 —— 纹理 UV 反算得 (-0.342, 0.644, -0.684)，
    // fe() 得 (0.684, 0.644, -0.343)：纬度 y 完全一致，x/z 互换反号，
    // 正是绕 Y 轴 −90°。少了它，夜面球壳与地球差 90° ⇒ 夜面上「大陆」出现在
    //   错误位置，于是**正确的城市点看起来全落进了海里**（真机反馈）。
    //
    // ⛔ 不要用 traverse 去找地球网格再 copy 它的 scale：会抓到**大气层网格**
    //   （scale≈1.16），球壳随之涨到 116、整片暗色盖住大陆 —— 这就是上一轮
    //   「大陆消失、城市落海」回归的真实成因。球壳的 scale 必须保持 1。
    nightShell.rotation.copy(globe.rotation);
    nightShell.rotation.y += EARTH_YAW_OFFSET;
    // 经度校准（见 NIGHT_LNG_CALIBRATION）：只动夜面球壳，不影响城市点/航线
    nightShell.rotation.y += NIGHT_LNG_CALIBRATION * Math.PI / 180;

    // 月球公转：时间驱动（与帧率无关），顺行
    var t = nowMs();
    var ang = (t / MOON_PERIOD_MS) * Math.PI * 2;
    moonMesh.position.set(
      Math.cos(ang) * MOON_ORBIT, 0, Math.sin(ang) * MOON_ORBIT
    );
    applyMoonEclipse();

    // 把太阳方向变换到**视图空间**喂给球壳着色器：片元里的法线是视图空间法线，
    // 用世界空间方向点乘会算错（两者不在同一坐标系）。
    // transformDirection 只取矩阵的旋转部分（方向向量不需要平移）。
    // camera.matrixWorldInverse 就是视图矩阵，three 每帧自动维护。
    SUN_DIR_VIEW.value.copy(SUN_DIR).transformDirection(camera.matrixWorldInverse);

    // 星空极慢反向自转：让星点相对球体有视差，画面才「立得住」
    starDust.rotation.y -= 0.00004;
    starBright.rotation.y -= 0.00004;

    // 夜面亮度随能量轻微起伏（音乐强时夜景更亮），范围很窄以免破坏昼夜对比
    NIGHT_LEVEL.value = 0.88 + 0.22 * audio.energy;
    // 大洲辉光随能量起伏：音乐越强，「灯火通明」越明显
    // 大洲辉光随能量起伏：音乐越强，「灯火通明」越明显
    CONTINENT_GLOW.value = 0.95 + 0.45 * audio.energy;

    // 球体 earthshine：只留极小量。⚠️ 不能像以前那样用 0.35+0.75*energy ——
    //   emissive 是全表面均匀加光，会把夜面一起抬亮、昼夜分界线就消失了。
    if (globeMat && globeMat.emissiveIntensity != null) {
      globeMat.emissiveIntensity = 0.30;
    }

    // 太阳本体固定在「画面右上」：把相机空间的偏移用相机朝向转到世界空间。
    // 相机是固定的，但写成通用形式，万一以后加轨道/震动也不会失效。
    sunSprite.position.copy(_tmp.copy(SUN_SCREEN_OFFSET).applyQuaternion(camera.quaternion))
      .add(camera.position);

    // 太阳随能量轻微呼吸（把「音频反应」从球体移到太阳上，避免削弱昼夜对比）
    var ss = 150 * (1 + 0.10 * audio.energy);
    sunSprite.scale.set(ss, ss, 1);

    // 城市光点呼吸：拍点包络逐帧衰减 + 持续正弦律动（内部限流 ~30Hz）
    if (beatEnv > 0.001) beatEnv *= BEAT_DECAY;
    else beatEnv = 0;
    updatePoints(t, false);

    renderer.render(scene, camera);
  }

  // Camera.updateMatrixWorld() 在 three r155 里会同时维护 matrixWorldInverse，
  // 故先调一次，保证第一帧的太阳方向就不是单位矩阵。
  camera.updateMatrixWorld();
  animate();
})();
