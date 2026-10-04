const fs = require('fs');
const html = fs.readFileSync(process.argv[2], 'utf8');
const m = html.match(/<script>([\s\S]*?)<\/script>/);
if (!m) { console.log('NO INLINE SCRIPT'); process.exit(2); }
try {
  new Function(m[1]);
  console.log('SYNTAX OK, script chars: ' + m[1].length);
} catch (e) {
  console.log('SYNTAX ERROR: ' + e.message);
  process.exit(1);
}
