import * as THREE from 'three';
import {GLTFLoader} from 'three/addons/loaders/GLTFLoader.js';
import {OrbitControls} from 'three/addons/controls/OrbitControls.js';
import {Reflector} from 'three/addons/objects/Reflector.js';
import {EffectComposer} from 'three/addons/postprocessing/EffectComposer.js';
import {RenderPass} from 'three/addons/postprocessing/RenderPass.js';
import {UnrealBloomPass} from 'three/addons/postprocessing/UnrealBloomPass.js';
import {ShaderPass} from 'three/addons/postprocessing/ShaderPass.js';

const $=id=>document.getElementById(id);
const renderer=new THREE.WebGLRenderer({antialias:true,preserveDrawingBuffer:true});
renderer.setPixelRatio(Math.min(devicePixelRatio,1.5));
renderer.setSize(innerWidth,innerHeight);
renderer.outputColorSpace=THREE.SRGBColorSpace;
// Keep HDR values through bloom; tone-map once, at the very end.
renderer.toneMapping=THREE.NoToneMapping;
renderer.useLegacyLights=false;
renderer.shadowMap.enabled=true;renderer.shadowMap.type=THREE.PCFSoftShadowMap;
document.body.prepend(renderer.domElement);
const scene=new THREE.Scene();scene.background=new THREE.Color(0x071329);
scene.fog=new THREE.FogExp2(0x102039,.0014);
const sky=new THREE.Mesh(new THREE.SphereGeometry(1000,32,16),new THREE.ShaderMaterial({side:THREE.BackSide,depthWrite:false,
 vertexShader:'varying vec3 direction;void main(){direction=normalize(position);gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0);}',
 fragmentShader:'varying vec3 direction;void main(){float h=smoothstep(-0.05,0.6,direction.z);gl_FragColor=vec4(mix(vec3(.008,.018,.043),vec3(.0008,.002,.007),h),1.0);}'}));
scene.add(sky);
const orbitCamera=new THREE.PerspectiveCamera(42,innerWidth/innerHeight,1,2000);
orbitCamera.up.set(0,0,1);orbitCamera.position.set(100,-285,102);
const controls=new OrbitControls(orbitCamera,renderer.domElement);
controls.target.set(4,0,53);controls.enableDamping=true;controls.maxDistance=600;controls.update();controls.enabled=false;
const hdrTarget=new THREE.WebGLRenderTarget(innerWidth,innerHeight,{type:THREE.HalfFloatType});
// Three r152 otherwise allocates a 16-bit renderbuffer: inadequate for the
// thin, recessed window geometry at a several-hundred-unit viewing distance.
hdrTarget.depthTexture=new THREE.DepthTexture(innerWidth,innerHeight,THREE.UnsignedIntType);
const composer=new EffectComposer(renderer,hdrTarget),renderPass=new RenderPass(scene,orbitCamera);
composer.addPass(renderPass);
const bloomPass=new UnrealBloomPass(new THREE.Vector2(innerWidth,innerHeight),.45,.35,1.1);composer.addPass(bloomPass);
composer.addPass(new ShaderPass({uniforms:{tDiffuse:{value:null}},
 vertexShader:'varying vec2 vUv; void main(){vUv=uv;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0);}',
 fragmentShader:`uniform sampler2D tDiffuse;varying vec2 vUv;
 void main(){vec3 x=texture2D(tDiffuse,vUv).rgb*0.85;
 vec3 y=clamp((x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14),0.0,1.0);
 vec3 srgb=mix(12.92*y,1.055*pow(y,vec3(1.0/2.4))-0.055,step(vec3(0.0031308),y));
 float noise=fract(sin(dot(gl_FragCoord.xy,vec2(12.9898,78.233)))*43758.5453)-0.5;
 gl_FragColor=vec4(srgb+noise/255.0,1.0);}`}));

// A shallow ripple distorts the reflection, not the portable GLB geometry.
const shader=Reflector.ReflectorShader;
const waterShader={uniforms:{...shader.uniforms,time:{value:0}},vertexShader:shader.vertexShader,
 fragmentShader:shader.fragmentShader.replace('uniform vec3 color;','uniform vec3 color;\nuniform float time;')
 .replace('vec4 base = texture2DProj( tDiffuse, vUv );',
 `vec4 q = vUv;
  vec2 st = vUv.xy / vUv.w;
  q.xy += vec2(sin(st.y*320.0+time*1.8),cos(st.x*230.0-time))*0.0012*q.w;
  vec4 base = texture2DProj(tDiffuse,q);`)};
const water=new Reflector(new THREE.PlaneGeometry(900,900),{color:0x63788b,textureWidth:1024,textureHeight:1024,multisample:0,shader:waterShader});
water.getRenderTarget().depthTexture=new THREE.DepthTexture(1024,1024,THREE.UnsignedIntType);
water.position.z=-6.46;scene.add(water);
let root,mixer,shotCamera,portableWater,playing=true,time=0,previous=performance.now();
function camera(){return $('cinematic').checked&&shotCamera?shotCamera:orbitCamera;}
function resize(){renderer.setSize(innerWidth,innerHeight);composer.setSize(innerWidth,innerHeight);for(const c of [shotCamera,orbitCamera])if(c){c.aspect=innerWidth/innerHeight;c.updateProjectionMatrix();}}
function pose(t){time=Math.max(0,Math.min(10,t));mixer?.setTime(time);scene.updateMatrixWorld(true);water.material.uniforms.time.value=time;$('timeline').value=time;$('time').textContent=`${time.toFixed(1)} / 10`;}
function draw(){renderPass.camera=camera();bloomPass.enabled=$('bloom').checked;water.visible=$('reflection').checked;if(portableWater)portableWater.visible=!water.visible;composer.render();}
function toggle(){playing=!playing;$('play').textContent=playing?'Pause':'Play';}
$('play').onclick=toggle;
$('timeline').oninput=e=>{playing=false;$('play').textContent='Play';pose(Number(e.target.value));};
$('cinematic').onchange=()=>{if(!$('cinematic').checked&&shotCamera){shotCamera.getWorldPosition(orbitCamera.position);shotCamera.getWorldQuaternion(orbitCamera.quaternion);controls.target.set(4,0,53);controls.update();}controls.enabled=!$('cinematic').checked;};
async function fullscreen(){if(document.fullscreenElement)await document.exitFullscreen();else await document.documentElement.requestFullscreen();}
$('fullscreen').onclick=fullscreen;
addEventListener('keydown',e=>{if(e.target.tagName==='INPUT')return;if(e.code==='Space'){e.preventDefault();toggle();}if(e.key.toLowerCase()==='f')fullscreen();});
addEventListener('resize',resize);
try{
 const gltf=await new GLTFLoader().loadAsync('/scene.glb');root=gltf.scene;scene.add(root);
 root.traverse(o=>{if(o.isMesh){o.castShadow=!(o.material.emissive?.getHex()>0);o.receiveShadow=true;}
  if(o.isSpotLight){o.castShadow=true;o.shadow.mapSize.set(2048,2048);o.shadow.camera.near=1;o.shadow.bias=-.0001;o.shadow.normalBias=.04;}});
 shotCamera=gltf.cameras[0];shotCamera.near=Math.max(2,shotCamera.near);portableWater=root.getObjectByName('lake');
 mixer=new THREE.AnimationMixer(root);gltf.animations.forEach(clip=>mixer.clipAction(clip).play());resize();pose(0);
 $('status').remove();
 // Read-only inspection plus deterministic scrubbing for browser regression tests.
 window.castlePreview={setTime(t){playing=false;$('play').textContent='Play';pose(t);draw();},
  setView(position,target){$('cinematic').checked=false;controls.enabled=true;orbitCamera.position.fromArray(position);controls.target.fromArray(target);controls.update();draw();},
  inspect(){let lights=0,meshes=0,textures=0;root.traverse(o=>{if(o.isLight)lights++;if(o.isMesh){meshes++;if(o.material.map)textures++;}});return {time,lights,meshes,textures,clips:gltf.animations.length,camera:camera().position.toArray(),cameraNear:camera().near,depth24:composer.renderTarget1.depthTexture?.type===THREE.UnsignedIntType,comet:root.getObjectByName('comet')?.position.toArray()};}};
 function tick(now){requestAnimationFrame(tick);const dt=Math.min((now-previous)/1000,.08);previous=now;if(playing)pose((time+dt)%10);if(controls.enabled)controls.update();draw();}requestAnimationFrame(tick);
}catch(error){$('status').textContent=`Could not load scene: ${error.message}`;console.error(error);}
