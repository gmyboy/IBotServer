import { defineConfig } from '@vben/vite-config';

export default defineConfig(async () => {
  return {
    application: {},
    vite: {
      server: {
        proxy: {
          // 代理到 server-java（Spring Boot），保留 /api 前缀。
          '/api': {
            changeOrigin: true,
            target: 'http://localhost:8000',
            ws: true,
          },
        },
      },
    },
  };
});
