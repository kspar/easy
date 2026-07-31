import { createServer } from 'node:http'

let config = { grade: 100, feedback: 'All tests passed.', delaySec: 3 }
const PORT = 5111

const ui = `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>Mock Executor</title>
<style>
  body { font-family: system-ui; max-width: 420px; margin: 40px auto; background: #1e1e1e; color: #ccc; }
  label { display: block; margin-top: 12px; font-weight: 600; }
  input, textarea { width: 100%; box-sizing: border-box; padding: 6px; margin-top: 4px; background: #2d2d2d; color: #eee; border: 1px solid #555; border-radius: 4px; }
  button { margin-top: 16px; padding: 8px 20px; background: #3a3a3a; color: #eee; border: 1px solid #555; border-radius: 4px; cursor: pointer; }
  button:hover { background: #4a4a4a; }
  code { background: #2d2d2d; padding: 2px 5px; border-radius: 3px; }
  .saved { color: #6c6; margin-left: 8px; }
</style>
</head>
<body>
<h2>Mock Executor</h2>
<p>Requests received on <code>POST /v1/grade</code> will return these values after the configured delay.</p>
<label>Grade (0–100)<input type="number" id="grade" min="0" max="100"></label>
<label>Delay (seconds)<input type="number" id="delay" min="0" step="0.5"></label>
<label>Feedback<textarea id="feedback" rows="5"></textarea></label>
<button onclick="save()">Save</button><span class="saved" id="msg"></span>
<script>
  fetch('/config').then(r=>r.json()).then(c=>{
    document.getElementById('grade').value=c.grade;
    document.getElementById('delay').value=c.delaySec;
    document.getElementById('feedback').value=c.feedback;
  });
  function save(){
    fetch('/config',{method:'PUT',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({
        grade:+document.getElementById('grade').value,
        delaySec:+document.getElementById('delay').value,
        feedback:document.getElementById('feedback').value
      })
    }).then(()=>{
      const m=document.getElementById('msg'); m.textContent='Saved'; setTimeout(()=>m.textContent='',1500);
    });
  }
</script>
</body>
</html>`

const server = createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/') {
    res.writeHead(200, { 'Content-Type': 'text/html' })
    return res.end(ui)
  }

  if (req.url === '/config') {
    if (req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'application/json' })
      return res.end(JSON.stringify(config))
    }
    if (req.method === 'PUT') {
      const body = await readBody(req)
      config = { ...config, ...JSON.parse(body) }
      console.log('Config updated:', config)
      res.writeHead(200)
      return res.end()
    }
  }

  if (req.method === 'POST' && req.url === '/v1/grade') {
    const body = JSON.parse(await readBody(req))
    console.log(`Grading submission (image=${body.image_name}, delay=${config.delaySec}s, grade=${config.grade})`)
    await sleep(config.delaySec * 1000)
    res.writeHead(200, { 'Content-Type': 'application/json' })
    return res.end(JSON.stringify({ grade: config.grade, feedback: config.feedback }))
  }

  res.writeHead(404)
  res.end()
})

function readBody(req) {
  return new Promise((resolve) => {
    let data = ''
    req.on('data', (chunk) => (data += chunk))
    req.on('end', () => resolve(data))
  })
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

server.listen(PORT, () => {
  console.log(`Mock executor running at http://localhost:${PORT}`)
  console.log(`Web UI:    http://localhost:${PORT}/`)
  console.log(`Executor:  POST http://localhost:${PORT}/v1/grade`)
  console.log(`Current config:`, config)
})
