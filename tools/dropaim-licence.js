#!/usr/bin/env node

'use strict';
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const DIR  = __dirname;
const PRIV = path.join(DIR, 'dropaim-private.pem');
const PUB  = path.join(DIR, 'dropaim-public.txt');
const REG  = path.join(DIR, 'issued-licences.csv');

const payload = deviceId => `DROPAIM-V1:${deviceId.trim().toUpperCase()}`;

function init() {
  if (fs.existsSync(PRIV)) {
    console.error('REFUSING: ' + PRIV + ' already exists.');
    console.error('A new key would invalidate every code you have issued.');
    console.error('Delete it deliberately if you really want to start over.');
    process.exit(1);
  }
  const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  fs.writeFileSync(PRIV, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
  const spki = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  fs.writeFileSync(PUB, spki + '\n');
  console.log('Keypair created.\n');
  console.log('  PRIVATE KEY : ' + PRIV);
  console.log('                *** back this up, never share it ***\n');
  console.log('  PUBLIC KEY  : ' + PUB);
  console.log('\nPaste this into Licence.kt as PUBLIC_KEY_B64:\n');
  console.log(spki + '\n');
}

function issue(deviceId, unit) {
  if (!fs.existsSync(PRIV)) { console.error('No private key. Run: node dropaim-licence.js init'); process.exit(1); }
  const id = deviceId.trim().toUpperCase();
  if (!/^[A-Z0-9-]{8,}$/.test(id)) { console.error('Device ID looks wrong: ' + deviceId); process.exit(1); }
  const key = crypto.createPrivateKey(fs.readFileSync(PRIV));
  const sig = crypto.sign('sha256', Buffer.from(payload(id), 'utf8'), key);
  const code = sig.toString('base64');

  const line = [new Date().toISOString(), id, (unit || '').replace(/,/g, ' '), code].join(',') + '\n';
  if (!fs.existsSync(REG)) fs.writeFileSync(REG, 'issued_utc,device_id,unit,code\n');
  fs.appendFileSync(REG, line);

  console.log('\n  Device ID : ' + id);
  if (unit) console.log('  Unit      : ' + unit);
  console.log('\n  ACTIVATION CODE (send this back):\n');
  console.log(code);
  console.log('\n  Registered in ' + path.basename(REG) + '\n');
}

function verify(deviceId, code) {
  if (!fs.existsSync(PUB)) { console.error('No public key file.'); process.exit(1); }
  const spki = fs.readFileSync(PUB, 'utf8').trim();
  const key = crypto.createPublicKey({ key: Buffer.from(spki, 'base64'), format: 'der', type: 'spki' });
  const ok = crypto.verify('sha256', Buffer.from(payload(deviceId), 'utf8'), key, Buffer.from(code.trim(), 'base64'));
  console.log(ok ? 'VALID — this code activates ' + deviceId.toUpperCase()
                 : 'INVALID — wrong code, or not for this device');
  process.exit(ok ? 0 : 2);
}

const [cmd, a, b] = process.argv.slice(2);
const unitIdx = process.argv.indexOf('--unit');
const unit = unitIdx > -1 ? process.argv[unitIdx + 1] : '';
if (cmd === 'init') init();
else if (cmd === 'issue' && a) issue(a, unit);
else if (cmd === 'verify' && a && b) verify(a, b);
else {
  console.log('Usage:');
  console.log('  node dropaim-licence.js init');
  console.log('  node dropaim-licence.js issue <DEVICE-ID> [--unit "name"]');
  console.log('  node dropaim-licence.js verify <DEVICE-ID> <CODE>');
}
