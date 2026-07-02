import { requestClient } from '#/api/request';

export namespace AuthApi {
  /** 登录接口参数（server-java 仅校验 admin_token，用户名忽略，密码即 token） */
  export interface LoginParams {
    password?: string;
    username?: string;
  }

  /** server-java 登录响应 */
  export interface AdminLoginResult {
    ok: boolean;
    token: string;
  }

  export interface LoginResult {
    accessToken: string;
  }
}

/**
 * 管理员登录：POST /api/admin/login。
 * server-java 校验 config.yaml 的 server.admin_token（为空则任意放行），返回 sa-token。
 * 这里把登录表单的 password 作为 admin_token 传入。
 */
export async function loginApi(
  data: AuthApi.LoginParams,
): Promise<AuthApi.LoginResult> {
  const res = await requestClient.post<AuthApi.AdminLoginResult>(
    '/admin/login',
    { token: data.password ?? '' },
  );
  return { accessToken: res?.token ?? '' };
}

/**
 * 退出登录：server-java 无退出接口，前端本地清理即可。
 */
export async function logoutApi() {
  return Promise.resolve({ ok: true });
}

/**
 * 获取用户权限码：server-java 无权限码体系，返回空。
 */
export async function getAccessCodesApi(): Promise<string[]> {
  return Promise.resolve([]);
}

/**
 * 刷新 token：server-java 无刷新机制（enableRefreshToken=false），保留空实现避免引用报错。
 */
export async function refreshTokenApi() {
  return Promise.resolve({ data: '', status: 0 });
}
