/**
 * 请求客户端：对接 server-java（Spring Boot）。
 * - 鉴权：sa-token，token 通过请求头 `token` 携带（application.properties: sa-token.token-name=token）。
 * - 响应：server-java 返回裸 JSON（无 {code,data} 包装），因此响应拦截器直接返回 response.data。
 * - 错误：server-java 异常体形如 {"detail": "..."}（ApiException），据此提示。
 */
import type { RequestClientOptions } from '@vben/request';

import { useAppConfig } from '@vben/hooks';
import { preferences } from '@vben/preferences';
import { RequestClient } from '@vben/request';
import { useAccessStore } from '@vben/stores';

import { message } from 'ant-design-vue';

import { useAuthStore } from '#/store';

const { apiURL } = useAppConfig(import.meta.env, import.meta.env.PROD);

function createRequestClient(baseURL: string, options?: RequestClientOptions) {
  const client = new RequestClient({
    ...options,
    baseURL,
  });

  async function doReAuthenticate() {
    const accessStore = useAccessStore();
    const authStore = useAuthStore();
    accessStore.setAccessToken(null);
    if (
      preferences.app.loginExpiredMode === 'modal' &&
      accessStore.isAccessChecked
    ) {
      accessStore.setLoginExpired(true);
    } else {
      await authStore.logout();
    }
  }

  // 请求头：携带 sa-token（token 名与 server-java 配置一致）
  client.addRequestInterceptor({
    fulfilled: async (config) => {
      const accessStore = useAccessStore();
      if (accessStore.accessToken) {
        config.headers.token = accessStore.accessToken;
      }
      config.headers['Accept-Language'] = preferences.app.locale;
      return config;
    },
  });

  // 响应：server-java 返回裸 JSON，直接取 body
  client.addResponseInterceptor({
    fulfilled: (response) => {
      const { config, data, status } = response;
      if (config.responseReturn === 'raw') {
        return response;
      }
      if (status >= 200 && status < 400) {
        return data;
      }
      return response;
    },
    rejected: async (error) => {
      const resp = error?.response;
      const status = resp?.status;
      // 401/403：登录过期或未认证 → 重新认证
      if (status === 401 || status === 403) {
        await doReAuthenticate();
        throw error;
      }
      // server-java 错误体：{ detail: "..." }
      const detail =
        resp?.data?.detail ?? resp?.data?.message ?? error?.message ?? '请求失败';
      message.error(String(detail));
      throw error;
    },
  });

  return client;
}

export const requestClient = createRequestClient(apiURL, {
  responseReturn: 'data',
});

export const baseRequestClient = new RequestClient({ baseURL: apiURL });
