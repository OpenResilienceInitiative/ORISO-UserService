/** Export the conditional fragments from the Frontend e-mail kit during sync. */
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const [frontend, output] = process.argv.slice(2);
if (!frontend || !output) {
	throw new Error('Usage: export-email-fragments.mts FRONTEND OUTPUT');
}

const kit = path.join(frontend, 'src/emails/kit');
const molecules = await import(pathToFileURL(path.join(kit, 'emailMolecules.ts')).href);
const tokens = await import(pathToFileURL(path.join(kit, 'emailTokens.ts')).href);
const fragments = {
	'cta.html': molecules.emailCallToAction(
		{ href: '{{actionUrl}}', label: '{{actionLabel}}', fallbackHint: '{{fallbackHint}}' },
		tokens.emailDefaultBrand
	),
	'cta.txt': '{{actionLabel}}:\n{{actionUrl}}',
	'assurance.html': molecules.emailAssurance('{{assurance}}'),
	'assurance.txt': '-'.repeat(64) + '\n{{assurance}}'
};

await mkdir(output, { recursive: true });
for (const [name, value] of Object.entries(fragments)) {
	await writeFile(path.join(output, name), value);
}
