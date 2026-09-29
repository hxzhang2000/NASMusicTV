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
 * 脉冲 / 自转 / 大气呼吸）全部留在本文件的 rAF 循环，Kotlin 侧只发参数。
 *
 * ⛔ 完全离线：任何 fetch / 外部图片 / 在线字体一律禁止。球体为纯色材质
 *    （不设 globeImageUrl），大气层用内置着色。
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
  scene.background = new THREE.Color(0x03050a); // 近黑深蓝底

  var camera = new THREE.PerspectiveCamera(50, 1, 0.1, 1000);
  camera.position.z = 260;

  var renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
  container.appendChild(renderer.domElement);

  // ── 地球（暗色球体 + 大气层）───────────────────────────────────────────
  var globe = new ThreeGlobe()
    .globeImageUrl(null) // 纯色球，离线无贴图
    .globeMaterial(new THREE.MeshPhongMaterial({
      color: 0x0a1424, // 暗蓝黑
      emissive: 0x060d1a,
      emissiveIntensity: 0.5,
      shininess: 8,
      specular: new THREE.Color(0x14243c)
    }))
    .showAtmosphere(true)
    .atmosphereColor('#274b7a')
    .atmosphereAltitude(0.16)
    .showGraticules(false);

  scene.add(globe);

  // ── 城市光点（Tier 分级：1 最亮最大 → 4 最暗最小）────────────────────────
  var TIER_COLOR = { 1: '#a8dcff', 2: '#5aa8f0', 3: '#2f6fc0', 4: '#1c4a80' };
  var TIER_RADIUS = { 1: 0.16, 2: 0.12, 3: 0.085, 4: 0.055 };

  globe
    .pointsData([])
    .pointsMerge(true) // 合批，单 draw call
    .pointLat(function (d) { return d.lat; })
    .pointLng(function (d) { return d.lng; })
    .pointColor(function (d) { return TIER_COLOR[d.tier] || TIER_COLOR[4]; })
    .pointRadius(function (d) {
      return (d.r != null ? d.r : (TIER_RADIUS[d.tier] || TIER_RADIUS[4]));
    })
    .pointAltitude(0.012)
    .pointsTransitionDuration(180) // 拍点脉冲的平滑过渡
    .pointLabel(null);

  // ── 大圆航线（klass 分级：0 主干亮粗快 / 1 支线 / 2 次要暗细慢）──────────
  var KLASS_COLOR = {
    0: ['rgba(150,205,255,0.02)', 'rgba(150,205,255,0.9)'],
    1: ['rgba(90,150,230,0.02)', 'rgba(90,150,230,0.65)'],
    2: ['rgba(60,110,180,0.02)', 'rgba(60,110,180,0.4)']
  };
  var KLASS_STROKE = { 0: 0.9, 1: 0.6, 2: 0.35 };
  var KLASS_DASH_MS = { 0: 1600, 1: 2600, 2: 3800 };

  globe
    .arcsData([])
    .arcsGreatCircle(true) // 大圆航线（2.16.0+ 支持）
    .arcStartLat(function (d) { return d.fromLat; })
    .arcStartLng(function (d) { return d.fromLng; })
    .arcEndLat(function (d) { return d.toLat; })
    .arcEndLng(function (d) { return d.toLng; })
    .arcColor(function (d) { return KLASS_COLOR[d.klass] || KLASS_COLOR[2]; })
    .arcStroke(function (d) { return KLASS_STROKE[d.klass] || KLASS_STROKE[2]; })
    .arcAltitudeAutoScale(0.5)
    .arcDashLength(0.35)
    .arcDashGap(0.2)
    .arcDashInitialGap(function () { return Math.random(); }) // 随机相位，避免齐步走
    .arcDashAnimateTime(function (d) { return KLASS_DASH_MS[d.klass] || 3800; });

  // ── 尺寸自适应 ─────────────────────────────────────────────────────────
  function resize() {
    var w = container.clientWidth || window.innerWidth || 1;
    var h = container.clientHeight || window.innerHeight || 1;
    renderer.setSize(w, h);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  }
  window.addEventListener('resize', resize);
  resize();

  // ── 状态 ────────────────────────────────────────────────────────────────
  var cityBase = [];        // 原始城市数据（不含脉冲半径）
  var audio = { energy: 0, bass: 0, mid: 0, treble: 0, beat: 0 };
  var lastRoutesKey = '';   // updateRoutes 去重（Kotlin 100ms 推一次）
  var lastPulseAt = 0;      // 拍点脉冲节流
  var ready = false;

  // ── 光点脉冲：放大 → 220ms 后回落（transition 提供平滑）─────────────────
  function applyPointsWithScale(scale) {
    var pts = [];
    for (var i = 0; i < cityBase.length; i++) {
      var c = cityBase[i];
      pts.push({
        lat: c.lat,
        lng: c.lng,
        tier: c.tier,
        r: (TIER_RADIUS[c.tier] || TIER_RADIUS[4]) * scale
      });
    }
    globe.pointsData(pts);
  }

  function pulseCities() {
    if (!cityBase.length) return;
    applyPointsWithScale(1 + 0.55);
    setTimeout(function () { applyPointsWithScale(1); }, 220);
  }

  // ── 契约入口 ────────────────────────────────────────────────────────────
  window.WorldGlobe = {
    initCities: function (cities) {
      cityBase = cities || [];
      applyPointsWithScale(1);
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
      // 拍点脉冲（>0.5 且距上次 ≥300ms 才触发）
      var now = Date.now();
      if (audio.beat > 0.5 && now - lastPulseAt > 300) {
        lastPulseAt = now;
        pulseCities();
      }
    },

    isReady: function () { return ready; }
  };

  // ── 主循环：自转 / 大气呼吸 / 弧线流动（dash 由 three-globe 内部推进）───
  function animate() {
    requestAnimationFrame(animate);

    // 自转：能量越高转得越快（基础 ~0.036°/帧 ≈ 2.2°/s）
    globe.rotation.y += 0.0006 * (1 + audio.energy * 1.6);

    // 大气层强度随能量呼吸（emissive 亮度）
    var mat = globe.globeMaterial();
    if (mat && mat.emissiveIntensity != null) {
      mat.emissiveIntensity = 0.35 + 0.75 * audio.energy;
    }

    renderer.render(scene, camera);
  }
  animate();
})();
