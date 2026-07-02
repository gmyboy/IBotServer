<script lang="ts" setup>
import type { VbenFormSchema } from '@vben/common-ui';

import { computed } from 'vue';

import { AuthenticationLogin, z } from '@vben/common-ui';

import { useAuthStore } from '#/store';

defineOptions({ name: 'Login' });

const authStore = useAuthStore();

/**
 * server-java 管理后台仅校验 config.yaml 的 server.admin_token。
 * 用户名对服务端无意义（这里固定 admin），密码即 admin_token（未配置时可任意填写）。
 */
const formSchema = computed((): VbenFormSchema[] => {
  return [
    {
      component: 'VbenInput',
      componentProps: {
        placeholder: '管理员账号（默认 admin）',
      },
      defaultValue: 'admin',
      fieldName: 'username',
      label: '账号',
      rules: z.string().min(1, { message: '请输入账号' }),
    },
    {
      component: 'VbenInputPassword',
      componentProps: {
        placeholder: '管理令牌 admin_token（服务端未配置时可任意填写）',
      },
      fieldName: 'password',
      label: '管理令牌',
      rules: z.string().default(''),
    },
  ];
});
</script>

<template>
  <AuthenticationLogin
    :form-schema="formSchema"
    :loading="authStore.loginLoading"
    :show-code-login="false"
    :show-forget-password="false"
    :show-qrcode-login="false"
    :show-register="false"
    :show-remember-me="false"
    :show-third-party-login="false"
    @submit="authStore.authLogin"
  />
</template>
