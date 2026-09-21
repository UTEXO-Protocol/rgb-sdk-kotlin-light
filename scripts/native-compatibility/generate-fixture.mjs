import fs from 'node:fs';
const [source, directory] = process.argv.slice(2);
if (!source || !directory) throw new Error('Expected generated binding source and build output directory');
const text = fs.readFileSync(source, 'utf8');
const names = [...text.matchAll(/external fun (\w+)\(/g)].map(match => match[1]);
if (names.length < 50 || new Set(names).size !== names.length) throw new Error('Unexpected FFI symbol surface');
const checks = new Map([...text.matchAll(/lib\.(\w+)\(\) and 0xFFFF\) != (\d+)/g)]
  .map(match => [match[1], Number(match[2])]));
const version = Number(text.match(/val bindings_contract_version = (\d+)/)?.[1]);
if (!version || checks.size < 15) throw new Error('Missing UniFFI integrity metadata');
let first = true;
const definitions = names.map(name => {
  if (name.endsWith('_uniffi_contract_version')) return `int ${name}(void) { checks++; return ${version} + BAD_CONTRACT; }`;
  if (checks.has(name)) {
    const adjustment = first ? ' + BAD_CHECKSUM' : '';
    first = false;
    return `int ${name}(void) { checks++; return ${checks.get(name)}${adjustment}; }`;
  }
  // Native.register resolves every symbol. Any attempt to allocate/serialize keys,
  // derive, sign, or otherwise enter an incompatible ordinary ABI kills the probe.
  return `void ${name}(void) { _Exit(90); }`;
});
fs.mkdirSync(directory, { recursive: true });
fs.writeFileSync(`${directory}/incompatible.c`, '#include <stdlib.h>\nstatic int checks = 0;\nint utexo_test_check_count(void) { return checks; }\n' + definitions.join('\n') + '\n');
fs.writeFileSync(`${directory}/symbols.txt`, names.join('\n') + '\n');
