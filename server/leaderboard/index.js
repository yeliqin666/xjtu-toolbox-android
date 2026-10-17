#!/usr/bin/env node
/**
 * 小游戏全服榜服务端。
 *
 * 零依赖：只用 Node 24 内置的 `node:http` 和 `node:sqlite`，没有 npm 包要装，
 * 也就没有供应链和升级维护的负担。数据落在同目录的 data.db（WAL 模式）。
 *
 * 为什么单独一个服务、不并进资料站后端：
 *   这个接口是**公开、无鉴权的写入口**，谁都能 POST 一个分数上来。资料站后端握着
 *   OSS 密钥、阿里云 IMM 凭据和用户 JWT，两者共用进程或数据库都不划算——万一榜单这边
 *   被人打穿，不该连带资料站。
 *
 * 分数不做校验（客户端游戏，防不住），只做「别把荒唐值写进库」的粗筛。发现脏数据手工清：
 *   sqlite3 data.db "delete from scores where score > 100000 and game = '2048';"
 *
 * 部署见同目录 README.md。
 */
import { createServer } from 'node:http';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { moderation } from './moderation.js';

const PORT = Number(process.env.PORT || 4100);
const DB_PATH = resolve(process.env.DB_PATH || 'data.db');

/** 榜长。**必须和客户端 Leaderboard.LIMIT 一致**，否则「够不够上榜」两边算得不一样。 */
const BOARD_LIMIT = Number(process.env.BOARD_LIMIT || 100);

/** 上限只为挡住明显的垃圾值，不是防作弊——分数本来就不可信。 */
const MAX_SCORE = 10_000_000;

/** 每 IP 每分钟的写入/读取次数。按量级估的：真人一次上榜就一次 POST，刷子才会打满。 */
const RATE_SUBMIT_PER_MIN = Number(process.env.RATE_SUBMIT_PER_MIN || 30);
const RATE_READ_PER_MIN = Number(process.env.RATE_READ_PER_MIN || 120);

/** 名字最长几个字。客户端 Leaderboard.MAX_NAME 同值。 */
const MAX_NAME_CHARS = 12;
const ANON = '匿名';

/**
 * 名字里的禁止词（**只是起手一套，不是完整词库**）。
 *
 * 分两类：脏话，以及冒充官方身份的词。真正的词库放同目录的 banned.txt（一行一个，
 * `#` 开头是注释，不进仓库），启动时读进来——这样加词只要改文件 + 重启，
 * 不用等下一次发版；放进 App 里的词库改一次就得发一次版，等于没有。
 *
 * 比对前会先做归一化（见 [skeleton]），所以这里写「傻B」也能拦住「傻ｂ」「傻*b」。
 * 写词表时注意：**别放太短或太常见的组合**（比如单个字、常见姓氏），
 * 归一化会去掉标点和空格，短词很容易误伤正常昵称。
 */
const BUILTIN_BANNED = [
  // 脏话（中文）
  '傻逼', '傻B', '煞笔', '沙比', '尼玛', '你妈', '妈的', '妈的死', '草你', '操你',
  '婊子', '贱人', '畜生', '滚你', '狗东西', '死全家',
  // 脏话（英文）。注意别收太短的词：这是子串匹配，收个 ass 就会把 class、grass 一起拦掉
  'fuck', 'shit', 'bitch', 'cunt', 'asshole', 'whore', 'porn',
  // 冒充身份（玩家昵称冒充官方，比脏话更容易引起误会）
  '管理员', '官方', '客服', '系统', '审核', '开发组', '西交工具箱', '屁岱',
];

/**
 * 归一化成用于比对的「骨架」：NFKC 折叠全角与兼容字形、去掉零宽和控制符、
 * 转小写、再去掉所有空白/标点/符号/下划线。
 *
 * 光做 substring 匹配挡不住「傻*逼」「傻 逼」「傻　逼」「Ｓｈｉｔ」这类写法。
 * 只用于比对，落库的仍是清洗过的人话（见 [cleanName]）。
 */
function skeleton(raw) {
  return raw
    .normalize('NFKC')
    .replace(/[\p{Cf}\p{Cc}]/gu, '')
    .toLowerCase()
    .replace(/[\s\p{P}\p{S}_]/gu, '');
}

function loadBanned() {
  const words = new Set(BUILTIN_BANNED.map(skeleton).filter(Boolean));
  const file = resolve(process.env.BANNED_FILE || 'banned.txt');
  try {
    for (const line of readFileSync(file, 'utf8').split('\n')) {
      const word = line.trim();
      if (!word || word.startsWith('#')) continue;
      const s = skeleton(word);
      if (s) words.add(s);
    }
    console.log(`banned: 已加载 ${file}，共 ${words.size} 条`);
  } catch {
    console.log(`banned: 没有 ${file}，只用内置的 ${words.size} 条起手词`);
  }
  return words;
}

const BANNED = loadBanned();

/** 名字是否命中禁止词（按骨架比对）。 */
function bannedName(name) {
  const s = skeleton(name);
  if (!s) return false;
  for (const word of BANNED) if (s.includes(word)) return true;
  return false;
}

/**
 * 允许上榜的游戏。和客户端 `game/board/Leaderboard.kt` 的 `boardTitle()` 一一对应，
 * 加游戏时两边一起加。
 */
const GAMES = new Set([
  'merge',
  '2048',
  'hop',
  'blocks_marathon',
  'blocks_ultra',
  'blocks_rise',
]);

const db = new DatabaseSync(DB_PATH);
db.exec('PRAGMA journal_mode = WAL');
db.exec(`
  CREATE TABLE IF NOT EXISTS scores (
    game       TEXT    NOT NULL,
    uid        TEXT    NOT NULL,
    name       TEXT    NOT NULL,
    score      INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (game, uid)
  )
`);
// 名次靠 (score 降序, 首次上榜时间升序)：同分时先打到的排前面，且不会因为后来更新名字而变动
db.exec('CREATE INDEX IF NOT EXISTS idx_scores_board ON scores(game, score DESC, created_at ASC)');

// 昵称不能重复：同一款游戏里一个名字只归一个人。
// 用**部分索引**是因为匿名不在此列——「匿名」这一行榜单上本来就会有很多条。
// 这是兜底：正常路径由 handleScore 里的检查给出清楚的 409，索引负责挡住并发窗口。
// 表里若已有重名（老数据）会建不上，那就只记日志、靠应用层检查，不因此起不来。
try {
  db.exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_scores_name ON scores(game, name) WHERE name <> '${ANON}'`);
} catch (e) {
  console.error(`唯一昵称索引没建上（表里可能已有重名），只能靠应用层检查：${e.message}`);
}

const stmtBoard = db.prepare(
  'SELECT name, score FROM scores WHERE game = ? ORDER BY score DESC, created_at ASC LIMIT ?'
);
const stmtCount = db.prepare('SELECT COUNT(*) AS n FROM scores WHERE game = ?');
const stmtMine = db.prepare(`
  SELECT s.name AS name, s.score AS score,
         (SELECT COUNT(*) FROM scores o
           WHERE o.game = s.game
             AND (o.score > s.score OR (o.score = s.score AND o.created_at < s.created_at))) + 1 AS rank
    FROM scores s WHERE s.game = ? AND s.uid = ?
`);
const stmtUpsert = db.prepare(`
  INSERT INTO scores (game, uid, name, score, created_at, updated_at)
  VALUES (?, ?, ?, ?, ?, ?)
  ON CONFLICT(game, uid) DO UPDATE SET
    name = excluded.name,
    score = MAX(scores.score, excluded.score),
    updated_at = excluded.updated_at
`);
/** 这个名字有没有被别人占着（自己的行不算，改回自己的名字不该被拒）。 */
const stmtNameTaken = db.prepare(
  'SELECT 1 FROM scores WHERE game = ? AND name = ? AND uid <> ? LIMIT 1'
);
/** 这个名字是不是自己已经在用的（用来判断"早就过审了"，云审核没跑成时据此放行）。 */
const stmtNameOwned = db.prepare(
  'SELECT 1 FROM scores WHERE game = ? AND uid = ? AND name = ? LIMIT 1'
);

// ── 限流：内存里的滑动窗口，按 IP。重启即清零，够用了 ──
const buckets = new Map();
function rateLimited(ip, max, windowMs) {
  const now = Date.now();
  const b = buckets.get(ip);
  if (!b || now > b.resetAt) {
    buckets.set(ip, { count: 1, resetAt: now + windowMs });
    return false;
  }
  b.count += 1;
  return b.count > max;
}
setInterval(() => {
  const now = Date.now();
  for (const [ip, b] of buckets) if (now > b.resetAt) buckets.delete(ip);
}, 60_000).unref();

/**
 * 名字清洗，规则与客户端 `Leaderboard.sanitizeName` 保持一致：
 * 控制符、零宽字符、空白一律压成一个空格，掐到 [MAX_NAME_CHARS] 个字。
 * 空、或干脆就叫「匿名」的，都当成没起名字。
 */
function cleanName(raw) {
  if (typeof raw !== 'string') return ANON;
  const s = [...raw.replace(/[\p{Cc}\p{Cf}\s]+/gu, ' ').trim()].slice(0, MAX_NAME_CHARS).join('').trim();
  return s && s !== ANON ? s : ANON;
}

function send(res, code, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function fail(res, status, error, reason) {
  send(res, status, reason ? { error, code: reason } : { error });
}

async function readJson(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 4096) throw new Error('请求体过大');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
}

function clientIp(req) {
  // nginx 会带这两个头过来，见 deploy/nginx-games.conf
  const real = req.headers['x-real-ip'];
  if (typeof real === 'string' && real) return real;
  const fwd = req.headers['x-forwarded-for'];
  if (typeof fwd === 'string' && fwd) return fwd.split(',')[0].trim();
  return req.socket.remoteAddress || 'unknown';
}

function validGame(game) {
  return typeof game === 'string' && GAMES.has(game);
}

function validUid(uid) {
  return typeof uid === 'string' && /^[A-Za-z0-9_-]{8,64}$/.test(uid);
}

function validScore(score) {
  return Number.isSafeInteger(score) && score >= 0 && score <= MAX_SCORE;
}

/** GET /games/board?game=&uid=&limit= —— 榜（前 limit 名）加自己在榜上的位置。 */
function handleBoard(req, res, url) {
  const game = url.searchParams.get('game');
  const uid = url.searchParams.get('uid') || '';
  if (!validGame(game)) return fail(res, 400, '未知的游戏');

  const limit = Math.min(Math.max(Number(url.searchParams.get('limit')) || BOARD_LIMIT, 1), BOARD_LIMIT);
  const rows = stmtBoard.all(game, limit).map((r, i) => ({ rank: i + 1, name: r.name, score: r.score }));
  // 只回「在读的这个人自己」那一行：uid 是设备级匿名 ID，不是登录态，拿到别人的 uid 也没用，
  // 但没必要把整张表按 uid 暴露出去
  const mine = validUid(uid) ? stmtMine.get(game, uid) : null;

  send(res, 200, {
    game,
    total: stmtCount.get(game).n,
    rows,
    me: mine ? { rank: mine.rank, name: mine.name, score: mine.score } : null,
  });
}

/** POST /games/score —— 上报成绩。按 (game, uid) 去重，只留最高分。 */
async function handleScore(req, res) {
  let body;
  try {
    body = await readJson(req);
  } catch {
    return fail(res, 400, '请求体不是合法的 JSON');
  }
  const { game, uid, name, score } = body;
  if (!validGame(game)) return fail(res, 400, '未知的游戏');
  if (!validUid(uid)) return fail(res, 400, 'uid 不合法');
  if (!validScore(score)) return fail(res, 400, '分数不合法');

  // 名字不合适就整条打回，让玩家自己换一个——比偷偷改成「匿名」清楚，
  // 客户端拿到这两个 code 会把弹窗留在原地让玩家改（见 Leaderboard.report）。
  const finalName = cleanName(name);
  if (finalName !== ANON) {
    // 第一道：本地词表。顺带挡住冒充官方的昵称——云审核的昵称检测不覆盖这个场景
    // （实测「管理员」返回的是"未检测出风险"），所以这一条只能自己扛。
    if (bannedName(finalName)) {
      return fail(res, 400, '这个名字不合适，换一个', 'name_rejected');
    }
    // 第二道：阿里云内容安全。没配凭据时是空操作，且**审核没跑成时对新名字不放行**（见 moderation.js）
    const verdict = await moderation.check(finalName);
    if (!verdict.ok) {
      if (verdict.unavailable) {
        // 审核没跑成（重试过仍失败）。新名字绝不放进来——这个字段是公开的，
        // 涉政内容不能靠「恰好云挂了」混上榜单；但已审过、只是来更新分数的老名字照常放行，
        // 不因为阿里云抖一下就把老玩家挡在榜外。
        if (!stmtNameOwned.get(game, uid, finalName)) {
          return fail(res, 503, '名字要过一遍审核，稍后再试一次', 'moderation_unavailable');
        }
        console.warn(`moderation 没跑成，放行已审过的名字「${finalName}」（${game}）`);
      } else {
        console.warn(`moderation 拒了昵称「${finalName}」（${game}）：${verdict.reason}`);
        return fail(res, 400, '这个名字不合适，换一个', 'name_rejected');
      }
    }
    // 第三道：昵称独占。放在审核之后、写入之前，中间没有 await，不给并发留窗口；
    // 万一还是撞上（多进程部署之类），下面的唯一索引兜底。
    if (stmtNameTaken.get(game, finalName, uid)) {
      return fail(res, 409, '这个名字已经有人用了，换一个', 'name_taken');
    }
  }

  const now = Date.now();
  const before = stmtMine.get(game, uid);
  try {
    stmtUpsert.run(game, uid, finalName, score, now, now);
  } catch (e) {
    if (String(e.message).includes('UNIQUE')) {
      return fail(res, 409, '这个名字已经有人用了，换一个', 'name_taken');
    }
    throw e;
  }
  const after = stmtMine.get(game, uid);

  send(res, 200, {
    ok: true,
    best: after.score,
    rank: after.rank,
    improved: !before || after.score > before.score,
  });
}

const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  // nginx 的 location 保留了前缀（见 deploy/nginx-games.conf），这里两种写法都认
  const path = url.pathname.replace(/^\/games(?=\/|$)/, '') || '/';
  const ip = clientIp(req);

  if (req.method === 'GET' && path === '/health') {
    // 只报禁止词条数，不吐词表本身——那等于给绕过它的人一张清单
    return send(res, 200, {
      ok: true,
      games: [...GAMES],
      limit: BOARD_LIMIT,
      bannedWords: BANNED.size,
      moderation: moderation.configured ? moderation.service : 'off',
    });
  }
  if (req.method === 'GET' && path === '/board') {
    if (rateLimited(ip, RATE_READ_PER_MIN, 60_000)) return fail(res, 429, '太频繁了，歇一会儿');
    return handleBoard(req, res, url);
  }
  if (req.method === 'POST' && path === '/score') {
    if (rateLimited(ip, RATE_SUBMIT_PER_MIN, 60_000)) return fail(res, 429, '太频繁了，歇一会儿');
    return handleScore(req, res).catch((e) => {
      console.error('score failed:', e.message);
      fail(res, 500, '服务端出错');
    });
  }
  fail(res, 404, '没有这个接口');
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`leaderboard listening on 127.0.0.1:${PORT}, db=${DB_PATH}, limit=${BOARD_LIMIT}`);
  console.log(
    moderation.configured
      ? `moderation: 已启用阿里云内容安全（${moderation.service}）`
      : 'moderation: 没配凭据，只跑本地词表（见 README 的昵称审核一节）',
  );
});

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    server.close(() => {
      db.close();
      process.exit(0);
    });
  });
}
