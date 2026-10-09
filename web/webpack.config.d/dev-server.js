// 开发服务器配置。Kotlin/JS 的 webpack 集成会把本目录下所有 *.js **原文拼接**进 webpack.config.js。
//
// ⚠️ 两个必须遵守的写法（都在 campus-web 上实测踩过，交接文档 §6 也记了）：
//   ① **前导分号**：拼接点前面那一段没有分号，文件若以 `(` 或 `[` 开头，JS 会把上一段当函数调用
//      ⇒ `TypeError: {(intermediate value)(…)} is not a function`。故以 `;` 开头，且不写 IIFE。
//   ② 不用 Kotlin DSL 的 `devServer`：2.4.20 的 `wasmJs { browser { } }` 里没有该属性
//      （脚本编译期就 Unresolved reference）。`webpack.config.d/` 是官方逃生口。
;
config.devServer = config.devServer || {};
config.devServer.host = '0.0.0.0';
config.devServer.port = 8097;
// 经 nginx / 隧道用 Host 头访问时，webpack-dev-server 默认会以 Invalid Host header 拒绝
config.devServer.allowedHosts = 'all';
// 开发期把相对路径 /api/* 代理到 campus-api（同源 ⇒ 浏览器端免 CORS）。
// ⚠️ 这不是权宜之计，而是**Web 端的真实部署形状**：浏览器直连 jwxt.xjtu.edu.cn 拿不到
// Access-Control-Allow-Origin，所以 Web 端的数据源必须同源反代（生产由 nginx 提供）。
config.devServer.proxy = [
  { context: ['/api'], target: 'http://127.0.0.1:3099', changeOrigin: true }
];
