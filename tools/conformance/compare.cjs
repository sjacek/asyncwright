const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { pathToFileURL } = require('node:url');

// The corpus may resolve its local helper, but comparisons must never fetch remote data.
for (const protocol of ['node:http', 'node:https']) {
  const api = require(protocol);
  api.request = api.get = () => { throw new Error('Network disabled during corpus comparison'); };
}
globalThis.fetch = () => { throw new Error('Network disabled during corpus comparison'); };
const { Parser } = require('@asyncapi/parser');
const parserVersion = require('@asyncapi/parser/package.json').version;
const project = path.resolve(__dirname, '../..');
const corpus = path.join(project, 'parser/src/test/resources/conformance');
const manifestBytes = fs.readFileSync(path.join(corpus, 'manifest.json'));
const manifest = JSON.parse(manifestBytes);
const sha256 = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const localPaths = [project, project.replaceAll('\\', '/'), pathToFileURL(project).href];
const normalizeMessage = message => {
  let text = message;
  for (const prefix of localPaths.flatMap(p => [p, p.replace(/([A-Z]):/, (_, drive) => drive.toLowerCase() + ':')]))
    text = text.replaceAll(prefix, '<project>');
  return text;
};

async function main() {
  const outputArg = process.argv.indexOf('--output');
  const jvmArg = process.argv.indexOf('--jvm');
  const probe = process.argv.includes('--probe');
  const output = outputArg < 0 ? path.join(project, 'parser/build/reports/conformance/comparison.json')
    : path.resolve(process.argv[outputArg + 1]);
  const jvmPath = jvmArg < 0 ? path.join(project, 'parser/build/reports/conformance/asyncwright.json')
    : path.resolve(process.argv[jvmArg + 1]);
  const jvm = probe ? undefined : JSON.parse(fs.readFileSync(jvmPath, 'utf8'));
  if (parserVersion !== '3.6.6') throw new Error(`Unexpected parser version ${parserVersion}`);
  if (jvm && jvm.manifestSha256 !== sha256(manifestBytes)) throw new Error('JVM report is stale: manifest checksum differs');
  if (jvm && (jvm.results.length !== manifest.cases.length || new Set(jvm.results.map(r => r.id)).size !== manifest.cases.length))
    throw new Error('JVM report does not contain exactly one result per corpus case');
  for (const asset of manifest.assets || []) {
    if (sha256(fs.readFileSync(path.join(corpus, asset.file))) !== asset.sha256)
      throw new Error(`Asset checksum differs: ${asset.file}`);
  }
  const results = [];
  let failures = 0;
  for (const entry of manifest.cases) {
    const file = path.join(corpus, entry.file);
    const bytes = fs.readFileSync(file);
    if (sha256(bytes) !== entry.sha256) throw new Error(`Fixture checksum differs: ${entry.id}`);
    const result = await new Parser({ ruleset: { core: true, recommended: false } })
      .parse(bytes.toString('utf8'), { source: pathToFileURL(file).href });
    // Compare validation outcomes, never the expanded JavaScript document with the reference-preserving Java model.
    const diagnostics = result.diagnostics.map(d => ({ code: String(d.code), severity: d.severity,
      message: normalizeMessage(d.message),
      pointer: d.path.length ? '/' + d.path.map(token => String(token).replaceAll('~', '~0').replaceAll('/', '~1')).join('/') : '' }));
    const errorCodes = [...new Set(diagnostics.filter(d => d.severity === 0).map(d => d.code))].sort();
    const javascript = { successful: !!result.document && errorCodes.length === 0, documentPresent: !!result.document,
      errorCodes, diagnostics };
    const asyncwright = jvm?.results.find(r => r.id === entry.id);
    const matches = !jvm || (asyncwright && asyncwright.file === entry.file && asyncwright.sha256 === entry.sha256
      && asyncwright.successful === entry.asyncwright.successful
      && JSON.stringify(asyncwright.errorRules) === JSON.stringify(entry.asyncwright.errorRules)
      && javascript.successful === entry.javascript.successful
      && JSON.stringify(errorCodes) === JSON.stringify(entry.javascript.errorCodes));
    if (!matches) failures++;
    results.push({ id: entry.id, file: entry.file, sha256: entry.sha256, classification: entry.classification,
      reason: entry.reason, asyncwright, javascript, matchesBaseline: matches });
    console.log(`${entry.id}: JS=${javascript.successful ? 'accept' : 'reject'}${probe ? '' : `, JVM=${asyncwright?.successful ? 'accept' : 'reject'}, baseline=${matches ? 'match' : 'CHANGED'}`}`);
  }
  const comparable = results.filter(r => r.asyncwright);
  const summary = { cases: results.length, agreements: comparable.filter(r => r.asyncwright.successful === r.javascript.successful).length,
    differences: comparable.filter(r => r.asyncwright.successful !== r.javascript.successful).length,
    classifications: results.reduce((counts, result) => { counts[result.classification] = (counts[result.classification] || 0) + 1; return counts; }, {}),
    openImplementationGaps: results.filter(r => r.classification === 'implementation-gap').length, unexpectedChanges: failures };
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, JSON.stringify({ formatVersion: 1, parser: { name: '@asyncapi/parser', version: parserVersion },
    node: process.version, ruleset: { core: true, recommended: false }, network: 'disabled',
    manifestSha256: sha256(manifestBytes), lockSha256: sha256(fs.readFileSync(path.join(__dirname, 'package-lock.json'))),
    summary, results }, null, 2) + '\n');
  console.log(JSON.stringify(summary));
  if (failures) process.exitCode = 1;
}

main().catch(error => { console.error(error); process.exitCode = 1; });
