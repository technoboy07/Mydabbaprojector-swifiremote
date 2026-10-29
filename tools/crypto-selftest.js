// Verifies crypto.js against Node's built-in crypto and RFC 4231 HMAC vectors.
// Run: node tools/crypto-selftest.js
'use strict';
const nodeCrypto = require('crypto');
const C = require('../app/src/main/assets/www/crypto.js');
let failures = 0;
function check(name, got, want) {
  if (got !== want) { failures++; console.error('FAIL', name, '\n got ', got, '\n want', want); }
}
// RFC 4231 test case 2
check('rfc4231-2', C.toHex(C.hmac(C.utf8('Jefe'), C.utf8('what do ya want for nothing?'))),
  '5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843');
check('sha256-empty', C.toHex(C.sha256(new Uint8Array(0))),
  'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
for (let n = 0; n < 300; n++) {
  const data = nodeCrypto.randomBytes(n);
  const key = nodeCrypto.randomBytes(n % 130);
  check('sha256-' + n, C.toHex(C.sha256(data)), nodeCrypto.createHash('sha256').update(data).digest('hex'));
  check('hmac-' + n, C.toHex(C.hmac(key, data)), nodeCrypto.createHmac('sha256', key).update(data).digest('hex'));
}
// Cross-check one protocol value with a fixture also asserted by ProtocolTest.java.
check('pair-proof-fixture', C.toHex(C.hmac(C.utf8('ABCD0123EFGH4567'), C.utf8('m2r-pair-v1|00112233445566778899aabbccddeeff'))),
  nodeCrypto.createHmac('sha256', 'ABCD0123EFGH4567').update('m2r-pair-v1|00112233445566778899aabbccddeeff').digest('hex'));
console.log(failures === 0 ? 'crypto.js: all checks passed' : failures + ' failures');
process.exit(failures === 0 ? 0 : 1);
