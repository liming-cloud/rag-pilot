/** 运行的版本（4 位：主.次.修订.构建号，D-048），构建镜像时由 git tag 写入；开发时为 dev。 */
export const APP_VERSION: string = (import.meta.env.VITE_APP_VERSION as string | undefined) || "dev";
