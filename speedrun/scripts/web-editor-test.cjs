// Unit tests for the browser editor state. Run with: node --test speedrun/scripts/web-editor-test.cjs
const test = require('node:test');
const assert = require('node:assert/strict');
const { EditorState, equal } = require('../src/main/resources/web-config/editor-state.js');

function state() {
  const editor = new EditorState();
  const values = { 'settings.language': 'en', 'rewards.enabled': false,
    rewards: { enabled: false, 'on-task-complete': ['say done'] },
    progression: { settings: { mode: 'ALL' }, first: { tasks: { IRON_INGOT: { amount: 3 } } } },
    'progression.settings.mode': 'ALL' };
  editor.accept({ values, savedValues: values, defaults: values, revision: 'first' });
  return editor;
}

test('Saving clears the draft and keeps the change pending until it is applied', () => {
  const editor = state();
  editor.set('settings.language', 'de');
  const saved = editor.desired();
  editor.accept({ values: editor.active, savedValues: saved, revision: 'second' });
  assert.deepEqual(editor.patch(), {});
  assert.equal(editor.pending()['settings.language'], 'de');
  assert.equal(editor.isPending('settings.language'), true);
  assert.equal(editor.value('settings.language'), 'de');
});

test('Applying without saving keeps the draft for a later save', () => {
  const editor = state();
  editor.set('settings.language', 'fr');
  const desired = editor.desired();
  editor.accept({ values: desired, savedValues: editor.saved, revision: 'third' }, { keepChanges: true });
  assert.deepEqual(editor.patch(), { 'settings.language': 'fr' });
  assert.deepEqual(editor.pending(), {});
});

test('Reloading with kept edits rebases the draft on the new saved file', () => {
  const editor = state();
  editor.set('settings.language', 'pl');
  const external = { ...editor.saved, 'rewards.enabled': true, rewards: { ...editor.saved.rewards, enabled: true } };
  editor.accept({ values: external, savedValues: external, revision: 'fourth' }, { keepChanges: true });
  assert.equal(editor.value('settings.language'), 'pl');
  assert.equal(editor.value('rewards.enabled'), true);
  assert.equal(editor.revision, 'fourth');
});

test('Nested scalar edits survive later structured edits without overlapping patches', () => {
  const editor = state();
  editor.set('rewards.enabled', true);
  const rewards = editor.value('rewards');
  rewards['on-task-complete'].push('say next');
  editor.set('rewards', rewards);
  assert.deepEqual(editor.patch(), { rewards: { enabled: true, 'on-task-complete': ['say done', 'say next'] } });
  editor.set('rewards.enabled', false);
  assert.equal(editor.patch().rewards.enabled, false);
  assert.equal(Object.hasOwn(editor.patch(), 'rewards.enabled'), false);
  assert.equal(editor.hasChange('rewards'), true);
});

test('Reverting a change removes it and source snapshots remain isolated', () => {
  const editor = state();
  const draft = editor.value('progression');
  draft.first.tasks.IRON_INGOT.amount = 10;
  assert.equal(editor.saved.progression.first.tasks.IRON_INGOT.amount, 3);
  editor.set('settings.language', 'uk');
  editor.set('settings.language', 'en');
  assert.deepEqual(editor.patch(), {});
  editor.set('settings.language', 'es');
  editor.revert('settings.language');
  assert.deepEqual(editor.patch(), {});
});

test('Semantic equality ignores object order and preserves list order', () => {
  assert.equal(equal({ a: 1, b: 2 }, { b: 2, a: 1 }), true);
  assert.equal(equal([1, 2], [2, 1]), false);
});
