// Node 24+: node scripts/contracts/sync-pseudonym-vocabulary.mjs <Frontend checkout> [--check]
// Compare both the curated tables and handles produced by the ACTUAL Frontend function.
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { stripTypeScriptTypes } from 'node:module';
import { createHash } from 'node:crypto';
const root = process.argv[2];
if (!root) throw new Error('Supply the reviewed Frontend checkout');
const source = fs.readFileSync(path.join(root, 'src/utils/anonName/data.ts'), 'utf8');
const marker = 'export const LANGUAGE_DATA: Record<string, NickLang> = ';
if (!source.includes(marker)) throw new Error('Frontend dictionary export shape changed');
const literal = source.slice(source.indexOf(marker) + marker.length).trim().replace(/;$/, '');
const data = vm.runInNewContext(`(${literal})`, Object.create(null), {timeout: 1000});
const languages = Object.fromEntries(Object.entries(data).map(([locale, value]) => [locale, {
  groups: value.groups.map(group => ({adjectives: group.adjectives, animals: group.animals.map(animal => animal.label)})),
  names: value.names
}]));
const usernamePath = 'src/components/registration/accountData/registrationUsername.ts';
const usernameSource = fs.readFileSync(path.join(root, usernamePath), 'utf8');
const validation = fs.readFileSync(path.join(root, 'src/components/registration/registrationDataValidation.ts'), 'utf8');
const max = Number(validation.match(/USERNAME_MAX_LENGTH\s*=\s*(\d+)/)?.[1]);
if (!max) throw new Error('Frontend username length contract changed');
const runnable = stripTypeScriptTypes(usernameSource.replace(/^import[^\n]*\n/gm, '')).replace(/export const /g, 'const ');
const convert = vm.runInNewContext(runnable + '\ntoRegistrationUsername;', {
  USERNAME_MAX_LENGTH: max,
  Math: Object.assign(Object.create(Math), {random: () => 0})
}, {timeout: 1000});
const bases = new Set();
for (const language of Object.values(data)) for (const group of language.groups)
  for (const animal of group.animals) for (const name of language.names) {
    const generated = convert({animalLabel: animal.label, name,
      displayName: `${group.adjectives[0]} ${animal.label} ${name}`});
    if (!generated.endsWith('_1000')) throw new Error('Frontend suffix contract changed');
    bases.add(generated.slice(0, -5));
  }
const artifacts = {
  'src/main/resources/identity/pseudonym-vocabulary.json': {
    source: 'ORISO-Frontend/src/utils/anonName/data.ts', languages
  },
  'src/test/resources/identity/frontend-generated-handles.json': {
    source: `ORISO-Frontend/${usernamePath}`,
    formatVersion: 1,
    count: bases.size,
    sha256: createHash('sha256').update([...bases].sort().join('\n') + '\n').digest('hex')
  }
};
for (const [target, value] of Object.entries(artifacts)) {
  const result = JSON.stringify(value, null, 2) + '\n';
  if (process.argv.includes('--check')) {
    if (fs.readFileSync(target, 'utf8') !== result) throw new Error(`Frontend parity mismatch: ${target}`);
  } else {
    fs.mkdirSync(path.dirname(target), {recursive: true});
    fs.writeFileSync(target, result);
  }
}
process.stdout.write(`Pseudonym parity: ${Object.keys(languages).length} dictionaries, ${bases.size} actual Frontend handle bases.\n`);
