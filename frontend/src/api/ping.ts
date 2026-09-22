import { request } from './request'

export interface PingData {
  message: string
  app: string
  time: string
  javaVersion: string
  jvm: string
  fileEncoding: string
  mdThreadId: string
  chineseTest: string
}

/** 连通性检查 */
export function ping() {
  return request<PingData>({
    url: '/ping',
    method: 'get',
  })
}
