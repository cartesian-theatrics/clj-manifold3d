// Standalone loopback-only preview. Does not touch journal state or its server.
const http=require('node:http'),fs=require('node:fs/promises'),path=require('node:path');
const root=path.resolve(__dirname,'..');
function createServer(){return http.createServer(async(req,res)=>{
 if(!['GET','HEAD'].includes(req.method)){res.writeHead(405).end();return;}
 try{
  const name=decodeURIComponent(new URL(req.url,'http://localhost').pathname);
  let file;
  if(name==='/scene.glb')file=path.join(root,'target/fairytale-castle-night.glb');
  else if(['/', '/index.html', '/viewer.js'].includes(name))file=path.join(root,'public/castle',name==='/viewer.js'?'viewer.js':'index.html');
  else if(name.startsWith('/vendor/three/')){
   const vendor=await fs.realpath(path.join(root,'node_modules/three'));
   file=await fs.realpath(path.resolve(vendor,name.slice('/vendor/three/'.length)));
   if(!file.startsWith(vendor+path.sep)){res.writeHead(403).end();return;}
  }else{res.writeHead(404).end();return;}
  const bytes=await fs.readFile(file);
  res.setHeader('Content-Type',({'.html':'text/html; charset=utf-8','.js':'text/javascript','.glb':'model/gltf-binary'})[path.extname(file)]||'application/octet-stream');
  res.setHeader('Cache-Control','no-store');res.setHeader('X-Content-Type-Options','nosniff');
  res.end(req.method==='HEAD'?undefined:bytes);
 }catch(_){res.writeHead(404).end('Not found');}
});}
module.exports={createServer};
if(require.main===module){const server=createServer();server.on('error',e=>{console.error(e);process.exitCode=1;});server.listen(Number(process.env.PORT||8092),'127.0.0.1',()=>console.log(`Castle scene → http://localhost:${server.address().port}/`));}
