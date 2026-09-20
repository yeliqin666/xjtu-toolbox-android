// 合成西交大 —— 基于 moonfloof/suika-game (Unlicense) 改写而来，物理引擎为本地 matter.min.js (MIT)。
// 完整版权声明见 THIRD_PARTY_NOTICES.merge.md。
//
// 相比原版「合成大西瓜」的主要改动：
//   1. 11 级水果 -> 9 级 C9 校徽（见下方 LEVELS，顺序待仓库主核对）；
//   2. 校徽素材用「真实素材优先（.webp 再 .png）、都没有就用 canvas 画占位图」
//      的方式加载，仓库主之后只需把同名素材放进 img/ 目录，代码不用改（见 loadLevelTexture）；
//   3. 只随机投放前 5 级，按权重偏向小级，并预告下一个；
//   4. 失败判定改成「球堆过线持续一段时间」，而不是原版的「一碰到线就死」，
//      避免正常游玩时偶尔蹭线就误判；线附近有球时闪烁预警；
//   5. 合成出最高级（西交大）有全屏庆祝动画；两个西交大相遇直接消除+加分，
//      避免顶级球越堆越多导致画面卡死；
//   6. 游戏结束时通过 JS -> Kotlin 的 JavascriptInterface 把分数回传给宿主 App。

(function () {
  'use strict';

  const { Engine, Render, Runner, Composite, Bodies, Body, Events, Mouse, MouseConstraint } = Matter;

  // ------------------------------------------------------------------
  // 等级配置：C9 高校，从小到大。改这一个数组即可调整整条合成链。
  // key 对应最终素材文件名 img/game_c9_<key>.webp 或 .png（正方形、透明底、512x512）。
  // 顺序（哈工大→中科大→南大→浙大→复旦→上交→北大→清华→西交大）为需求方给定，
  // 待仓库主最终核对是否符合期望的「梗」顺序。
  // ------------------------------------------------------------------
  const LEVELS = [
    { key: 'hit',   name: '哈工大', radius: 22, color: '#1a3f8f', score: 1  },
    { key: 'ustc',  name: '中科大', radius: 28, color: '#c8161d', score: 3  },
    { key: 'nju',   name: '南大',   radius: 34, color: '#7a1f2b', score: 6  },
    { key: 'zju',   name: '浙大',   radius: 42, color: '#003f88', score: 10 },
    { key: 'fudan', name: '复旦',   radius: 50, color: '#003c78', score: 15 },
    { key: 'sjtu',  name: '上交',   radius: 60, color: '#b8272c', score: 21 },
    { key: 'pku',   name: '北大',   radius: 70, color: '#7f1416', score: 28 },
    { key: 'thu',   name: '清华',   radius: 82, color: '#660874', score: 36 },
    { key: 'xjtu',  name: '西交大', radius: 96, color: '#003f7f', score: 60 },
  ];
  const MAX_LEVEL = LEVELS.length - 1;

  // 只投放前 5 级，权重偏向小球（数值越大越常见）。
  const SPAWN_POOL = [0, 1, 2, 3, 4];
  const SPAWN_WEIGHTS = [30, 24, 18, 12, 6];

  const WIDTH = 640;
  const HEIGHT = 960;
  const WALL_PAD = 40;
  const STATUS_BAR_HEIGHT = 56;
  const PREVIEW_HEIGHT = 40;
  const LOSE_LINE_Y = 110; // 红线的世界坐标 y
  const LOSE_HOLD_MS = 1500; // 球堆持续过线多久判负
  const WARN_MARGIN = 46;   // 球顶部距红线多近开始闪烁预警

  const FRICTION = { friction: 0.008, frictionStatic: 0.01, frictionAir: 0.0008, restitution: 0.15 };

  const STORAGE_KEY = 'merge-xjtu-highscore';

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

  // ------------------------------------------------------------------
  // 贴图加载：依次尝试真实素材 img/game_c9_<key>.webp / .png（正式素材是
  // 512x512 WebP），都找不到就用 canvas 画一个纯色圆 + 校名文字当占位图，
  // 三种情况下最终都产出一个能直接喂给 Matter.js sprite 的图片地址/dataURL，
  // 业务代码不用关心具体用了哪一种。
  // ------------------------------------------------------------------
  const TEXTURE_SIZE = 512; // 与正式校徽素材尺寸保持一致，占位图也按同样尺寸画

  function drawPlaceholder(level) {
    const size = TEXTURE_SIZE;
    const c = document.createElement('canvas');
    c.width = size;
    c.height = size;
    const ctx = c.getContext('2d');
    ctx.clearRect(0, 0, size, size);
    ctx.beginPath();
    ctx.arc(size / 2, size / 2, size / 2 - 6, 0, Math.PI * 2);
    ctx.fillStyle = level.color;
    ctx.fill();
    ctx.lineWidth = 6;
    ctx.strokeStyle = 'rgba(255,255,255,0.85)';
    ctx.stroke();
    ctx.fillStyle = '#ffffff';
    ctx.font = 'bold ' + Math.floor(size * 0.26) + 'px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(level.name, size / 2, size / 2);
    return c.toDataURL('image/png');
  }

  function tryLoadImage(path) {
    return new Promise((resolve) => {
      const img = new Image();
      img.onload = () => resolve(path);
      img.onerror = () => resolve(null);
      img.src = path;
    });
  }

  async function loadLevelTexture(level) {
    // 正式素材是 WebP，先试 .webp 再试 .png，都没有就用 canvas 占位图。
    const webp = await tryLoadImage('./img/game_c9_' + level.key + '.webp');
    if (webp) return webp;
    const png = await tryLoadImage('./img/game_c9_' + level.key + '.png');
    if (png) return png;
    return drawPlaceholder(level);
  }

  // ------------------------------------------------------------------
  // 与宿主 App 通信：优先用 JavascriptInterface（window.AndroidGameBridge），
  // 因为分数回传只在「游戏结束」这一个时间点发生一次，事件驱动天然合适，
  // 不需要 evaluateJavascript 轮询去反复查询 JS 侧状态。
  // 找不到桥（比如在普通浏览器里调试）时静默忽略。
  // ------------------------------------------------------------------
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
    nextCanvas: document.getElementById('game-next-fruit'),
    end: document.getElementById('game-end-container'),
    endScore: document.getElementById('game-end-score-value'),
    highscore: document.getElementById('game-highscore-value'),
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
    options: { width: WIDTH, height: HEIGHT, wireframes: false, background: '#FFDCAE' },
  });

  const wallProps = { isStatic: true, render: { fillStyle: '#FFEEDB' }, ...FRICTION };
  const walls = [
    Bodies.rectangle(-(WALL_PAD / 2), HEIGHT / 2, WALL_PAD, HEIGHT, wallProps),
    Bodies.rectangle(WIDTH + WALL_PAD / 2, HEIGHT / 2, WALL_PAD, HEIGHT, wallProps),
    Bodies.rectangle(WIDTH / 2, HEIGHT + WALL_PAD / 2 - STATUS_BAR_HEIGHT, WIDTH, WALL_PAD, wallProps),
  ];
  Composite.add(engine.world, walls);

  const mouse = Mouse.create(render.canvas);
  mouse.pixelRatio = 1; // 实际值在首次 resize() 时根据画布缩放比例重新计算
  const mouseConstraint = MouseConstraint.create(engine, {
    mouse,
    constraint: { stiffness: 0.2, render: { visible: false } },
  });
  Composite.add(engine.world, mouseConstraint);
  render.mouse = mouse;

  const Game = {
    state: GameStates.READY,
    score: 0,
    highscore: 0,
    textures: [],
    currentLevel: 0,
    nextLevel: 0,
    previewBody: null,
    aboveLineSince: null,
    lastDropTs: 0,

    loadHighscore() {
      try {
        const v = localStorage.getItem(STORAGE_KEY);
        Game.highscore = v ? parseInt(v, 10) || 0 : 0;
      } catch (e) { Game.highscore = 0; }
      els.highscore.innerText = Game.highscore;
    },
    saveHighscoreIfNeeded() {
      if (Game.score <= Game.highscore) return;
      Game.highscore = Game.score;
      try { localStorage.setItem(STORAGE_KEY, String(Game.highscore)); } catch (e) { /* 忽略存储失败 */ }
    },

    async preload() {
      Game.textures = await Promise.all(LEVELS.map(loadLevelTexture));
    },

    setScore(v) {
      Game.score = v;
      els.score.innerText = Game.score;
    },

    pickNextLevel() {
      Game.nextLevel = weightedPick(SPAWN_POOL, SPAWN_WEIGHTS);
      drawNextPreview(Game.nextLevel);
    },

    makeBody(x, y, levelIndex, extra) {
      const level = LEVELS[levelIndex];
      const body = Bodies.circle(x, y, level.radius, Object.assign({}, FRICTION, extra, {
        render: { sprite: { texture: Game.textures[levelIndex], xScale: (level.radius * 2) / TEXTURE_SIZE, yScale: (level.radius * 2) / TEXTURE_SIZE } },
      }));
      body.levelIndex = levelIndex;
      body.merged = false;
      body.spawnTs = performance.now();
      return body;
    },

    start() {
      Game.state = GameStates.READY;
      Game.setScore(0);
      Game.aboveLineSince = null;
      Game.currentLevel = weightedPick(SPAWN_POOL, SPAWN_WEIGHTS);
      Game.pickNextLevel();
      els.ui.style.display = 'block';
      els.end.style.display = 'none';
      spawnPreviewBall();
      Runner.run(runner, engine);
      Render.run(render);
    },

    drop(x) {
      if (Game.state !== GameStates.READY) return;
      Game.state = GameStates.DROP;
      const clampedX = Math.max(LEVELS[Game.currentLevel].radius + WALL_PAD / 2, Math.min(WIDTH - LEVELS[Game.currentLevel].radius - WALL_PAD / 2, x));
      const body = Game.makeBody(clampedX, PREVIEW_HEIGHT, Game.currentLevel, {});
      Composite.add(engine.world, body);
      Game.lastDropTs = performance.now();

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
      Game.saveHighscoreIfNeeded();
      els.endScore.innerText = Game.score;
      els.highscore.innerText = Game.highscore;
      els.end.style.display = 'flex';
      submitScoreToHost(Game.score);
    },

    celebrate(text) {
      els.celebrateText.innerText = text;
      els.celebrate.style.display = 'flex';
      // 重新触发一次 CSS 动画
      const clone = els.celebrateText.cloneNode(true);
      els.celebrateText.parentNode.replaceChild(clone, els.celebrateText);
      els.celebrateText = clone;
      setTimeout(() => { els.celebrate.style.display = 'none'; }, 900);
    },
  };

  function spawnPreviewBall() {
    Game.previewBody = Game.makeBody(WIDTH / 2, PREVIEW_HEIGHT, Game.currentLevel, {
      isStatic: true,
      collisionFilter: { mask: 0x0002 },
    });
    Composite.add(engine.world, Game.previewBody);
  }

  function drawNextPreview(levelIndex) {
    const ctx = els.nextCanvas.getContext('2d');
    ctx.clearRect(0, 0, 48, 48);
    const img = new Image();
    img.onload = () => ctx.drawImage(img, 0, 0, 48, 48);
    img.src = Game.textures[levelIndex];
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
      if (bodyA.merged || bodyB.merged) continue; // 保证同一帧每个球只参与一次合成

      bodyA.merged = true;
      bodyB.merged = true;

      const midX = (bodyA.position.x + bodyB.position.x) / 2;
      const midY = (bodyA.position.y + bodyB.position.y) / 2;
      const level = bodyA.levelIndex;

      Composite.remove(engine.world, [bodyA, bodyB]);
      addPopEffect(midX, midY, LEVELS[level].radius);

      if (level === MAX_LEVEL) {
        // 两个西交大相遇：直接消除 + 加分，不再生成更高一级的球，
        // 否则顶级球会越堆越多，最终把屏幕铺满导致游戏卡死。
        Game.setScore(Game.score + LEVELS[MAX_LEVEL].score * 2);
        Game.celebrate('西交大 x2！消除！');
      } else {
        const newLevel = level + 1;
        const newBody = Game.makeBody(midX, midY, newLevel, {});
        Composite.add(engine.world, newBody);
        Game.setScore(Game.score + LEVELS[newLevel].score);
        if (newLevel === MAX_LEVEL) {
          Game.celebrate('合成西交大！');
        }
      }
    }
  });

  function addPopEffect(x, y, r) {
    const circle = Bodies.circle(x, y, r, {
      isStatic: true,
      collisionFilter: { mask: 0x0000 },
      render: { fillStyle: 'rgba(255,255,255,0.55)' },
    });
    Composite.add(engine.world, circle);
    setTimeout(() => Composite.remove(engine.world, circle), 120);
  }

  // ------------------------------------------------------------------
  // 失败判定：球堆持续过线一段时间才判负（而不是一碰线就死），
  // 线附近有球时闪烁预警。用 afterUpdate 每帧检查一次。
  // ------------------------------------------------------------------
  let warnFlashOn = false;
  Events.on(engine, 'afterUpdate', () => {
    if (Game.state === GameStates.LOSE) return;

    const bodies = Composite.allBodies(engine.world).filter((b) => !b.isStatic && b.levelIndex !== undefined);
    const now = performance.now();
    let overLine = false;
    let nearLine = false;

    for (const b of bodies) {
      // 刚落下的球给 300ms 缓冲，避免出生点本来就在预警线附近导致误判
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

    warnFlashOn = overLine || (nearLine && Math.floor(now / 300) % 2 === 0);
  });

  Events.on(render, 'afterRender', () => {
    const ctx = render.context;
    ctx.save();
    ctx.strokeStyle = warnFlashOn ? 'rgba(255,20,20,0.95)' : 'rgba(255,20,20,0.35)';
    ctx.lineWidth = warnFlashOn ? 4 : 2;
    ctx.setLineDash([10, 8]);
    ctx.beginPath();
    ctx.moveTo(0, LOSE_LINE_Y);
    ctx.lineTo(WIDTH, LOSE_LINE_Y);
    ctx.stroke();
    ctx.restore();
  });

  // ------------------------------------------------------------------
  // 输入：跟随手指/鼠标移动预览球，抬起时落球
  // ------------------------------------------------------------------
  Events.on(mouseConstraint, 'mousemove', (e) => {
    if (Game.state !== GameStates.READY || !Game.previewBody) return;
    Body.setPosition(Game.previewBody, { x: e.mouse.position.x, y: PREVIEW_HEIGHT });
  });
  Events.on(mouseConstraint, 'mouseup', (e) => {
    Game.drop(e.mouse.position.x);
  });

  els.restart.addEventListener('click', () => {
    // 清空场上所有非墙体的球
    const bodies = Composite.allBodies(engine.world).filter((b) => !walls.includes(b) && b !== mouseConstraint.constraint);
    Composite.remove(engine.world, bodies);
    Game.start();
  });

  // ------------------------------------------------------------------
  // 供 Kotlin 壳在页面不可见时调用，避免物理循环在后台空转
  // ------------------------------------------------------------------
  window.pauseGame = function () {
    Runner.stop(runner);
  };
  window.resumeGame = function () {
    if (Game.state !== GameStates.LOSE) Runner.run(runner, engine);
  };

  // ------------------------------------------------------------------
  // 自适应缩放：按容器尺寸等比缩放画布
  // ------------------------------------------------------------------
  function resize() {
    const wrap = document.getElementById('game-wrap');
    const parent = wrap.parentElement;
    const availW = parent.clientWidth;
    const availH = parent.clientHeight;
    const scale = Math.min(availW / WIDTH, availH / HEIGHT);
    wrap.style.width = WIDTH * scale + 'px';
    wrap.style.height = HEIGHT * scale + 'px';
    render.canvas.style.width = WIDTH * scale + 'px';
    render.canvas.style.height = HEIGHT * scale + 'px';
    els.ui.style.width = WIDTH + 'px';
    els.ui.style.height = HEIGHT + 'px';
    els.ui.style.transform = `scale(${scale})`;
    mouse.pixelRatio = WIDTH / (WIDTH * scale);
  }
  window.addEventListener('resize', resize);

  Game.loadHighscore();
  Game.preload().then(() => {
    resize();
    Game.start();
  });
})();
