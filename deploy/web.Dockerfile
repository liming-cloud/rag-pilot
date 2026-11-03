# 前端镜像，在宿主机上构建（#138）：发布包里已经有构建好的 web/dist（字体随包，D-011；版本号在打包时写进去），
# 这里只放进 Nginx 镜像，并把 /api 转发到 app（D-029）。基础镜像由 compose.yml 按摘要传入。
ARG NGINX_IMAGE
FROM ${NGINX_IMAGE}
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY web/dist /usr/share/nginx/html
EXPOSE 80
