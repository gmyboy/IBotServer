import type { UserInfo } from '@vben/types';

/**
 * 获取用户信息：server-java 管理后台无用户信息接口，返回固定的管理员信息。
 */
export async function getUserInfoApi(): Promise<UserInfo> {
  return Promise.resolve({
    avatar: '',
    desc: 'Pophie 管理员',
    homePath: '/robots',
    realName: '管理员',
    roles: ['admin'],
    token: '',
    userId: 'admin',
    username: 'admin',
  } as unknown as UserInfo);
}
