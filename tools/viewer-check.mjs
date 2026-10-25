#!/usr/bin/env node
// KWAMSTER viewer decoder check (DESIGN.md §9.6, §12.1).
//
// Runs the share-link decoder embedded in docs/index.html (<script id="kw-decoder">) against the golden
// vectors that SharePayloadCodecTest writes to build/share-vectors/ (<name>.txt = link fragment,
// <name>.json = the Java reference decoder's output) and requires the two decoders to agree exactly. It
// also decodes the page's embedded "Load example" payload (<script id="kw-example">) and requires it to
// equal example.json. A missing KwDecoder export (validate, LIMITS, ...) or required vector is a failure,
// never a silently skipped section.
//
// The decoder runs in a node:vm context that exposes only DecompressionStream, Blob, atob, TextDecoder and
// Uint8Array, so it cannot lean on anything a browser page would not have. Needs Node 21.2+ (for
// DecompressionStream('deflate-raw')).
//
// It also checks that the two decoders reject the same malformed bytes (build/share-vectors/rejected/*.txt,
// which SharePayloadCodecTest asserts the Java decoder rejects), that every golden vector passes the viewer's
// KwDecoder.validate, and that crafted links built here (huge windows, event counts, times, inflated size)
// are refused quickly by decode or validate instead of freezing the page (the viewer DoS guard).
//
// Usage (from the project root, after ./gradlew test):  node tools/viewer-check.mjs [--vectors <dir>] [--write-example]
//   --vectors <dir>   read the golden vectors from <dir> instead of build/share-vectors
//   --write-example   first copy <vectors>/example.txt into docs/index.html's <script id="kw-example">
//                     (run it after a change to the payload layout or to SampleFights.example())
// Exit code: 0 when every vector matches, 1 otherwise.

import { readFileSync, readdirSync, existsSync, writeFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateRawSync } from 'node:zlib';
import vm from 'node:vm';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const pagePath = join(root, 'docs', 'index.html');
const argv = process.argv.slice(2);
const vectorsArg = argv.indexOf('--vectors');
const vectorDir = vectorsArg >= 0 && argv[vectorsArg + 1] ? resolve(argv[vectorsArg + 1]) : join(root, 'build', 'share-vectors');
const writeExample = argv.includes('--write-example');

let failures = 0;
const fail = (msg) => {
	failures++;
	console.log(`FAIL  ${msg}`);
};

function scriptById(html, id) {
	const re = new RegExp(`<script\\b[^>]*\\bid=["']${id}["'][^>]*>([\\s\\S]*?)</script>`, 'i');
	const m = html.match(re);
	if (!m) throw new Error(`docs/index.html has no <script id="${id}">`);
	return m[1];
}

/** Deep equality for plain JSON-like values; returns a path describing the first difference, or null. */
function diff(a, b, path = '$') {
	if (a === b) return null;
	if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') {
		if (typeof a === 'number' && typeof b === 'number' && Number.isNaN(a) && Number.isNaN(b)) return null;
		return `${path}: ${JSON.stringify(a)} != ${JSON.stringify(b)}`;
	}
	if (Array.isArray(a) !== Array.isArray(b)) return `${path}: array vs object`;
	if (Array.isArray(a)) {
		if (a.length !== b.length) return `${path}: length ${a.length} != ${b.length}`;
		for (let i = 0; i < a.length; i++) {
			const d = diff(a[i], b[i], `${path}[${i}]`);
			if (d) return d;
		}
		return null;
	}
	const ka = Object.keys(a).sort();
	const kb = Object.keys(b).sort();
	if (ka.join(',') !== kb.join(',')) return `${path}: keys [${ka}] != [${kb}]`;
	for (const k of ka) {
		const d = diff(a[k], b[k], `${path}.${k}`);
		if (d) return d;
	}
	return null;
}

/** Round-trips a value through JSON inside this realm (the vm result's objects come from another realm). */
const plain = (v) => JSON.parse(JSON.stringify(v));

/** A minimal payload writer for the crafted links (§9.2 primitives; BigInt so any LEB128 value can be written). */
class Writer {
	constructor() { this.b = []; }
	u8(v) { this.b.push(v & 0xff); return this; }
	uleb(v) {
		let x = BigInt(v);
		while (x > 0x7fn) { this.b.push(Number((x & 0x7fn) | 0x80n)); x >>= 7n; }
		this.b.push(Number(x));
		return this;
	}
	zig(v) { const n = BigInt(v); return this.uleb(n >= 0n ? n * 2n : -n * 2n - 1n); }
	str(s) { const e = new TextEncoder().encode(s); this.uleb(e.length); for (const x of e) this.b.push(x); return this; }
	bytes() { return Uint8Array.from(this.b); }
}

/** A v1 header, a zero summary and E1/E2; `h` overrides the defaults. The caller appends the events. */
function craft(h = {}) {
	const o = {
		flags: 0, opponents: ['b'], duration: 1000, three: 295, jumpMin: 0, jumpMax: 80, jumpAttempt: 200,
		combo: 2000, adv: 3000, total: 0, count: 0, ...h
	};
	const multi = o.opponents.length > 1;
	const w = new Writer().u8(1).u8(o.flags | (multi ? 16 : 0)).str('a').str(o.opponents[0]);
	if (multi) {
		w.u8(o.opponents.length - 1);
		for (const n of o.opponents.slice(1)) w.str(n);
	}
	w.uleb(1792510212).uleb(o.duration).u8(0).uleb(0).uleb(o.three)
		.uleb(o.jumpMin).uleb(o.jumpMax).uleb(o.jumpAttempt).uleb(o.combo).uleb(o.adv);
	for (let i = 0; i < 42; i++) {
		if (i === 37) w.zig(0);
		else w.uleb(0);
	}
	return w.uleb(o.total).uleb(o.count);
}
const fragmentOf = (bytes) => deflateRawSync(bytes).toString('base64url');
const DEATH_YOU = 4 | (1 << 3); // kind DEATH, arg bit 0 = you died: no opponent byte

async function main() {
	if (typeof DecompressionStream !== 'function') {
		console.log('FAIL  this Node has no DecompressionStream (needs Node 21.2+)');
		process.exit(1);
	}
	let html = readFileSync(pagePath, 'utf8');
	if (writeExample) {
		const fresh = readFileSync(join(vectorDir, 'example.txt'), 'utf8').trim();
		if (!/^[A-Za-z0-9_-]+$/.test(fresh)) throw new Error('example.txt is not a base64url fragment');
		const re = /(<script\b[^>]*\bid=["']kw-example["'][^>]*>)[\s\S]*?(<\/script>)/i;
		if (!re.test(html)) throw new Error('docs/index.html has no <script id="kw-example">');
		html = html.replace(re, (_, open, close) => open + fresh + close);
		writeFileSync(pagePath, html, 'utf8');
		console.log('wrote example.txt into docs/index.html <script id="kw-example">');
	}
	const decoderSource = scriptById(html, 'kw-decoder');
	const exampleFragment = scriptById(html, 'kw-example').trim();

	const sandbox = { DecompressionStream, Blob, atob, TextDecoder, Uint8Array };
	const context = vm.createContext(sandbox, { name: 'kw-decoder' });
	vm.runInContext(decoderSource, context, { filename: 'docs/index.html#kw-decoder' });
	const KwDecoder = context.KwDecoder;
	if (!KwDecoder || typeof KwDecoder.decodeFragment !== 'function') {
		console.log('FAIL  the decoder script did not define globalThis.KwDecoder');
		process.exit(1);
	}
	for (const fn of ['isSupported', 'b64urlToBytes', 'inflateRaw', 'decode', 'decodeFragment', 'validate']) {
		if (typeof KwDecoder[fn] !== 'function') fail(`KwDecoder.${fn} is missing`);
	}
	// LIMITS is part of the §9.6 contract, and the crafted-link checks below are built from it.
	const LIMIT_KEYS = ['MAX_EVENTS', 'MAX_TOTAL_EVENTS', 'MAX_DURATION_MS', 'MAX_JUMP_MS', 'MAX_WINDOW_MS',
		'MAX_THREE_BLOCK_CM', 'MAX_DIST_DM', 'MAX_NAME_CHARS', 'MAX_SERVER_CHARS'];
	if (!KwDecoder.LIMITS || typeof KwDecoder.LIMITS !== 'object') fail('KwDecoder.LIMITS is missing');
	else {
		for (const k of LIMIT_KEYS) {
			if (!Number.isFinite(KwDecoder.LIMITS[k]) || KwDecoder.LIMITS[k] <= 0) fail(`KwDecoder.LIMITS.${k} is missing`);
		}
	}
	if (!KwDecoder.isSupported()) fail('KwDecoder.isSupported() is false under Node');

	if (!existsSync(vectorDir)) {
		console.log(`FAIL  ${vectorDir} does not exist: run ./gradlew test first (SharePayloadCodecTest writes the vectors)`);
		process.exit(1);
	}
	const names = readdirSync(vectorDir).filter((f) => f.endsWith('.txt')).map((f) => f.slice(0, -4)).sort();
	if (names.length === 0) fail('no golden vectors found');
	for (const required of ['empty', 'example', 'thinned', 'reserved_kind', 'multibyte_name', 'bom_name', 'noreg', 'downsampled',
		'at_size_cap']) {
		if (!names.includes(required)) fail(`golden vector "${required}" is missing`);
	}

	const expectedByName = new Map();
	for (const name of names) {
		const jsonPath = join(vectorDir, `${name}.json`);
		if (!existsSync(jsonPath)) {
			fail(`${name}: no ${name}.json next to ${name}.txt`);
			continue;
		}
		const expected = JSON.parse(readFileSync(jsonPath, 'utf8'));
		expectedByName.set(name, expected);
		const fragment = readFileSync(join(vectorDir, `${name}.txt`), 'utf8').trim();
		try {
			const decoded = await KwDecoder.decodeFragment(fragment);
			const d = diff(plain(decoded), expected);
			const problem = typeof KwDecoder.validate === 'function' ? KwDecoder.validate(decoded) : null;
			if (d) fail(`${name}: ${d}`);
			else if (problem) fail(`${name}: a real link fails the viewer's validate: ${problem}`);
			else console.log(`ok    ${name} (${expected.events.length}/${expected.totalEvents} events${expected.truncated ? ', truncated' : ''})`);
		} catch (e) {
			fail(`${name}: decoder threw ${e && e.code ? e.code + ' ' : ''}${e && e.message ? e.message : e}`);
		}
	}

	// The page's embedded "Load example" payload must be the golden example (§9.6).
	const example = expectedByName.get('example');
	if (example) {
		try {
			const actual = plain(await KwDecoder.decodeFragment(exampleFragment));
			const d = diff(actual, example);
			if (d) fail(`embedded kw-example != example.json: ${d}`);
			else console.log('ok    embedded kw-example equals example.json');
		} catch (e) {
			fail(`embedded kw-example: decoder threw ${e && e.message ? e.message : e}`);
		}
	}

	// Malformed bytes the Java reference rejects must be rejected here too (decoders in lock-step).
	const rejectedDir = join(vectorDir, 'rejected');
	if (!existsSync(rejectedDir)) {
		fail(`${rejectedDir} does not exist: SharePayloadCodecTest writes the rejected vectors`);
	} else {
		const rejected = readdirSync(rejectedDir).filter((f) => f.endsWith('.txt')).sort();
		if (rejected.length === 0) fail('no rejected vectors found');
		for (const required of ['over_size_cap', 'event_time_overflow']) {
			if (!rejected.includes(`${required}.txt`)) fail(`rejected vector "${required}" is missing`);
		}
		for (const file of rejected) {
			const fragment = readFileSync(join(rejectedDir, file), 'utf8').trim();
			try {
				await KwDecoder.decodeFragment(fragment);
				fail(`rejected/${file}: the JS decoder accepted bytes the Java decoder rejects`);
			} catch (e) {
				if (!e || !e.code) fail(`rejected/${file}: threw without a DecodeError code: ${e && e.message ? e.message : e}`);
				else console.log(`ok    rejected/${file.slice(0, -4)} (${e.code})`);
			}
		}
	}

	// Crafted links (viewer DoS guard): each must be refused by decode or validate, and quickly.
	if (typeof KwDecoder.validate !== 'function' || !KwDecoder.LIMITS || typeof KwDecoder.LIMITS !== 'object') {
		fail('KwDecoder.validate or KwDecoder.LIMITS is missing: the crafted DoS checks were not run');
	} else {
		const L = KwDecoder.LIMITS;
		const deaths = (n, h = {}) => {
			const w = craft({ total: n, count: n, ...h });
			for (let i = 0; i < n; i++) w.uleb(0).u8(DEATH_YOU);
			return w.bytes();
		};
		const crafted = [
			['huge jump window, one jump', craft({ jumpAttempt: 2147483647, total: 1, count: 1 }).uleb(10).u8(3).zig(60).bytes(), 'validate'],
			['huge combo window', craft({ combo: 2147483647 }).bytes(), 'validate'],
			['huge advantage window', craft({ adv: 2147483647 }).bytes(), 'validate'],
			['fight of 2^31 - 1 ms', craft({ duration: 2147483647 }).bytes(), 'validate'],
			['event a day after the start', craft({ total: 1, count: 1 }).uleb(L.MAX_DURATION_MS / 10 + 2).u8(DEATH_YOU).bytes(), 'validate'],
			['event times past 2^31 cs', (() => {
				const w = craft({ total: 20, count: 20 });
				for (let i = 0; i < 20; i++) w.uleb(34359738367n).u8(DEATH_YOU);
				return w.bytes();
			})(), 'decode'],
			[`${L.MAX_EVENTS + 1} events`, deaths(L.MAX_EVENTS + 1), 'validate'],
			['500000 events (1 MB inflated)', deaths(500000), 'decode'],
			['event total below the count', deaths(3, { total: 2 }), 'validate'],
			['opponent index past the table', craft({ opponents: ['b', 'c'], total: 1, count: 1 }).uleb(1).u8(4).u8(5).bytes(), 'validate'],
			['distance past 200 blocks', craft({ flags: 1, total: 1, count: 1 }).uleb(1).u8(2).u8(100).zig(0).zig(0).u8(0).uleb(2001).bytes(), 'validate'],
			['3-block threshold of 20 blocks', craft({ three: 2000 }).bytes(), 'validate'],
			['opponent name of 300 bytes', craft({ opponents: ['x'.repeat(300)] }).bytes(), 'validate']
		];
		for (const [label, bytes, expect] of crafted) {
			const started = performance.now();
			let stage = 'accepted';
			try {
				const d = await KwDecoder.decodeFragment(fragmentOf(bytes));
				if (KwDecoder.validate(d)) stage = 'validate';
			} catch (e) {
				stage = 'decode';
			}
			const ms = performance.now() - started;
			if (stage === 'accepted') fail(`crafted "${label}" would be drawn`);
			else if (stage !== expect) fail(`crafted "${label}" was refused by ${stage}, expected ${expect}`);
			else if (ms > 2000) fail(`crafted "${label}" took ${ms.toFixed(0)} ms to refuse`);
			else console.log(`ok    crafted "${label}" refused by ${stage} (${ms.toFixed(0)} ms)`);
		}
		// A link exactly at the limits is still drawn.
		try {
			const d = await KwDecoder.decodeFragment(fragmentOf(deaths(L.MAX_EVENTS, { duration: L.MAX_DURATION_MS, jumpAttempt: L.MAX_JUMP_MS })));
			const problem = KwDecoder.validate(d);
			if (problem) fail(`a link exactly at the limits is refused: ${problem}`);
			else console.log(`ok    a link exactly at the limits is accepted (${d.events.length} events)`);
		} catch (e) {
			fail(`a link exactly at the limits threw ${e && e.message ? e.message : e}`);
		}
	}

	// Error paths a link in the wild can hit.
	try {
		KwDecoder.decode(new Uint8Array([2, 0]));
		fail('a version-2 payload was accepted');
	} catch (e) {
		if (e.code !== 'VERSION') fail(`a version-2 payload threw ${e.code}, expected VERSION`);
		else console.log('ok    unknown version is rejected with VERSION');
	}
	try {
		await KwDecoder.decodeFragment('not base64 !');
		fail('a corrupt fragment was accepted');
	} catch (e) {
		if (e.code !== 'BASE64') fail(`a corrupt fragment threw ${e.code}, expected BASE64`);
		else console.log('ok    corrupt fragment is rejected with BASE64');
	}

	console.log(failures === 0 ? `viewer-check OK (${names.length} vectors)` : `viewer-check FAILED (${failures} problem(s))`);
	process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
	console.log(`FAIL  ${e && e.stack ? e.stack : e}`);
	process.exit(1);
});
