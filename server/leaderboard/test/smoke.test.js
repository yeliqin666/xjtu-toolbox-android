// 起一个真进程、走真 HTTP 的冒烟测试：接口的排序、去重、入参校验都在这里过一遍。
// 不引入任何测试依赖，用 node 自带的 test runner 和 fetch。
//
//   cd server/leaderboard && npm test
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const PORT = 4199;
const BASE = `http://127.0.0.1:${PORT}/games`;
let child;
let dir;

before(async () => {
  dir = mkdtempSync(join(tmpdir(), 'lb-test-'));
  child = spawn(process.execPath, ['index.js'], {
    cwd: new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'),
    env: {
      ...process.env,
      PORT: String(PORT),
      DB_PATH: join(dir, 'test.db'),
      // 用例会打几十次提交，把线上那套限流关掉，否则测的是 429 不是逻辑
      RATE_SUBMIT_PER_MIN: '10000',
      RATE_READ_PER_MIN: '10000',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  // 等端口起来
  for (let i = 0; i < 100; i++) {
    try {
      const r = await fetch(`${BASE}/health`);
      if (r.ok) return;
    } catch { /* 还没起来 */ }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error('服务没能在 10 秒内起来');
});

after(async () => {
  if (child) {
    const exited = new Promise((r) => child.once('exit', r));
    child.kill('SIGTERM');
    await Promise.race([exited, new Promise((r) => setTimeout(r, 2000))]);
  }
  try {
    if (dir) rmSync(dir, { recursive: true, force: true });
  } catch {
    // Windows 上刚退出的进程可能还占着 data.db，临时目录留着不影响测试结果
  }
});

const post = (body) =>
  fetch(`${BASE}/score`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

const board = (game, uid, limit = 100) =>
  fetch(`${BASE}/board?game=${game}&uid=${uid}&limit=${limit}`).then((r) => r.json());

test('新分数进入榜单并给出名次', async () => {
  const res = await post({ game: '2048', uid: 'uid-aaaa-0001', name: '张三', score: 500 });
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.best, 500);
  assert.equal(body.improved, true);

  const b = await board('2048', 'uid-aaaa-0001');
  assert.equal(b.total, 1);
  assert.deepEqual(b.rows, [{ rank: 1, name: '张三', score: 500 }]);
  assert.deepEqual(b.me, { rank: 1, name: '张三', score: 500 });
});

test('同一个人同一款游戏只有一行，分数只增不减', async () => {
  await post({ game: '2048', uid: 'uid-aaaa-0001', name: '张三', score: 300 });
  let b = await board('2048', 'uid-aaaa-0001');
  assert.equal(b.total, 1, '低分不该新增一行');
  assert.equal(b.me.score, 500, '低分不该覆盖高分');

  await post({ game: '2048', uid: 'uid-aaaa-0001', name: '张三', score: 900 });
  b = await board('2048', 'uid-aaaa-0001');
  assert.equal(b.total, 1);
  assert.equal(b.me.score, 900);
});

test('按分数降序排列，同分先上榜的在前', async () => {
  await post({ game: 'hop', uid: 'uid-hop-0001', name: 'A', score: 10 });
  await new Promise((r) => setTimeout(r, 5));
  await post({ game: 'hop', uid: 'uid-hop-0002', name: 'B', score: 10 });
  await post({ game: 'hop', uid: 'uid-hop-0003', name: 'C', score: 30 });

  const b = await board('hop', 'uid-hop-0001');
  assert.deepEqual(b.rows.map((r) => r.name), ['C', 'A', 'B']);
});

test('榜外的人也能问到自己的名次', async () => {
  for (let i = 0; i < 5; i++) {
    await post({ game: 'merge', uid: `uid-merge-000${i}`, name: `P${i}`, score: 100 - i });
  }
  const b = await board('merge', 'uid-merge-0004', 2);
  assert.deepEqual(b.rows.map((r) => r.name), ['P0', 'P1'], '榜只放前 2 名');
  assert.equal(b.me.rank, 5, '自己的名次照给');
  assert.equal(b.total, 5);
});

test('匿名、空白名、控制字符都落成匿名', async () => {
  await post({ game: 'blocks_ultra', uid: 'uid-anon-00001', name: '', score: 7 });
  await post({ game: 'blocks_ultra', uid: 'uid-anon-00002', name: '   ', score: 6 });
  await post({ game: 'blocks_ultra', uid: 'uid-anon-00003', name: '匿名', score: 5 });
  await post({ game: 'blocks_ultra', uid: 'uid-anon-00004', name: 'a\u0000b\nc', score: 4 });

  const b = await board('blocks_ultra', 'uid-anon-00001');
  assert.deepEqual(b.rows.map((r) => r.name), ['匿名', '匿名', '匿名', 'a b c']);
});

test('名字超长会被截断', async () => {
  await post({ game: 'blocks_rise', uid: 'uid-long-00001', name: '一二三四五六七八九十十一十二十三', score: 1 });
  const b = await board('blocks_rise', 'uid-long-00001');
  assert.equal(b.rows[0].name, '一二三四五六七八九十十一');
});

test('未知游戏、坏 uid、坏分数一律 400', async () => {
  assert.equal((await post({ game: 'gomoku', uid: 'uid-aaaa-0001', name: 'x', score: 1 })).status, 400);
  assert.equal((await post({ game: '2048', uid: '短', name: 'x', score: 1 })).status, 400);
  assert.equal((await post({ game: '2048', uid: 'uid-aaaa-0001', name: 'x', score: -1 })).status, 400);
  assert.equal((await post({ game: '2048', uid: 'uid-aaaa-0001', name: 'x', score: 1e12 })).status, 400);
  assert.equal((await post({ game: '2048', uid: 'uid-aaaa-0001', name: 'x', score: 1.5 })).status, 400);
});

test('不认识的路径 404', async () => {
  assert.equal((await fetch(`${BASE}/nope`)).status, 404);
});

test('敏感词被拒，并带上 name_rejected 让客户端能重问', async () => {
  const res = await post({ game: 'blocks_rise', uid: 'uid-bad-000001', name: '傻逼', score: 1 });
  assert.equal(res.status, 400);
  const body = await res.json();
  assert.equal(body.code, 'name_rejected');
  // 被拒的这次不该留下任何行
  const b = await board('blocks_rise', 'uid-bad-000001');
  assert.equal(b.me, null);
});

test('变体写法一样拦得住（归一化后再比对）', async () => {
  const variants = ['傻 逼', '傻*逼', '傻　逼', '傻\u200b逼', '傻Ｂ', 'Ｓｈｉｔ'];
  for (const [i, name] of variants.entries()) {
    const res = await post({ game: 'blocks_rise', uid: `uid-bad-v${i}0001`, name, score: 1 });
    assert.equal(res.status, 400, `「${name}」应该被拒`);
  }
});

test('冒充官方身份的昵称被拒', async () => {
  for (const [i, name] of ['管理员', '客服小助手', '系统公告'].entries()) {
    const res = await post({ game: 'blocks_rise', uid: `uid-role-${i}0001`, name, score: 1 });
    assert.equal(res.status, 400, `「${name}」应该被拒`);
  }
});

test('正常名字不被误伤', async () => {
  const ok = ['阿黄', '小西', '西jie', '管院小王', '乐傻傻', 'Tester'];
  for (const [i, name] of ok.entries()) {
    const res = await post({ game: 'blocks_rise', uid: `uid-ok-${i}0001`, name, score: 1 });
    assert.equal(res.status, 200, `「${name}」不该被拒`);
  }
});

test('零宽字符混不进落库的名字', async () => {
  await post({ game: 'merge', uid: 'uid-zw-000001', name: '阿\u200b黄', score: 1 });
  // 榜上前几名是别的用例灌的，看自己的那一行
  const b = await board('merge', 'uid-zw-000001');
  assert.equal(b.me.name, '阿 黄');
});

test('昵称独占：同一款游戏里被别人占着的名字会被拒', async () => {
  const first = await post({ game: 'hop', uid: 'uid-uniq-000001', name: '独占名', score: 5 });
  assert.equal(first.status, 200);

  const second = await post({ game: 'hop', uid: 'uid-uniq-000002', name: '独占名', score: 9 });
  assert.equal(second.status, 409);
  assert.equal((await second.json()).code, 'name_taken');

  // 被拒的那次不该留下任何痕迹
  const b = await board('hop', 'uid-uniq-000002');
  assert.equal(b.me, null);
});

test('自己的名字不算重复，可以反复提交', async () => {
  await post({ game: 'hop', uid: 'uid-uniq-000003', name: '自己的名', score: 5 });
  const again = await post({ game: 'hop', uid: 'uid-uniq-000003', name: '自己的名', score: 7 });
  assert.equal(again.status, 200);
  assert.equal((await again.json()).best, 7);
});

test('同一个名字可以用在不同游戏的榜上', async () => {
  const a = await post({ game: 'hop', uid: 'uid-uniq-000004', name: '跨榜名', score: 1 });
  const b = await post({ game: '2048', uid: 'uid-uniq-000005', name: '跨榜名', score: 1 });
  assert.equal(a.status, 200, '跳一跳榜上先占住');
  assert.equal(b.status, 200, '另一款游戏不该受影响');
});

test('匿名不参与独占：多少人都可以叫匿名', async () => {
  const a = await post({ game: '2048', uid: 'uid-uniq-000006', name: '', score: 1 });
  const b = await post({ game: '2048', uid: 'uid-uniq-000007', name: '', score: 2 });
  assert.equal(a.status, 200);
  assert.equal(b.status, 200);
  // 榜上的匿名行可以有很多条——这正是唯一索引要排除它们的原因
  const board2048 = await (await fetch(`${BASE}/board?game=2048&uid=uid-uniq-000006&limit=100`)).json();
  assert.ok(board2048.rows.filter((r) => r.name === '匿名').length >= 2, '匿名行应当可以重复');
});
