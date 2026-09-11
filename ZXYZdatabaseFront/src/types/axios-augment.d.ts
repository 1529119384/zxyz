/**
 * axios 配置项模块增强。
 *
 * 背景：本项目在 axios 的请求配置上使用了自定义键 `rawBlob`，用于要求响应拦截器
 * 直接返回原始 Blob、不套统一响应信封。该键由 `src/utils/createApiClient.js` 的
 * 响应拦截器消费（`response.config?.rawBlob`）。
 *
 * 由于 axios 自身的类型不认识这个自定义键，此前在开启 // @ts-check 的文件里会报
 * TS2353（"rawBlob does not exist in type 'AxiosRequestConfig'"）。这里通过模块
 * 增强把自定义键补进类型，使得「自定义配置项」这件事在类型层面也是显式的。
 */
import 'axios'

declare module 'axios' {
  interface AxiosRequestConfig<D = any> {
    /** 要求拦截器返回原始 Blob，而不是解析后的统一响应信封。 */
    rawBlob?: boolean
  }
}
