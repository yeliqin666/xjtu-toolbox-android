// 开发服务器配置：本目录下的 *.js 会被原文拼接进 webpack.config.js。
// ⚠️ 必须以 `;` 开头且不写 IIFE：前一段没有分号，以 `(` / `[` 开头会被当成函数调用。
;
config.devServer = config.devServer || {};
config.devServer.host = '0.0.0.0';
config.devServer.port = 8097;
// 经 nginx / 隧道用 Host 头访问时，webpack-dev-server 默认会以 Invalid Host header 拒绝
config.devServer.allowedHosts = 'all';
// /api/* 同源代理到 campus-api：浏览器直连学校站点拿不到 CORS 头（生产由 nginx 反代）。
config.devServer.proxy = [
  { context: ['/api'], target: 'http://127.0.0.1:3099', changeOrigin: true }
];
