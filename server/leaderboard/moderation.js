/**
 * 昵称内容审核：阿里云内容安全「用户昵称检测_专业版」。
 *
 * 走 `TextModerationPlus`（Version 2022-03-02）+ ServiceCode `nickname_detection_pro`。
 * 注意**不是** `TextModeration`：那个接口不认 `_pro` 结尾的服务码，会返回
 * `service is invalid`；`nickname_detection`（非专业版）才是它的。
 *
 * 自己实现 POP 签名而不是引 SDK：这个服务整体零依赖，为了一个接口拖进
 * @alicloud/green20220302 及其一整串 transitive 依赖不划算。签名算法只有二十行。
 *
 * 凭据从环境变量或同目录的 aliyun.env 读（见 README，文件权限 0640 root:www）。
 * 没有凭据时 [configured] 为 false，[check] 直接放行——本地词表仍然挡着，
 * 所以在你配好之前服务照常能用。
 *
 * **审核没跑成的时候不再当成放行**（超时、鉴权错、响应看不懂）：先重试一次，仍失败就返回
 * `unavailable`，由调用方区分处理——**新名字绝不放进来**（这个字段是公开的，涉政内容不能靠
 * 「恰好云挂了」混上榜单），而已经审过的老名字照常更新分数，不因为阿里云抖一下把人挡在榜外。
 * 见 index.js 的 handleScore。
 */
import { createHmac, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const ENDPOINT = process.env.GREEN_ENDPOINT || 'https://green-cip.cn-shanghai.aliyuncs.com/';
const SERVICE = process.env.GREEN_SERVICE || 'nickname_detection_pro';
const ACTION = 'TextModerationPlus';
const VERSION = '2022-03-02';
const TIMEOUT_MS = Number(process.env.GREEN_TIMEOUT_MS || 3000);

/** 第一次没跑成时间隔多久重试。只重试一次，宁可让玩家稍后再试，也不放未审的名字进榜。 */
const RETRY_DELAY_MS = Number(process.env.GREEN_RETRY_DELAY_MS || 400);

/** 从这一档起拦下：none / low / medium / high。默认连 low 都拦——榜是给人看的。 */
const BLOCK_FROM = (process.env.GREEN_BLOCK_LEVEL || 'low').toLowerCase();
const LEVELS = { none: 0, low: 1, medium: 2, high: 3 };

/** 结果缓存：同一个名字反复提交时不重复计费。上榜很少，2000 条够用了。 */
const CACHE_LIMIT = 2000;
const cache = new Map();

function loadCredentials() {
  const fromEnv = {
    id: process.env.GREEN_ACCESS_KEY_ID,
    secret: process.env.GREEN_ACCESS_KEY_SECRET,
  };
  if (fromEnv.id && fromEnv.secret) return fromEnv;
  try {
    const file = resolve(process.env.GREEN_CRED_FILE || 'aliyun.env');
    const props = Object.fromEntries(
      readFileSync(file, 'utf8')
        .split('\n')
        .map((l) => l.trim())
        .filter((l) => l && !l.startsWith('#'))
        .map((l) => {
          const i = l.indexOf('=');
          return [l.slice(0, i).trim(), l.slice(i + 1).trim()];
        }),
    );
    if (props.accessKeyId && props.accessKeySecret) {
      console.log(`green: 已从 ${file} 读取凭据，审核服务 ${SERVICE} @ ${ENDPOINT}`);
      return { id: props.accessKeyId, secret: props.accessKeySecret };
    }
  } catch {
    /* 没配凭据是正常状态 */
  }
  return { id: '', secret: '' };
}

const CRED = loadCredentials();

/** POP 签名要求的百分号编码：encodeURIComponent 之外还要补三个字符。 */
function percentEncode(str) {
  return encodeURIComponent(str)
    .replace(/\+/g, '%20')
    .replace(/\*/g, '%2A')
    .replace(/%7E/g, '~');
}

/** 把参数按 POP 规则排好、签好，返回可以直接当 body 发的表单串。 */
function signedBody(params) {
  const canonical = Object.keys(params)
    .sort()
    .map((k) => `${percentEncode(k)}=${percentEncode(params[k])}`)
    .join('&');
  const stringToSign = `POST&${percentEncode('/')}&${percentEncode(canonical)}`;
  const signature = createHmac('sha1', `${CRED.secret}&`).update(stringToSign).digest('base64');
  return `${canonical}&Signature=${percentEncode(signature)}`;
}

async function callApi(content) {
  const params = {
    AccessKeyId: CRED.id,
    Action: ACTION,
    Format: 'JSON',
    SignatureMethod: 'HMAC-SHA1',
    SignatureNonce: randomUUID().replace(/-/g, ''),
    SignatureVersion: '1.0',
    Timestamp: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    Version: VERSION,
    Service: SERVICE,
    ServiceParameters: JSON.stringify({ content }),
  };
  const resp = await fetch(ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: signedBody(params),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  return resp.json();
}

/**
 * 解读审核结果。
 *   { ok: true }                     干净
 *   { ok: false, reason: '...' }     命中风险，reason 是一句人话
 * 响应形状（TextModerationPlus）：
 *   { Code: 200, Data: { Result: [{ Label, Description, Confidence, RiskWords }], RiskLevel } }
 * 干净时是一条 `Label: "nonLabel"`；命中时会有多条（涉政类还会展开繁体与插入符号变体，
 * 例如「法&轮功」会连「法輪功」一起命中）。TextModeration 那套的 `Data` 是 JSON 字符串，
 * 所以两种都兜一下。
 */
function verdict(json) {
  if (Number(json?.Code) !== 200) {
    // AccessDeniedDetail 里带着服务端认定的 action（AuthAction），
    // 配 RAM 权限对不上时全靠它——所以一起打出来，别只留 Code/Message
    const denied = json?.AccessDeniedDetail
      ? ` AccessDeniedDetail=${JSON.stringify(json.AccessDeniedDetail)}`
      : '';
    throw new Error(`Code=${json?.Code} ${json?.Message || ''}${denied}`);
  }
  const data = typeof json.Data === 'string' ? JSON.parse(json.Data) : json.Data;
  const level = String(data?.RiskLevel || 'none').toLowerCase();
  const hit = (data?.Result || []).filter((r) => r?.Label && r.Label !== 'nonLabel');
  const blocked = hit.length > 0 || (LEVELS[level] ?? 0) >= (LEVELS[BLOCK_FROM] ?? 1);
  if (!blocked) return { ok: true };
  const why = hit.map((r) => `${r.Label}/${r.Description || ''}${r.RiskWords ? `(${r.RiskWords})` : ''}`).join('; ');
  return { ok: false, reason: why || `riskLevel=${level}` };
}

export const moderation = {
  configured: Boolean(CRED.id && CRED.secret),
  service: SERVICE,

  /**
   * 查一个昵称。
   *
   * 返回 `{ ok: true }`（放行）、`{ ok: false, reason }`（命中风险）、
   * 或 `{ ok: false, unavailable: true }`（**审核本身没跑成**：超时、鉴权错、响应看不懂）。
   *
   * 第三种要跟第二种分开：前者是"这个名字有问题"，后者是"我们没审成"。
   * 调用方对两者的处理完全不同（见 index.js：一个让玩家改名，一个只挡新名字）。
   * 注意这里**不再吞掉失败当成放行**——涉政这类内容不能靠"恰好云挂了"混进公开榜单。
   */
  async check(name) {
    if (!this.configured) return { ok: true };
    const cached = cache.get(name);
    if (cached !== undefined) return cached;

    let result;
    try {
      result = verdict(await callApi(name));
    } catch (first) {
      // 先重试一次：阿里云偶发抖动（策略传播、灰度）不该立刻让玩家吃闭门羹
      try {
        await new Promise((r) => setTimeout(r, RETRY_DELAY_MS));
        result = verdict(await callApi(name));
      } catch (second) {
        console.warn(`green: 审核没跑成（重试过）「${name}」：${second.message}`);
        return { ok: false, unavailable: true }; // 不缓存：下次还得真查
      }
    }
    if (cache.size >= CACHE_LIMIT) cache.delete(cache.keys().next().value);
    cache.set(name, result);
    return result;
  },
};
