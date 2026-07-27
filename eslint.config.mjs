import js from '@eslint/js';
import boundaries from 'eslint-plugin-boundaries';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import tseslint from 'typescript-eslint';

const tsconfigRootDir = path.dirname(fileURLToPath(import.meta.url));

export default tseslint.config(
  { ignores: ['**/dist/**', '**/coverage/**', 'packages/api-client/src/generated/**'] },
  js.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    extends: [...tseslint.configs.strictTypeChecked],
    languageOptions: { parserOptions: { projectService: true, tsconfigRootDir } }
  },
  {
    files: ['**/*.{js,mjs,cjs}'],
    extends: [tseslint.configs.disableTypeChecked]
  },
  {
    plugins: { boundaries },
    settings: {
      'boundaries/elements': [
        { type: 'app', pattern: 'apps/web/src/app/*' },
        { type: 'route', pattern: 'apps/web/src/routes/*' },
        { type: 'module', pattern: 'apps/web/src/modules/*' },
        { type: 'shared', pattern: 'apps/web/src/shared/*' },
        { type: 'package', pattern: 'packages/*/src/*' }
      ]
    },
    rules: {
      'boundaries/element-types': ['error', {
        default: 'disallow',
        rules: [
          { from: ['app', 'route'], allow: ['module', 'shared', 'package', 'app', 'route'] },
          { from: ['module'], allow: ['shared', 'package', 'module'] },
          { from: ['shared'], allow: ['shared', 'package'] },
          { from: ['package'], allow: ['package'] }
        ]
      }]
    }
  }
);
