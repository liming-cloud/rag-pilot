# 构建阶段：Node 24（见 DECISIONS.md D-012）构建前端，字体随包打进 dist（D-011）。
FROM node:24-alpine AS builder
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend ./
RUN npm run build

# 运行阶段：Nginx 提供静态文件，并把 /api 转发到 app（见 D-029）。
FROM nginx:1.28-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /web/dist /usr/share/nginx/html
EXPOSE 80
