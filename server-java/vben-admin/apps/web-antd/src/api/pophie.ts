/**
 * Pophie server-java 管理接口封装。
 * - Admin 接口：/api/admin/**（需 sa-token）
 * - 业务查询接口：/api/memories、/api/conversations（按 user 维度查询，用于查看）
 */
import { requestClient } from '#/api/request';

export namespace PophieApi {
  export interface RobotRow {
    robot_id: string;
    display_name?: null | string;
    created_at?: null | string;
    last_seen_at?: null | string;
    memories_count?: number;
    conversations_count?: number;
    reminders_pending?: number;
  }

  export interface RobotDetail extends RobotRow {
    proactive_log_count?: number;
    session_count?: number;
    layer_counts?: Record<string, number>;
  }

  export interface UserRow {
    user_id: string;
    display_name?: null | string;
    nickname?: null | string;
    gender?: null | string;
    avatar_url?: null | string;
    voice_enrolled?: boolean;
    created_at?: null | string;
    last_seen_at?: null | string;
    devices_count?: number;
    robot_ids?: string[];
  }

  export interface ServiceInfo {
    pid: number;
    host: string;
    port: number;
    speech_config_enabled: boolean;
    speech_runtime: boolean;
  }

  export interface ConfigResult {
    config: Record<string, any>;
    schema?: Record<string, any>;
    tts_voices?: any[];
    config_path?: string;
    restart_required_for?: string[];
  }

  export type WipeScope = 'all' | 'conversations' | 'memories' | 'reminders';

  export interface PushResult {
    ok: boolean;
    targets: string[];
    text: string;
  }
}

/* ---------------- 机器人 ---------------- */

export async function listRobotsApi(q?: string) {
  return requestClient.get<{ items: PophieApi.RobotRow[] }>('/admin/robots', {
    params: q ? { q } : {},
  });
}

export async function getRobotDetailApi(robotId: string) {
  return requestClient.get<PophieApi.RobotDetail>(
    `/admin/robots/${encodeURIComponent(robotId)}`,
  );
}

export async function renameRobotApi(robotId: string, displayName: string) {
  return requestClient.patch(`/admin/robots/${encodeURIComponent(robotId)}`, {
    display_name: displayName,
  });
}

export async function deleteRobotApi(robotId: string) {
  return requestClient.delete(`/admin/robots/${encodeURIComponent(robotId)}`);
}

/* ---------------- 用户 ---------------- */

export async function listUsersApi(q?: string) {
  return requestClient.get<{ items: PophieApi.UserRow[] }>('/admin/users', {
    params: q ? { q } : {},
  });
}

export async function getUserDetailApi(userId: string) {
  return requestClient.get<Record<string, any>>(
    `/admin/users/${encodeURIComponent(userId)}`,
  );
}

/* ---------------- 记忆 ---------------- */

export async function listMemoriesApi(params: {
  layer?: string;
  sessionId?: string;
  userId?: string;
}) {
  return requestClient.get<{ items: any[]; user_id: string }>('/memories', {
    params,
  });
}

export async function deleteMemoryApi(memId: number, robotId?: string) {
  return requestClient.delete(`/admin/memories/${memId}`, {
    params: robotId ? { robotId } : {},
  });
}

/* ---------------- 对话 ---------------- */

export async function listConversationsApi(params: {
  limit?: number;
  sessionId?: string;
  userId?: string;
}) {
  return requestClient.get<{ items: any[] }>('/conversations', { params });
}

/* ---------------- 配置 ---------------- */

export async function getConfigApi() {
  return requestClient.get<PophieApi.ConfigResult>('/admin/config');
}

export async function updateConfigApi(config: Record<string, any>) {
  return requestClient.put<PophieApi.ConfigResult>('/admin/config', { config });
}

/* ---------------- 服务 ---------------- */

export async function getServiceApi() {
  return requestClient.get<PophieApi.ServiceInfo>('/admin/service');
}

export async function setSpeechApi(enabled: boolean) {
  return requestClient.put('/admin/service', { speech_enabled: enabled });
}

export async function restartServiceApi() {
  return requestClient.post('/admin/service/restart');
}

/* ---------------- 数据清除 ---------------- */

export async function wipeApi(scope: PophieApi.WipeScope, robotId: string) {
  return requestClient.post('/admin/wipe', { scope, robot_id: robotId });
}

/* ---------------- 消息推送 ---------------- */

export async function pushMessageApi(params: {
  user_id?: string;
  robot_id?: string;
  text: string;
  session_id?: string;
}) {
  return requestClient.post<PophieApi.PushResult>('/admin/push', params);
}
