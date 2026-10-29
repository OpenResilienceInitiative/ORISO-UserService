// Test-only browser transport; acceptance requires the actual User/native fixture.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { lstat, mkdtemp, readFile, readlink, rm } from 'node:fs/promises';
import { request as httpRequest } from 'node:http';
import { createRequire } from 'node:module';
import { join, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';

const digest = (value) => createHash('sha256').update(value).digest('hex');
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export function loopbackOrigin(value) {
    const url = new URL(value);
    assert.equal(url.protocol, 'http:');
    assert.equal(url.hostname, '127.0.0.1');
    assert.ok(Number(url.port) >= 1024 && Number(url.port) <= 65535);
    assert.equal(url.pathname, '/');
    assert.equal(url.username + url.password + url.search + url.hash, '');
    return url.origin;
}
export function forwardedPath(method, value) {
    const url = new URL(value, 'http://127.0.0.1');
    const data = url.pathname === '/service/users/data' && ['GET', 'PATCH'].includes(method) && !url.search;
    const removal = method === 'DELETE' &&
        /^\/service\/useradmin\/(consultants|agencyadmins)\/[0-9a-f-]{36}$/.test(url.pathname) &&
        (!url.search || /^\?forceDeleteSessions=(true|false)$/.test(url.search));
    assert.ok(data || removal, 'Unexpected fixture request');
    return url.pathname.slice('/service'.length) + url.search;
}
const git = (root, ...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
async function source(root, ref) {
    assert.match(ref, /^[0-9a-f]{40}$/);
    assert.equal(git(root, 'rev-parse', 'HEAD'), ref);
    assert.equal(git(root, 'status', '--porcelain', '--untracked-files=no'), '');
    const paths = execFileSync('git', ['ls-files', '-z'], { cwd: root, encoding: 'utf8' }).split('\0').filter(Boolean);
    const hashes = {};
    for (const name of paths) {
        const path = join(root, name);
        const info = await lstat(path);
        assert.ok(info.isFile() || info.isSymbolicLink());
        hashes[name] = digest(info.isSymbolicLink() ? 'symlink\0' + await readlink(path) : await readFile(path));
    }
    return { ref, hashes };
}
const virtualId = 'virtual:scenario-c-browser-bridge';
function browserModule(kind) {
    const common = `
        const prepare = command => {
            if (!/^[0-9a-f-]{36}$/.test(command.operationId)) throw Error('INVALID_OPERATION_ID');
            const original = crypto.randomUUID;
            let attempt;
            try {
                crypto.randomUUID = () => command.operationId;
                attempt = actions.prepare(command.kind, command.target, command.payload);
            } finally { crypto.randomUUID = original; }
            if (attempt.intent.id !== command.operationId) throw Error('CHANGED_OPERATION_ID');
            return attempt;
        };
        window.scenarioBridge = { seed, async send(command) {
            const attempt = prepare(command);
            const before = snapshot();
            await actions.run(command.kind, command.target, command.payload,
                (frozen, intent) => send(command, frozen, intent));
            let verification;
            try { verification = await after(command, before); }
            catch { verification = { postconditions: false }; }
            return { acknowledged: true, sameIntent: attempt.intent.id === command.operationId, ...verification };
        }};
    `;
    if (kind === 'frontend') return `
        import { apiGetUserData } from '/src/api/apiGetUserData.ts';
        import { apiPatchUserData } from '/src/api/apiPatchUserData.ts';
        import { AccountOperationActions } from '/src/utils/accountOperationIntent.ts';
        import { accountOperationOwner } from '/src/utils/accountOperationOwner.ts';
        import { getValueFromCookie, setValueInCookie } from '/src/components/sessionCookie/accessSessionCookie.ts';
        const actions = new AccountOperationActions(() => accountOperationOwner(getValueFromCookie('keycloak')));
        const snapshot = () => ({ owner: accountOperationOwner(getValueFromCookie('keycloak')) });
        async function seed(tokens) {
            setValueInCookie('keycloak', tokens.access_token);
            if (tokens.refresh_token) setValueInCookie('refreshToken', tokens.refresh_token);
            await apiGetUserData();
        }
        function send(command, frozen, intent) {
            if (command.kind !== 'locale') throw Error('INVALID_FRONTEND_ACTION');
            return apiPatchUserData(frozen, intent);
        }
        async function after(command, before) {
            const data = await apiGetUserData();
            if (data.scopedAccountCommands !== true || snapshot().owner !== before.owner)
                throw Error('FRONTEND_METADATA_CHANGED');
            return { postconditions: true, metadataRead: true, sameHuman: true };
        }
        ${common}`;
    return `
        import { getUserData } from '/src/api/user/getUserData.ts';
        import { deleteCounselorData } from '/src/api/counselor/deleteCounselorData.ts';
        import { deleteAgencyAdminData } from '/src/api/admins/deleteAgencyAdminData.ts';
        import { setAuthTokensViaBff } from '/src/api/auth/authBffClient.ts';
        import { getSessionAccessToken, setSessionTokens } from '/src/api/auth/tokenSessionStore.ts';
        import { AccountOperationActions } from '/src/utils/accountOperationActions.ts';
        import { accountOperationOwner } from '/src/utils/accountOperationOwner.ts';
        const actions = new AccountOperationActions(() => accountOperationOwner(getSessionAccessToken()));
        const claims = token => JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
        const snapshot = () => ({ owner: accountOperationOwner(getSessionAccessToken()),
            token: getSessionAccessToken(), subject: claims(getSessionAccessToken()).sub });
        async function seed(tokens) {
            setSessionTokens(tokens.access_token, tokens.refresh_token);
            await setAuthTokensViaBff(tokens);
            await getUserData();
        }
        async function send(command, frozen, intent) {
            if (command.kind === 'consultant-delete')
                return deleteCounselorData(command.target, frozen.forceDeleteSessions, intent);
            if (command.kind === 'admin-delete') return deleteAgencyAdminData(command.target, intent);
            throw Error('INVALID_ADMIN_ACTION');
        }
        async function after(command, before) {
            if (command.kind !== 'consultant-delete' || command.target !== before.subject)
                return { postconditions: true };
            const current = snapshot();
            const roles = claims(current.token).realm_access.roles;
            if (current.owner !== before.owner || current.token === before.token || roles.includes('consultant') ||
                roles.includes('group-chat-consultant') ||
                !roles.includes('restricted-agency-admin') || !roles.includes('user-admin'))
                throw Error('OWN_ROLE_NOT_RENEWED');
            const data = await getUserData();
            if (data.userId !== before.subject || data.scopedAccountCommands !== true)
                throw Error('OWN_METADATA_CHANGED');
            return { postconditions: true, metadataRead: true, sameHuman: true, roleRenewed: true };
        }
        ${common}`;
}

export async function openBridge(config) {
    const backend = loopbackOrigin(config.backendOrigin);
    const keycloak = loopbackOrigin(config.keycloakOrigin);
    assert.match(config.realm, /^[a-zA-Z0-9_-]{1,128}$/);
    const before = {};
    for (const kind of ['admin', 'frontend']) {
        config[kind].root = resolve(config[kind].root);
        before[kind] = await source(config[kind].root, config[kind].ref);
    }
    const privateRoot = resolve(config.privateRoot);
    const privateInfo = await lstat(privateRoot);
    assert.ok(privateInfo.isDirectory() && !privateInfo.isSymbolicLink());
    assert.equal(privateInfo.mode & 0o777, 0o700);
    const scratch = await mkdtemp(join(privateRoot, 'scenario-c-browser-'));
    let browser;
    try {
        const adminRequire = createRequire(join(config.admin.root, 'package.json'));
        const playwright = await import(pathToFileURL(adminRequire.resolve('playwright')).href);
        browser = await (playwright.chromium ?? playwright.default.chromium).launch({ headless: true });
    } catch (error) {
        await rm(scratch, { recursive: true, force: true });
        throw error;
    }
    let server;
    let activeKind;
    const pages = new Map();
    const observations = [];
    let drop;
    let refreshes = 0;
    async function switchClient(kind) {
        assert.ok(['admin', 'frontend'].includes(kind));
        if (activeKind === kind) return;
        // The native journey runs SELF first, then Admin. Never reopen an old intent store.
        assert.ok(!activeKind || (activeKind === 'frontend' && kind === 'admin'));
        for (const { context } of pages.values()) await context.close();
        pages.clear();
        if (server) await server.close();
        activeKind = kind;
        const root = config[kind].root;
        const require = createRequire(join(root, 'package.json'));
        const viteModule = await import(pathToFileURL(require.resolve('vite')).href);
        const vite = viteModule.createServer ? viteModule : viteModule.default;
        const reactModule = await import(pathToFileURL(require.resolve('@vitejs/plugin-react')).href);
        const plugins = [(reactModule.default ?? reactModule)(), {
            name: 'scenario-c-private-browser-entry',
            resolveId(id) { if (id === virtualId) return '\0' + virtualId; },
            load(id) { if (id === '\0' + virtualId) return browserModule(kind); },
            configureServer(viteServer) {
                viteServer.middlewares.use(async (req, res, next) => {
                    if (req.url?.startsWith('/service/')) {
                        try {
                            const path = forwardedPath(req.method, req.url);
                            const chunks = [];
                            for await (const chunk of req) {
                                chunks.push(chunk);
                                assert.ok(Buffer.concat(chunks).length <= 16384);
                            }
                            const body = Buffer.concat(chunks);
                            const upstream = httpRequest(backend + path, {
                                method: req.method,
                                headers: { ...req.headers, host: new URL(backend).host },
                                timeout: 10000,
                            }, async response => {
                              try {
                                const chunks = [];
                                for await (const chunk of response) {
                                    chunks.push(chunk);
                                    if (Buffer.concat(chunks).length > 65536) { response.destroy(); res.destroy(); return; }
                                }
                                const status = response.statusCode;
                                const observation = { method: req.method, path, status,
                                    operationId: req.headers['idempotency-key'] ?? null,
                                    bodySHA256: digest(body), dropped: false };
                                observations.push(observation);
                                if (drop && drop === observation.operationId && status >= 200 && status < 300) {
                                    drop = null; observation.dropped = true; res.destroy(); return;
                                }
                                res.writeHead(status, response.headers);
                                res.end(Buffer.concat(chunks));
                              } catch {
                                response.destroy();
                                res.destroy();
                              }
                            });
                            upstream.on('timeout', () => upstream.destroy());
                            upstream.on('error', () => res.destroy());
                            upstream.end(body);
                        } catch { res.statusCode = 502; res.end(); }
                        return;
                    }
                    if (req.url?.split('?')[0] === '/admin/auth/refresh-token') refreshes += 1;
                    if (req.url?.split('?')[0] === '/__scenario_c_browser') {
                        const origin = viteServer.resolvedUrls.local[0].replace(/\/$/, '');
                        const runtime = {};
                        for (const prefix of ['VITE', 'REACT_APP']) Object.assign(runtime, {
                            [prefix + '_API_URL']: origin, [prefix + '_APP_URL']: origin,
                            [prefix + '_MATRIX_URL']: origin, [prefix + '_USE_API_URL']: 'true',
                            [prefix + '_USE_HTTPS']: 'false', [prefix + '_KEYCLOAK_URL']: keycloak,
                            [prefix + '_KEYCLOAK_REALM']: config.realm,
                            [prefix + '_KEYCLOAK_CLIENT_ID']: 'protected-human-web',
                        });
                        const adminRuntime = { API_URL: origin, APP_URL: origin, MATRIX_URL: origin,
                            USER_SERVICE_ORIGIN: origin, AGENCY_SERVICE_ORIGIN: origin,
                            TENANT_SERVICE_ORIGIN: origin, CONSULTING_TYPE_SERVICE_ORIGIN: origin,
                            USE_API_URL: 'true', USE_HTTPS: 'false', KEYCLOAK_URL: keycloak,
                            KEYCLOAK_REALM: config.realm, KEYCLOAK_CLIENT_ID: 'protected-human-web' };
                        res.setHeader('Content-Type', 'text/html');
                        const html = '<!doctype html><script>window.__ORISO_RUNTIME_CONFIG__=' + JSON.stringify(runtime) +
                            ';window.__APP_CONFIG__=' + JSON.stringify(adminRuntime) +
                            '</script><script type="module" src="/@id/__x00__' + virtualId + '"></script>';
                        res.end(await viteServer.transformIndexHtml('/__scenario_c_browser', html));
                        return;
                    }
                    next();
                });
            },
        }];
        if (kind === 'admin') {
            Object.assign(process.env, { VITE_USE_HTTPS: 'false', VITE_COOKIE_SECURE: 'false',
                VITE_KEYCLOAK_URL: keycloak, VITE_KEYCLOAK_REALM: config.realm,
                VITE_KEYCLOAK_CLIENT_ID: 'protected-human-web',
                VITE_HOSTNAMES_WITHOUT_COOKIE_DOMAIN: '127.0.0.1', VITE_AUTH_COOKIE_PATH: '/admin' });
            const { createAuthBffHandler, AUTH_BFF_ROUTE_PATTERN } = await import(
                pathToFileURL(join(root, 'scripts/auth-bff-handlers.mjs')).href);
            plugins.push({ name: 'actual-admin-bff', configureServer(viteServer) {
                const handler = createAuthBffHandler();
                viteServer.middlewares.use((req, res, next) => {
                    if (!AUTH_BFF_ROUTE_PATTERN.test(req.url?.split('?')[0] ?? '')) { next(); return; }
                    handler(req, res).catch(() => { res.statusCode = 500; res.end(); });
                });
            } });
        }
        server = await vite.createServer({ root, configFile: false, envDir: false,
            cacheDir: join(scratch, kind), logLevel: 'silent', plugins,
            optimizeDeps: { entries: ['src/api/fetchData.ts'] },
            define: { 'process.env': JSON.stringify({ NODE_ENV: 'test',
                REACT_APP_KEYCLOAK_REALM: config.realm }) },
            server: { host: '127.0.0.1', port: 0 },
        });
        await server.listen();
    }
    return {
        async seed(command) {
            assert.match(command.actor, /^[a-z-]{1,32}$/);
            assert.equal(typeof command.tokens.access_token, 'string');
            await switchClient(command.client);
            assert.ok(!pages.has(command.actor), 'Never overwrite an unresolved actor session');
            const context = await browser.newContext();
            const page = await context.newPage();
            pages.set(command.actor, { context, page });
            await page.goto(server.resolvedUrls.local[0] + '__scenario_c_browser', { timeout: 15000 });
            await page.waitForFunction(() => !!window.scenarioBridge, undefined, { timeout: 15000 });
            await page.evaluate(tokens => window.scenarioBridge.seed(tokens), command.tokens);
            return { seeded: true };
        },
        async send(command) {
            assert.equal(command.client, activeKind);
            assert.ok(uuid.test(command.operationId));
            const { page } = pages.get(command.actor);
            const start = observations.length;
            const refreshStart = refreshes;
            if (command.dropAfterSuccess) drop = command.operationId;
            let result;
            try { result = await page.evaluate(value => window.scenarioBridge.send(value), command); }
            catch { /* Only bounded statuses and observed transport evidence leave the private page. */ }
            const requests = observations.slice(start);
            return { ...result, ok: result?.acknowledged === true && result?.postconditions === true,
                requests, refreshes: refreshes - refreshStart,
                originalIntentOnly: requests.filter(r => r.method !== 'GET').every(r => r.operationId === command.operationId),
            };
        },
        async close() {
            const failures = [];
            const closeOwned = async operation => {
                try { await operation(); } catch (error) { failures.push(error); }
            };
            try {
                for (const { context } of pages.values()) await closeOwned(() => context.close());
                if (server) await closeOwned(() => server.close());
                await closeOwned(() => browser.close());
                assert.equal(failures.length, 0, 'Owned browser cleanup failed');
                const after = {};
                for (const kind of ['admin', 'frontend']) after[kind] = await source(config[kind].root, config[kind].ref);
                assert.deepEqual(after, before);
                return { sourceUnchanged: true, refs: { admin: before.admin.ref, frontend: before.frontend.ref } };
            } finally { await rm(scratch, { recursive: true, force: true }); }
        },
    };
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(new URL(import.meta.url).pathname)) {
    // stdin/stdout are private parent-child pipes; credentials never enter CLI args or evidence files.
    let bridge;
    try {
        for await (const line of createInterface({ input: process.stdin })) {
            assert.ok(line.length <= 131072);
            const command = JSON.parse(line);
            if (command.command === 'open') { assert.ok(!bridge); bridge = await openBridge(command.config); console.log('{"opened":true}'); }
            else if (command.command === 'seed') console.log(JSON.stringify(await bridge.seed(command)));
            else if (command.command === 'send') console.log(JSON.stringify(await bridge.send(command)));
            else if (command.command === 'close') { console.log(JSON.stringify(await bridge.close())); bridge = null; break; }
            else throw Error('INVALID_PRIVATE_COMMAND');
        }
    } catch { process.stderr.write('SCENARIO_BROWSER_BRIDGE_FAILED\n'); process.exitCode = 1; }
    finally { if (bridge) await bridge.close().catch(() => {}); }
}
