# 屁岱读取文档

Android 的 `get_lms_activity`（带 `file`）和 `read_zyxf_file` 共用 `AgentDocReader`。

## PDF

用系统 `PdfRenderer`（minSdk 35 起能取文字、检索），不再依赖 pdfbox。

- `mode=info`：页数，以及每页首行（幻灯片多半就是标题，前 150 页）当目录用；无文字的页合并成区间列出。系统接口不提供书签。
- `mode=search` + `query`：逐页 `searchText`，列出命中页、处数和片段（前 30 页）。扫描页无文字层，检索不到。
- `mode=text`：按页取文字，每次最多 20 页、约 12000 字符；过长时返回单页 `text_offset` 续读位置和未读页。
- `mode=images`：只渲染所选页，每次最多 4 页。
- `mode=auto`（默认）：取文字；空文字页有 DeepSeek Key 时逐页 OCR（每次最多 8 页），识别为空或失败的页退回页面图；没有 Key 时转页面图。

`pages=5` 只读第 5 页；支持 `1,3,7-8`，默认前 2 页。越界、倒序或超量选择返回错误，不静默改成其他页。LMS 附件优先按 ID 或完整文件名匹配，多项匹配返回候选。

## OCR

`DeepSeekOcr` 调独立 OCR `POST /v1/tools/ocr`（`text_recognition`，1.5 元/千页），Key 与联网搜索相同（`AgentConfig.webSearchKey`），搜索关掉时也不 OCR。

- 扫描页按单页 JPEG 送，不送整份 PDF：省钱、省时，也绕开服务端整份解析会随机丢页的问题（2026-11-13 实测，10 页样本：文件块 8 次中 2 次完整，OCR 12 次中 5 次完整）。
- 服务端偶发把整页返回空串且照样 `stop`，空页最多再识别两次，按页取首个非空结果。
- 结果按「文件内容 + 页码」缓存（进程内 64 条），翻页、续读不重复计费。
- 老式 `doc` / `ppt` 本机解析不了，整份送 OCR；没有 Key 时提示用户。

## 其他格式

`docx` / `pptx` / `xlsx` 本机解 zip 取正文，图片直接交给模型看，纯文本按 6000 字一段分段。

工具实例按账号缓存附件字节 10 分钟，最多 4 份、24 MiB，失败不缓存。
