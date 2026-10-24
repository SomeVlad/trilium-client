#!/usr/bin/env python3
"""Verify content/type round trips using only two newly created disposable notes."""
import json
import pathlib
import secrets
import urllib.error
import urllib.request

properties = {}
for line in (pathlib.Path(__file__).resolve().parents[1] / 'private.properties').read_text().splitlines():
    if '=' in line:
        key, value = line.split('=', 1)
        properties[key] = value
base = properties['trilium.baseUrl'].rstrip('/') + '/etapi/'
token = properties['trilium.token'].removeprefix('Bearer ')


def request(method, path, payload=None, content_type='application/json'):
    data = None if payload is None else (json.dumps(payload).encode() if isinstance(payload, dict) else payload.encode())
    req = urllib.request.Request(base + path, data=data, method=method, headers={
        'Authorization': 'Bearer ' + token, 'Content-Type': content_type,
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, ''
    except Exception as error:
        raise RuntimeError('Acceptance request failed: ' + type(error).__name__) from None


cases = [
    ('html', 'text', 'text/html',
     '<h2>Проверка клиента</h2><table><tbody><tr><td>Один</td><td>Два</td></tr></tbody></table>'
     '<ul class="todo-list"><li><label class="todo-list__label"><input type="checkbox" checked="checked">'
     '<span class="todo-list__label__description">Задача</span></label></li></ul>'
     '<blockquote><p>Цитата</p></blockquote><p><a href="https://example.org/">Ссылка</a></p>'),
    ('markdown', 'code', 'text/x-markdown',
     '# Проверка Markdown\n\n- [x] Задача\n- [ ] Следующая\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n```python\nprint(42)\n```\n'),
]

results = []
for label, note_type, mime, content in cases:
    note_id = secrets.token_hex(16)
    assert request('GET', 'notes/' + note_id)[0] == 404, 'Unexpected existing note id'
    attempted = False
    deleted = False
    try:
        attempted = True
        status, body = request('POST', 'create-note', {
            'parentNoteId': 'root', 'noteId': note_id,
            'title': 'Временная проверка клиента 0.2', 'type': note_type, 'mime': mime, 'content': content,
        })
        assert status == 201, 'Create failed'
        assert json.loads(body)['note']['noteId'] == note_id, 'Unexpected created note'
        assert request('GET', 'notes/' + note_id + '/content') == (200, content), 'Initial content differs'
        revised = content + ('<p>Правка</p>' if note_type == 'text' else '\nПравка\n')
        status, _ = request('PUT', 'notes/' + note_id + '/content', revised, 'text/plain; charset=utf-8')
        assert status == 204, 'Content update failed'
        assert request('GET', 'notes/' + note_id + '/content') == (200, revised), 'Updated content differs'
        status, metadata = request('GET', 'notes/' + note_id)
        assert status == 200, 'Updated metadata unavailable'
        metadata = json.loads(metadata)
        assert metadata['type'] == note_type and metadata['mime'] == mime, 'Type or MIME changed'
        results.append({'format': label, 'create': 201, 'update': 204, 'content_exact': True, 'type_and_mime_preserved': True})
    finally:
        if attempted:
            status, _ = request('DELETE', 'notes/' + note_id)
            deleted = status in (204, 404)
            if results and results[-1]['format'] == label:
                results[-1]['temporary_note_deleted'] = deleted
            assert deleted, 'Temporary note cleanup failed'
print(json.dumps({'ok': True, 'cases': results}, ensure_ascii=False))
