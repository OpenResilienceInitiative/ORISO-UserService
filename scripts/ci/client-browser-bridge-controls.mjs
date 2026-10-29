import assert from 'node:assert/strict';
import { test } from 'node:test';
import { forwardedPath, loopbackOrigin } from './client-browser-bridge.mjs';

test('uses only the owned loopback origin, without userinfo or extra URL fields', () => {
    assert.equal(loopbackOrigin('http://127.0.0.1:18480'), 'http://127.0.0.1:18480');
    for (const value of ['https://127.0.0.1:18480', 'http://localhost:18480',
        'http://192.0.2.1:18480', 'http://user:password@127.0.0.1:18480',
        'http://127.0.0.1:18480/private', 'http://127.0.0.1:18480?secret=x']) {
        assert.throws(() => loopbackOrigin(value));
    }
});
test('preserves only the selected real methods, path and query through ingress prefix removal', () => {
    const id = '81000000-0000-4000-8000-000000000001';
    assert.equal(forwardedPath('PATCH', '/service/users/data'), '/users/data');
    assert.equal(forwardedPath('GET', '/service/users/data'), '/users/data');
    assert.equal(forwardedPath('DELETE', `/service/useradmin/consultants/${id}?forceDeleteSessions=false`),
        `/useradmin/consultants/${id}?forceDeleteSessions=false`);
    assert.equal(forwardedPath('DELETE', `/service/useradmin/agencyadmins/${id}`), `/useradmin/agencyadmins/${id}`);
    for (const [method, path] of [['POST', '/service/users/data'], ['GET', '/service/users/data?token=x'],
        ['DELETE', `/service/useradmin/agencyadmins/${id}?unknown=x`], ['GET', '/service/users/consultants'],
        ['POST', '/service/useradmin/self-assignments'], ['GET', '/service/../users/data']]) {
        assert.throws(() => forwardedPath(method, path));
    }
});
