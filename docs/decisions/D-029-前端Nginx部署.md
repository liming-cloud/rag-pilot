# D-029 前端用 Nginx 部署，字体随前端打包（2026-10-29）

Compose 增加 web 服务（`deploy/web.Dockerfile`）：Node 24 构建前端，`nginx:1.28-alpine` 提供静态文件，并把 `/api` 转发到 app。员工只访问 web 端口（`WEB_PORT`），app 的 8080 只绑定本机，用于健康检查。流式问答关闭代理缓冲、读超时 150 秒（后端单次流式最长 2 分钟）；请求体上限 55 MB，与后端一致。不另加网关或 HTTPS 终止：客户环境一般已有统一入口，到时按客户要求在前面再加。

字体兑现 D-011：Google Fonts 换成 Fontsource 的 `@fontsource/noto-sans-sc`、`@fontsource/noto-serif-sc`（5.3.1，OFL-1.1），只引入页面用到的字重（无衬线 400/500/600，衬线 600）。字体按 unicode-range 切成小分片，浏览器只下载页面实际用到的字；构建产物约 25 MB，几乎都是不会全部下载的字体分片。代价是样式表因 `@font-face` 声明变成约 210 KB（gzip 后），首次打开多下载这些，之后走缓存。页面不再请求任何外网地址，测试断言 index.html 不引用外部资源。
