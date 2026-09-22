// 合成西交大 —— 基于 moonfloof/suika-game (Unlicense) 改写而来，物理引擎为本地 matter.min.js (MIT)。
// 完整版权声明见 THIRD_PARTY_NOTICES.md。
//
// 相比原版「合成大西瓜」的主要改动：
//   1. 11 级水果 -> 9 级 C9 校徽（见下方 LEVELS）；
//   2. 校徽素材是透明底线稿，直接贴在球上像镂空的圈、哈工大那枚还不是圆的。
//      加载时统一烘焙成「白底圆片 + 校色描边 + 校徽 + 高光」的棋子（见 bakeToken）；
//   3. 只随机投放前 5 级，按权重偏向小级，并预告下一个；
//   4. 失败判定改成「球堆过线持续一段时间」，线附近有球时闪烁预警；
//   5. 合成出最高级（西交大）有全屏庆祝动画；两个西交大相遇直接消除+加分；
//   6. 瞄准线、合成光圈/粒子/加分飘字、底部合成链进度条；
//   7. 游戏结束时通过 JavascriptInterface 把分数回传给宿主 App。

(function () {
  'use strict';

  const { Engine, Render, Runner, Composite, Bodies, Body, Events, Mouse, MouseConstraint } = Matter;

  const DARK = document.documentElement.classList.contains('dark');

  // ------------------------------------------------------------------
  // 等级配置：C9 高校，从小到大。key 对应素材 img/game_c9_<key>.webp / .png。
  // ------------------------------------------------------------------
  const LEVELS = [
    { key: 'hit',   name: '哈工大', radius: 22, color: '#1a5fa8', score: 1  },
    { key: 'ustc',  name: '中科大', radius: 28, color: '#1f6fc9', score: 3  },
    { key: 'nju',   name: '南大',   radius: 34, color: '#6a1b7a', score: 6  },
    { key: 'zju',   name: '浙大',   radius: 42, color: '#0d4fa8', score: 10 },
    { key: 'fudan', name: '复旦',   radius: 50, color: '#1c4f9c', score: 15 },
    { key: 'sjtu',  name: '上交',   radius: 60, color: '#c8322f', score: 21 },
    { key: 'pku',   name: '北大',   radius: 70, color: '#9b1b1f', score: 28 },
    { key: 'thu',   name: '清华',   radius: 82, color: '#7a2a8c', score: 36 },
    { key: 'xjtu',  name: '西交大', radius: 96, color: '#d62f2f', score: 60 },
  ];
  const MAX_LEVEL = LEVELS.length - 1;

  const SPAWN_POOL = [0, 1, 2, 3, 4];
  const SPAWN_WEIGHTS = [30, 24, 18, 12, 6];

  // 世界坐标。顶部 0..~110 留给分数和「下一个」，预览球在它下面，不再和文字叠在一起。
  const WIDTH = 640;
  const HEIGHT = 1050;
  const WALL_PAD = 40;
  const FLOOR_BAND = 64;            // 底部合成链那条带子的高度，也是地面的位置
  const FLOOR_Y = HEIGHT - FLOOR_BAND;
  const PREVIEW_HEIGHT = 170;
  const LOSE_LINE_Y = 232;
  const LOSE_HOLD_MS = 1500;
  const WARN_MARGIN = 46;

  const FRICTION = { friction: 0.008, frictionStatic: 0.01, frictionAir: 0.0008, restitution: 0.15 };
  const STORAGE_KEY = 'merge-xjtu-highscore';
  const TOKEN_SIZE = 320;           // 烘焙棋子的边长（像素），最大的球显示出来也不糊

  const COLORS = DARK
    ? { band: 'rgba(140,180,255,0.06)', line: 'rgba(255,110,110,', guide: 'rgba(140,190,255,0.28)', text: '#E6EEF9' }
    : { band: 'rgba(11,79,156,0.05)', line: 'rgba(235,60,60,', guide: 'rgba(11,92,173,0.22)', text: '#0B2545' };

  function mulberry32(a) {
    return function () {
      let t = (a += 0x6d2b79f5);
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  const rand = mulberry32(Date.now() & 0xffffffff);

  function weightedPick(pool, weights) {
    const total = weights.reduce((a, b) => a + b, 0);
    let r = rand() * total;
    for (let i = 0; i < pool.length; i++) {
      r -= weights[i];
      if (r <= 0) return pool[i];
    }
    return pool[pool.length - 1];
  }

  function hexToRgb(hex) {
    const n = parseInt(hex.slice(1), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  function rgba(hex, a) {
    const [r, g, b] = hexToRgb(hex);
    return 'rgba(' + r + ',' + g + ',' + b + ',' + a + ')';
  }

  // ------------------------------------------------------------------
  // 贴图：读校徽素材（.webp → .png），烘焙成统一风格的圆形棋子。
  // 素材缺失时棋子中间写校名，照样能玩。
  // ------------------------------------------------------------------
  function tryLoadImage(path) {
    return new Promise((resolve) => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => resolve(null);
      img.src = path;
    });
  }

  function bakeToken(level, badge) {
    const s = TOKEN_SIZE;
    const c = document.createElement('canvas');
    c.width = s;
    c.height = s;
    const ctx = c.getContext('2d');
    const cx = s / 2;
    const R = s / 2 - 1;

    // 底：白色圆片，边缘带一点校色
    const base = ctx.createRadialGradient(cx - s * 0.12, cx - s * 0.16, s * 0.05, cx, cx, R);
    base.addColorStop(0, '#ffffff');
    base.addColorStop(0.72, '#ffffff');
    base.addColorStop(1, rgba(level.color, 0.16));
    ctx.beginPath();
    ctx.arc(cx, cx, R, 0, Math.PI * 2);
    ctx.fillStyle = base;
    ctx.fill();

    // 校徽
    ctx.save();
    ctx.beginPath();
    ctx.arc(cx, cx, R * 0.86, 0, Math.PI * 2);
    ctx.clip();
    if (badge) {
      const bs = R * 1.56;
      ctx.drawImage(badge, cx - bs / 2, cx - bs / 2, bs, bs);
    } else {
      ctx.fillStyle = level.color;
      ctx.font = 'bold ' + Math.floor(s * 0.22) + 'px sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(level.name, cx, cx);
    }
    ctx.restore();

    // 描边：外圈校色，内侧一道细白线
    const rim = s * 0.05;
    ctx.beginPath();
    ctx.arc(cx, cx, R - rim / 2, 0, Math.PI * 2);
    ctx.lineWidth = rim;
    ctx.strokeStyle = level.color;
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(cx, cx, R - rim - s * 0.008, 0, Math.PI * 2);
    ctx.lineWidth = s * 0.012;
    ctx.strokeStyle = 'rgba(255,255,255,0.9)';
    ctx.stroke();

    // 高光：左上一片柔光，让它像一枚有厚度的棋子
    const gloss = ctx.createLinearGradient(0, cx - R, 0, cx);
    gloss.addColorStop(0, 'rgba(255,255,255,0.55)');
    gloss.addColorStop(1, 'rgba(255,255,255,0)');
    ctx.save();
    ctx.beginPath();
    ctx.ellipse(cx - R * 0.18, cx - R * 0.42, R * 0.55, R * 0.3, -0.35, 0, Math.PI * 2);
    ctx.fillStyle = gloss;
    ctx.fill();
    ctx.restore();

    return c;
  }

  // 烘焙好的棋子直接以 canvas 形式塞进 Matter 的贴图缓存，不走 toDataURL：
  // 页面是 file:// 的不透明源，校徽一画进来 canvas 就被污染，toDataURL 会抛 SecurityError，
  // 整个预加载 reject，游戏一帧都起不来。Matter 的 sprite 只拿贴图去 drawImage，canvas 照样能用。
  async function loadLevelTexture(level) {
    const badge = (await tryLoadImage('./img/game_c9_' + level.key + '.webp'))
      || (await tryLoadImage('./img/game_c9_' + level.key + '.png'));
    const token = bakeToken(level, badge);
    const key = 'token:' + level.key;
    render.textures[key] = token;
    return { texture: key, image: token, size: TOKEN_SIZE };
  }

  function submitScoreToHost(score) {
    try {
      if (window.AndroidGameBridge && typeof window.AndroidGameBridge.submitScore === 'function') {
        window.AndroidGameBridge.submitScore(Math.floor(score));
      }
    } catch (e) { /* 非 Android 环境或桥未注入，忽略 */ }
  }

  const GameStates = { READY: 0, DROP: 1, LOSE: 2 };

  const els = {
    canvas: document.getElementById('game-canvas'),
    ui: document.getElementById('game-ui'),
    score: document.getElementById('game-score'),
    highscore: document.getElementById('game-highscore-value'),
    nextCanvas: document.getElementById('game-next-fruit'),
    end: document.getElementById('game-end-container'),
    endScore: document.getElementById('game-end-score-value'),
    endBest: document.getElementById('game-end-best-value'),
    endRecord: document.getElementById('game-end-record'),
    restart: document.getElementById('game-restart'),
    celebrate: document.getElementById('celebrate'),
    celebrateText: document.getElementById('celebrate-text'),
  };

  const engine = Engine.create();
  engine.gravity.y = 1.1;
  const runner = Runner.create();
  const render = Render.create({
    element: null,
    canvas: els.canvas,
    engine,
    options: { width: WIDTH, height: HEIGHT, wireframes: false, background: 'transparent' },
  });

  const wallProps = { isStatic: true, render: { visible: false }, ...FRICTION };
  const walls = [
    Bodies.rectangle(-(WALL_PAD / 2), HEIGHT / 2, WALL_PAD, HEIGHT * 2, wallProps),
    Bodies.rectangle(WIDTH + WALL_PAD / 2, HEIGHT / 2, WALL_PAD, HEIGHT * 2, wallProps),
    Bodies.rectangle(WIDTH / 2, FLOOR_Y + WALL_PAD / 2, WIDTH, WALL_PAD, wallProps),
  ];
  Composite.add(engine.world, walls);

  // 触点换算：Matter 的 Mouse 会自己按「CSS 尺寸 / 画布像素」换算，
  // 只要 pixelRatio 和画布的 pixelRatio 一致，拿到的就是世界坐标（见 resize）。
  // 以前这里设成 1/scale，把 Matter 已经做过的缩放又抵消了一次，
  // 于是手机上球落在手指约 0.6 倍的位置——越往右偏得越多。
  const mouse = Mouse.create(render.canvas);
  const mouseConstraint = MouseConstraint.create(engine, {
    mouse,
    constraint: { stiffness: 0.2, render: { visible: false } },
  });
  // 只拿来收触摸事件，不让手指拖动场上的球
  mouseConstraint.collisionFilter.mask = 0;
  Composite.add(engine.world, mouseConstraint);
  render.mouse = mouse;

  const effects = [];

  const Game = {
    state: GameStates.READY,
    score: 0,
    highscore: 0,
    startHighscore: 0,
    textures: [],
    currentLevel: 0,
    nextLevel: 0,
    previewBody: null,
    aimX: WIDTH / 2,
    aboveLineSince: null,
    maxReached: 0,

    // 记录以原生端为准：切账号时 WebView 的 localStorage 会被整个清掉。
    // localStorage 只作浏览器里单独调试时的后备，两边取大。
    loadHighscore() {
      let local = 0, host = 0;
      try { local = parseInt(localStorage.getItem(STORAGE_KEY), 10) || 0; } catch (e) { /* 忽略 */ }
      try {
        if (window.AndroidGameBridge && typeof window.AndroidGameBridge.bestScore === 'function') {
          host = window.AndroidGameBridge.bestScore() || 0;
        }
      } catch (e) { /* 忽略 */ }
      Game.highscore = Math.max(local, host);
      els.highscore.innerText = Game.highscore;
      if (local > host) submitScoreToHost(local);
    },
    saveHighscore() {
      try { localStorage.setItem(STORAGE_KEY, String(Game.highscore)); } catch (e) { /* 忽略 */ }
      submitScoreToHost(Game.highscore);
    },

    async preload() {
      Game.textures = await Promise.all(LEVELS.map(loadLevelTexture));
    },

    setScore(v) {
      Game.score = v;
      els.score.innerText = v;
      if (v > 0) {
        els.score.classList.add('bump');
        setTimeout(() => els.score.classList.remove('bump'), 120);
      }
      if (v > Game.highscore) {
        Game.highscore = v;
        els.highscore.innerText = v;
        // 破纪录当场就存：中途返回或切走 App 不会走到 lose()
        Game.saveHighscore();
      }
    },

    pickNextLevel() {
      Game.nextLevel = weightedPick(SPAWN_POOL, SPAWN_WEIGHTS);
      drawNextPreview(Game.nextLevel);
    },

    makeBody(x, y, levelIndex, extra) {
      const level = LEVELS[levelIndex];
      const tex = Game.textures[levelIndex];
      const scale = (level.radius * 2) / tex.size;
      const body = Bodies.circle(x, y, level.radius, Object.assign({}, FRICTION, extra, {
        render: { sprite: { texture: tex.texture, xScale: scale, yScale: scale } },
      }));
      body.levelIndex = levelIndex;
      body.merged = false;
      body.spawnTs = performance.now();
      return body;
    },

    start() {
      Game.state = GameStates.READY;
      Game.startHighscore = Game.highscore;
      Game.setScore(0);
      Game.aboveLineSince = null;
      Game.maxReached = 0;
      effects.length = 0;
      Game.currentLevel = weightedPick(SPAWN_POOL, SPAWN_WEIGHTS);
      Game.pickNextLevel();
      els.ui.style.display = 'block';
      els.end.style.display = 'none';
      spawnPreviewBall();
      Runner.run(runner, engine);
    },

    drop(x) {
      if (Game.state !== GameStates.READY) return;
      Game.state = GameStates.DROP;
      const dropX = clampX(x, Game.currentLevel);
      const body = Game.makeBody(dropX, PREVIEW_HEIGHT, Game.currentLevel, {});
      Composite.add(engine.world, body);

      if (Game.previewBody) {
        Composite.remove(engine.world, Game.previewBody);
        Game.previewBody = null;
      }

      Game.currentLevel = Game.nextLevel;
      Game.pickNextLevel();

      setTimeout(() => {
        if (Game.state === GameStates.DROP) {
          Game.state = GameStates.READY;
          spawnPreviewBall();
        }
      }, 420);
    },

    lose() {
      if (Game.state === GameStates.LOSE) return;
      Game.state = GameStates.LOSE;
      Runner.stop(runner);
      const isRecord = Game.score > Game.startHighscore && Game.score > 0;
      Game.saveHighscore();
      els.endScore.innerText = Game.score;
      els.endBest.innerText = Game.highscore;
      els.endRecord.style.display = isRecord ? 'block' : 'none';
      els.end.style.display = 'flex';
      submitScoreToHost(Game.score);
    },

    celebrate(text) {
      els.celebrateText.innerText = text;
      els.celebrate.style.display = 'flex';
      const clone = els.celebrateText.cloneNode(true);
      els.celebrateText.parentNode.replaceChild(clone, els.celebrateText);
      els.celebrateText = clone;
      setTimeout(() => { els.celebrate.style.display = 'none'; }, 1100);
    },
  };

  function clampX(x, levelIndex) {
    const r = LEVELS[levelIndex].radius;
    return Math.max(r + 2, Math.min(WIDTH - r - 2, x));
  }

  function spawnPreviewBall() {
    Game.previewBody = Game.makeBody(clampX(Game.aimX, Game.currentLevel), PREVIEW_HEIGHT, Game.currentLevel, {
      isStatic: true,
      collisionFilter: { mask: 0x0002 },
    });
    Composite.add(engine.world, Game.previewBody);
  }

  function drawNextPreview(levelIndex) {
    const c = els.nextCanvas;
    const ctx = c.getContext('2d');
    ctx.clearRect(0, 0, c.width, c.height);
    const img = Game.textures[levelIndex].image;
    if (img) ctx.drawImage(img, 0, 0, c.width, c.height);
  }

  // ------------------------------------------------------------------
  // 碰撞合成
  // ------------------------------------------------------------------
  Events.on(engine, 'collisionStart', (e) => {
    if (Game.state === GameStates.LOSE) return;

    for (const pair of e.pairs) {
      const { bodyA, bodyB } = pair;
      if (bodyA.isStatic || bodyB.isStatic) continue;
      if (bodyA.levelIndex === undefined || bodyB.levelIndex === undefined) continue;
      if (bodyA.levelIndex !== bodyB.levelIndex) continue;
      if (bodyA.merged || bodyB.merged) continue;

      bodyA.merged = true;
      bodyB.merged = true;

      const midX = (bodyA.position.x + bodyB.position.x) / 2;
      const midY = (bodyA.position.y + bodyB.position.y) / 2;
      const level = bodyA.levelIndex;

      Composite.remove(engine.world, [bodyA, bodyB]);

      if (level === MAX_LEVEL) {
        const gain = LEVELS[MAX_LEVEL].score * 2;
        addMergeEffect(midX, midY, LEVELS[MAX_LEVEL], gain);
        Game.setScore(Game.score + gain);
        Game.celebrate('西交大 ×2 消除！');
      } else {
        const newLevel = level + 1;
        const newBody = Game.makeBody(midX, midY, newLevel, {});
        Composite.add(engine.world, newBody);
        addMergeEffect(midX, midY, LEVELS[newLevel], LEVELS[newLevel].score);
        Game.setScore(Game.score + LEVELS[newLevel].score);
        Game.maxReached = Math.max(Game.maxReached, newLevel);
        if (newLevel === MAX_LEVEL) Game.celebrate('合成西交大！');
      }
    }
  });

  function addMergeEffect(x, y, level, gain) {
    const now = performance.now();
    effects.push({ kind: 'ring', x, y, r: level.radius, color: level.color, t0: now, dur: 380 });
    const n = 10;
    for (let i = 0; i < n; i++) {
      const a = (i / n) * Math.PI * 2 + rand() * 0.5;
      const speed = 0.18 + rand() * 0.14;
      effects.push({
        kind: 'dot', x, y, angle: a, speed,
        size: 3 + rand() * 4, color: level.color, t0: now, dur: 520,
        startR: level.radius * 0.8,
      });
    }
    effects.push({ kind: 'text', x, y: y - level.radius * 0.4, text: '+' + gain, t0: now, dur: 750 });
  }

  function easeOut(t) { return 1 - Math.pow(1 - t, 3); }

  // ------------------------------------------------------------------
  // 失败判定
  // ------------------------------------------------------------------
  let warnLevel = 0; // 0 无、1 靠近、2 过线
  Events.on(engine, 'afterUpdate', () => {
    if (Game.state === GameStates.LOSE) return;

    const bodies = Composite.allBodies(engine.world).filter((b) => !b.isStatic && b.levelIndex !== undefined);
    const now = performance.now();
    let overLine = false;
    let nearLine = false;

    for (const b of bodies) {
      if (now - b.spawnTs < 300) continue;
      const top = b.position.y - b.circleRadius;
      if (top < LOSE_LINE_Y) overLine = true;
      else if (top < LOSE_LINE_Y + WARN_MARGIN) nearLine = true;
    }

    if (overLine) {
      if (Game.aboveLineSince === null) Game.aboveLineSince = now;
      if (now - Game.aboveLineSince > LOSE_HOLD_MS) {
        Game.lose();
        return;
      }
    } else {
      Game.aboveLineSince = null;
    }
    warnLevel = overLine ? 2 : nearLine ? 1 : 0;
  });

  // ------------------------------------------------------------------
  // 叠加绘制：地面合成链、警戒线、瞄准线、特效。此时画布已按 pixelRatio 变换，直接用世界坐标。
  // ------------------------------------------------------------------
  Events.on(render, 'afterRender', () => {
    const ctx = render.context;
    const now = performance.now();
    ctx.save();

    // 底部合成链：已合成过的等级亮起来
    ctx.fillStyle = COLORS.band;
    ctx.fillRect(0, FLOOR_Y, WIDTH, FLOOR_BAND);
    const icon = 44;
    const gap = (WIDTH - icon * LEVELS.length) / (LEVELS.length + 1);
    for (let i = 0; i < LEVELS.length; i++) {
      const img = Game.textures[i] && Game.textures[i].image;
      if (!img) continue;
      ctx.globalAlpha = i <= Math.max(Game.maxReached, 4) ? 1 : 0.22;
      ctx.drawImage(img, gap + i * (icon + gap), FLOOR_Y + (FLOOR_BAND - icon) / 2, icon, icon);
    }
    ctx.globalAlpha = 1;

    // 警戒线
    const pulse = warnLevel === 2 ? 1 : warnLevel === 1 ? 0.55 + 0.45 * Math.sin(now / 90) : 0;
    ctx.strokeStyle = COLORS.line + (0.28 + 0.62 * pulse) + ')';
    ctx.lineWidth = 2 + 2 * pulse;
    ctx.setLineDash([12, 10]);
    ctx.beginPath();
    ctx.moveTo(16, LOSE_LINE_Y);
    ctx.lineTo(WIDTH - 16, LOSE_LINE_Y);
    ctx.stroke();

    // 瞄准线：从预览球底部垂直到地面，落点一目了然
    if (Game.state === GameStates.READY && Game.previewBody) {
      const p = Game.previewBody.position;
      const r = LEVELS[Game.currentLevel].radius;
      ctx.strokeStyle = COLORS.guide;
      ctx.lineWidth = 3;
      ctx.setLineDash([6, 10]);
      ctx.beginPath();
      ctx.moveTo(p.x, p.y + r + 6);
      ctx.lineTo(p.x, FLOOR_Y);
      ctx.stroke();
    }
    ctx.setLineDash([]);

    // 特效
    for (let i = effects.length - 1; i >= 0; i--) {
      const fx = effects[i];
      const t = (now - fx.t0) / fx.dur;
      if (t >= 1) { effects.splice(i, 1); continue; }
      const k = easeOut(t);
      if (fx.kind === 'ring') {
        ctx.globalAlpha = 1 - t;
        ctx.strokeStyle = fx.color;
        ctx.lineWidth = 6 * (1 - t) + 1;
        ctx.beginPath();
        ctx.arc(fx.x, fx.y, fx.r * (1 + 0.55 * k), 0, Math.PI * 2);
        ctx.stroke();
      } else if (fx.kind === 'dot') {
        // 从球的边缘往外飞，速度不同的粒子散开得远近不一
        const d = fx.startR + fx.speed * 260 * k;
        ctx.globalAlpha = 1 - t;
        ctx.fillStyle = fx.color;
        ctx.beginPath();
        ctx.arc(fx.x + Math.cos(fx.angle) * d, fx.y + Math.sin(fx.angle) * d, fx.size * (1 - t * 0.6), 0, Math.PI * 2);
        ctx.fill();
      } else if (fx.kind === 'text') {
        ctx.globalAlpha = t < 0.7 ? 1 : 1 - (t - 0.7) / 0.3;
        ctx.fillStyle = COLORS.text;
        ctx.font = '800 ' + Math.round(30 + 8 * (1 - k)) + 'px sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(fx.text, fx.x, fx.y - 60 * k);
      }
    }
    ctx.globalAlpha = 1;
    ctx.restore();
  });

  // ------------------------------------------------------------------
  // 输入：按下即把预览球移到手指下，拖动跟随，抬起落球
  // ------------------------------------------------------------------
  function aimAt(x) {
    Game.aimX = x;
    if (Game.state !== GameStates.READY || !Game.previewBody) return;
    Body.setPosition(Game.previewBody, { x: clampX(x, Game.currentLevel), y: PREVIEW_HEIGHT });
  }
  Events.on(mouseConstraint, 'mousedown', (e) => aimAt(e.mouse.position.x));
  Events.on(mouseConstraint, 'mousemove', (e) => aimAt(e.mouse.position.x));
  Events.on(mouseConstraint, 'mouseup', (e) => {
    aimAt(e.mouse.position.x);
    Game.drop(e.mouse.position.x);
  });

  els.restart.addEventListener('click', () => {
    const bodies = Composite.allBodies(engine.world).filter((b) => !walls.includes(b));
    Composite.remove(engine.world, bodies);
    Game.start();
  });

  // Render.run 不防重入，连调两次就有两条绘制循环，所以自己记着开没开
  let rendering = false;
  function startRender() { if (!rendering) { rendering = true; Render.run(render); } }
  function stopRender() { if (rendering) { rendering = false; Render.stop(render); } }

  window.pauseGame = function () {
    Runner.stop(runner);
    stopRender();
  };
  window.resumeGame = function () {
    if (!Game.textures.length) return; // 还没加载完，加载完会自己开
    startRender();
    if (Game.state !== GameStates.LOSE) Runner.run(runner, engine);
  };

  // ------------------------------------------------------------------
  // 自适应：等比缩放到容器里；画布像素密度跟着实际显示尺寸走（不糊、也不浪费）
  // ------------------------------------------------------------------
  function resize() {
    const wrap = document.getElementById('game-wrap');
    const parent = wrap.parentElement;
    const cs = getComputedStyle(parent);
    const availW = parent.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
    const availH = parent.clientHeight - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom);
    const scale = Math.min(availW / WIDTH, availH / HEIGHT);
    const ratio = Math.min(3, scale * (window.devicePixelRatio || 1));

    Render.setPixelRatio(render, ratio);
    mouse.pixelRatio = ratio;

    const w = WIDTH * scale + 'px';
    const h = HEIGHT * scale + 'px';
    wrap.style.width = w;
    wrap.style.height = h;
    render.canvas.style.width = w;
    render.canvas.style.height = h;
    els.ui.style.width = WIDTH + 'px';
    els.ui.style.height = HEIGHT + 'px';
    els.ui.style.transform = 'scale(' + scale + ')';

    // 「下一个」小图同样按实际像素画
    const px = Math.round(52 * ratio);
    els.nextCanvas.width = px;
    els.nextCanvas.height = px;
    if (Game.textures.length) drawNextPreview(Game.nextLevel);
  }
  window.addEventListener('resize', resize);

  Game.loadHighscore();
  // 单枚棋子出错也不能让整局起不来：失败的那级退回写校名的纯色棋子
  Game.preload().catch(() => {
    Game.textures = LEVELS.map((level) => {
      const key = 'token:' + level.key;
      const token = render.textures[key] || (render.textures[key] = bakeToken(level, null));
      return { texture: key, image: token, size: TOKEN_SIZE };
    });
  }).then(() => {
    resize();
    startRender();
    Game.start();
  });
})();
