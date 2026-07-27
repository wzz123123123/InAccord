import { defineConfig } from 'orval';

const input = '../../contracts/openapi/accord-control-api.yaml';

export default defineConfig({
  accordFetch: {
    input,
    output: {
      target: './src/generated/endpoints.ts',
      schemas: './src/generated/model',
      client: 'fetch',
      mode: 'single',
      clean: true,
      prettier: true,
      override: {
        mutator: { path: './src/runtime.ts', name: 'apiFetch' },
        fetch: { includeHttpResponseReturnType: true }
      }
    }
  },
  accordSchemas: {
    input,
    output: {
      target: './src/generated/schemas.ts',
      client: 'zod',
      mode: 'single',
      clean: false,
      prettier: true
    }
  }
});
