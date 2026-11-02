import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({
    plugins: [react()],
    server: {
        proxy: {
            '/api': 'http://127.0.0.1:8080',
            '/internal': 'http://127.0.0.1:8080',
            '/swagger-ui': 'http://127.0.0.1:8080',
            '/v3/api-docs': 'http://127.0.0.1:8080',
        },
    },
    build: { outDir: 'dist' },
});
