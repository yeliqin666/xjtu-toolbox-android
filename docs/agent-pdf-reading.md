# 屁岱读取 PDF

Android 的 `get_lms_activity`（带 `file`）和 `read_zyxf_file` 共用文档读取器。

- `mode=info`：文件大小、物理页数、文档信息和已有书签目录，目录定位为从 1 起的 PDF 页码。
- `mode=text`：按页提取文字，不渲染图片；每次最多 20 页、约 12000 字符。过长时返回单页 `text_offset` 续读位置和未读页。
- `mode=images`：只渲染所选页，每次最多 4 页。
- `mode=auto`（默认）：提取文字，空文字页按需转图。

`pages=5` 只读取第 5 页；支持 `1,3,7-8`，默认前 2 页。越界、倒序或超量选择返回错误，不静默改成其他页。LMS 附件优先按 ID 或完整文件名匹配，多项匹配返回候选。

工具实例按账号缓存附件字节 10 分钟，最多 4 份、24 MiB，失败不缓存。

DeepSeek 官方 [Files API](https://api-docs.deepseek.com/guides/files_api/) 当前仅列出图片格式。此实现本地解析 PDF，发送文本或页面图，不向该接口上传 PDF 原件或分片。
